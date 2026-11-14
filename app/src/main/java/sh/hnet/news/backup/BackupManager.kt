package sh.hnet.news.backup

import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.hnet.news.BuildConfig
import sh.hnet.news.NewsApplication
import sh.hnet.news.Source
import sh.hnet.news.content.Blocks
import sh.hnet.news.data.Article
import sh.hnet.news.data.ArticleContent
import sh.hnet.news.data.ArticleDao
import sh.hnet.news.data.ArticleState
import sh.hnet.news.data.Feed
import sh.hnet.news.data.FeedDao
import sh.hnet.news.data.Folder
import sh.hnet.news.refresh.RefreshJobService
import java.io.IOException
import java.time.Instant

/**
 * Backup and restore (SPEC §14.6), as Music's: writes the feeds and folders, the settings and the
 * saved articles to a file the user picked, and reads one back. A restore replaces everything in
 * the app. Nothing is logged: what a person reads stays private.
 */
class BackupManager(private val app: NewsApplication, private val feedDao: FeedDao, private val articles: ArticleDao, private val stamps: SharedPreferences) {
    private val resolver = app.contentResolver
    private val _lastBackup = MutableStateFlow(stamps.getLong(LAST_BACKUP, 0L).takeIf { it > 0 })

    /** When the last backup was saved on this phone; null before the first. Not a setting. */
    val lastBackup: StateFlow<Long?> = _lastBackup.asStateFlow()

    sealed interface Created {
        data class Saved(val fileName: String) : Created
        data class Failed(val fileName: String, val details: String) : Created
    }

    /** A picked file: checked ([check]), or not readable at all ([check] null, with [details]). */
    data class Picked(val fileName: String, val check: BackupCheck?, val details: String? = null)

    /** What a restore brought. */
    class Restored(val feeds: Int, val saved: Int)

    suspend fun create(uri: Uri): Created = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val text = Backup.encode(snapshot())
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("No output")).use { it.write(text.toByteArray()) }
            val now = System.currentTimeMillis()
            stamps.edit { putLong(LAST_BACKUP, now) }
            _lastBackup.value = now
            Created.Saved(fileName)
        } catch (e: IOException) {
            Created.Failed(fileName, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Created.Failed(fileName, e.javaClass.simpleName)
        }
    }

    /** Reads the picked file once and checks it; nothing changes yet. */
    suspend fun read(uri: Uri): Picked = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        try {
            val bytes = (resolver.openInputStream(uri) ?: throw IOException("No input")).use { input ->
                val read = input.readNBytes(MAX_BYTES + 1)
                if (read.size > MAX_BYTES) return@withContext Picked(fileName, BackupCheck.NotOurs("Larger than ${MAX_BYTES / 1_048_576} MB"))
                read
            }
            Picked(fileName, Backup.check(bytes.toString(Charsets.UTF_8)))
        } catch (e: IOException) {
            Picked(fileName, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: SecurityException) {
            Picked(fileName, null, e.javaClass.simpleName)
        }
    }

    /**
     * Replaces everything in the app with the backup: feeds, folders, settings and saved articles.
     * The feeds then get their first fetch, which also brings the saved articles' images back.
     */
    suspend fun restore(backup: BackupFile): Restored = withContext(Dispatchers.IO) {
        app.refresher.current()?.cancelAndJoin()
        val now = System.currentTimeMillis()
        articles.deleteAllSearch()
        articles.deleteAllContents()
        articles.deleteAll()
        feedDao.deleteAllFeeds()
        feedDao.deleteAllFolders()
        app.images.keepOnly(emptyList())
        app.icons.clear()

        val folders = HashMap<String, Long>()
        backup.folders.forEach { folder ->
            val name = folder.name.trim()
            if (name.isNotEmpty() && name.lowercase() !in folders) folders[name.lowercase()] = feedDao.insert(Folder(name = name, position = folder.position, collapsed = folder.collapsed))
        }
        val feeds = LinkedHashMap<String, Feed>()
        backup.feeds.forEach { stored ->
            if (stored.url.isBlank() || stored.url in feeds) return@forEach
            val feed = Feed(
                url = stored.url, title = stored.title.ifBlank { stored.url }, siteUrl = stored.siteUrl,
                folderId = stored.folder?.trim()?.lowercase()?.let(folders::get), position = stored.position,
                fullPage = stored.fullPage, contentChosen = stored.contentChosen, storeImages = stored.storeImages, backgroundRefresh = stored.backgroundRefresh,
                addedAt = now, ownLook = stored.ownLook, look = stored.look, previewLines = stored.previewLines, keepDays = stored.keepDays, language = stored.language,
            )
            feeds[stored.url] = feed.copy(id = feedDao.insert(feed))
        }
        // Saved articles of feeds no longer subscribed come back under a feed that only names them (as after Unsubscribe)
        val gone = HashMap<String, Long>()
        var saved = 0
        backup.saved.forEach { stored ->
            val feed = stored.feedUrl?.let(feeds::get)
            val feedId = feed?.id ?: gone.getOrPut(stored.feedTitle) {
                val id = feedDao.insert(Feed(url = "restoring:${gone.size}", title = stored.feedTitle, removedAt = now, addedAt = now))
                feedDao.entomb(id)
                id
            }
            val feedBody = stored.feed?.toString()
            val pageBody = stored.page?.toString()
            val pictures = stored.imageUrl != null || Blocks.images(Blocks.decode(pageBody ?: feedBody)).isNotEmpty()
            val id = articles.insert(
                Article(
                    feedId = feedId, guid = stored.guid, title = stored.title, link = stored.link, author = stored.author,
                    published = stored.published, firstSeen = now, preview = stored.preview, imageUrl = stored.imageUrl,
                    read = stored.read, saved = true,
                    // Incomplete, so the feed's next refresh fetches the images the backup leaves out
                    state = if (feed != null && feed.storeImages && pictures) ArticleState.INCOMPLETE else ArticleState.COMPLETE,
                    words = stored.words,
                ),
            )
            if (id < 0) return@forEach
            articles.upsert(ArticleContent(id, feedBody, pageBody))
            app.search.index(id)
            saved++
        }

        val source = when (backup.settings.source) {
            "folder" -> backup.settings.sourceFolder?.trim()?.lowercase()?.let(folders::get)?.let { Source.Folder(it) }
            "feed" -> backup.settings.sourceFeed?.let(feeds::get)?.let { Source.Feed(it.id) }
            else -> null
        } ?: Source.All
        app.preferences.restoreSettings(backup.settings, source)
        RefreshJobService.schedule(app, app.preferences.refreshSettings())
        app.feeds.restored(feeds.values.toList())
        Restored(feeds.size, saved)
    }

    private suspend fun snapshot(): BackupFile {
        val folders = feedDao.folders()
        val folderNames = folders.associate { it.id to it.name }
        // Every feed, those kept only for their saved articles too
        val all = feedDao.everyFeed().associateBy { it.id }
        val subscribed = all.values.filter { it.removedAt == null }
        val source = app.preferences.source.first()
        return BackupFile(
            appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            createdAt = Instant.now().toString(),
            settings = app.preferences.backupSettings().copy(
                source = when (source) {
                    Source.All -> "all"
                    is Source.Folder -> "folder"
                    is Source.Feed -> "feed"
                },
                sourceFolder = (source as? Source.Folder)?.let { folderNames[it.id] },
                sourceFeed = (source as? Source.Feed)?.let { all[it.id]?.url },
            ),
            folders = folders.map { BackupFolder(it.name, it.position, it.collapsed) },
            feeds = subscribed.map { feed ->
                BackupFeed(
                    url = feed.url, title = feed.title, siteUrl = feed.siteUrl, folder = feed.folderId?.let(folderNames::get), position = feed.position,
                    fullPage = feed.fullPage, contentChosen = feed.contentChosen, storeImages = feed.storeImages, backgroundRefresh = feed.backgroundRefresh,
                    ownLook = feed.ownLook, look = feed.look, previewLines = feed.previewLines, keepDays = feed.keepDays, language = feed.language,
                )
            },
            saved = articles.saved().map { article ->
                val feed = all[article.feedId]
                val content = articles.content(article.id)
                BackupArticle(
                    feedUrl = feed?.takeIf { it.removedAt == null }?.url, feedTitle = feed?.title.orEmpty(),
                    guid = article.guid, title = article.title, link = article.link, author = article.author, published = article.published,
                    read = article.read, preview = article.preview, imageUrl = article.imageUrl, words = article.words,
                    feed = Backup.body(content?.feed), page = Backup.body(content?.page),
                )
            },
        )
    }

    /** The name the picker shows for [uri]; null when its provider doesn't say. Never logged. */
    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val LAST_BACKUP = "last_backup"
        /** Far above any real backup: a thousand saved articles with their text are a few megabytes. */
        const val MAX_BYTES = 64 * 1_048_576
    }
}
