package sh.hnet.news.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.news.feed.FeedIcons
import sh.hnet.news.feed.Opml
import sh.hnet.news.feed.OpmlFeed

/** What an OPML file would add (SPEC §9): shown before anything changes. */
class ImportPlan(val all: Int, val folders: Int, val new: List<OpmlFeed>) {
    val skipped: Int get() = all - new.size
}

/** A deleted folder, kept until its Undo passes. */
class DeletedFolder(val folder: Folder, val feedIds: List<Long>)

/** The subscriptions and their folders: everything Settings › Feeds, Add a feed and a feed's page change. */
class FeedRepository(private val dao: FeedDao, private val icons: FeedIcons, private val scope: CoroutineScope) {
    /** Removes what else belongs to a feed that goes for good (its articles); set by the application. */
    var onPurge: suspend (Long) -> Boolean = { false }

    /** Fetches the feeds just subscribed for the first time (SPEC §7.2, §9); set by the application. */
    var onAdded: (List<Long>) -> Unit = {}

    val feeds: Flow<List<Feed>> = dao.observeFeeds()
    val folders: Flow<List<Folder>> = dao.observeFolders()

    fun feed(id: Long): Flow<Feed?> = dao.observeFeed(id)

    /** The subscribed feed at this address (scheme, "www." and a trailing slash aside), if any. */
    suspend fun subscribed(url: String): Feed? {
        val key = Opml.key(url)
        return dao.feeds().firstOrNull { Opml.key(it.url) == key }
    }

    /**
     * Subscribes; the icon is fetched afterwards, on its own.
     * @param newFolder The name of a folder to create and put the feed in; wins over [folderId]
     */
    suspend fun subscribe(url: String, title: String, siteUrl: String?, iconUrl: String?, folderId: Long?, newFolder: String?, fullPage: Boolean): Long {
        // An unsubscribed feed still waiting for its Undo holds the address
        purgeRemoved()
        val folder = newFolder?.let { folderNamed(it) } ?: folderId
        val id = dao.insert(Feed(url = url, title = title, siteUrl = siteUrl, folderId = folder, position = nextPosition(), fullPage = fullPage, addedAt = System.currentTimeMillis()))
        fetchIcon(id, url, siteUrl, iconUrl)
        onAdded(listOf(id))
        return id
    }

    private fun fetchIcon(id: Long, url: String, siteUrl: String?, iconUrl: String?) = scope.launch(Dispatchers.IO) {
        if (icons.fetch(id, url, siteUrl, iconUrl)) dao.setHasIcon(id, true)
    }

    private suspend fun nextPosition(): Int = (dao.feeds().maxOfOrNull { it.position } ?: -1) + 1

    /** The folder of this name (case aside), created when there is none. */
    private suspend fun folderNamed(name: String): Long {
        val folders = dao.folders()
        folders.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.id }
        return dao.insert(Folder(name = name, position = (folders.maxOfOrNull { it.position } ?: -1) + 1))
    }

    suspend fun rename(id: Long, title: String) = dao.setTitle(id, title)

    suspend fun setUrl(id: Long, url: String) = dao.setUrl(id, url)

    suspend fun setFolder(id: Long, folderId: Long?) = dao.setFolder(id, folderId)

    suspend fun moveToNewFolder(id: Long, name: String) = dao.setFolder(id, folderNamed(name))

    suspend fun setFullPage(id: Long, fullPage: Boolean) = dao.chooseContent(id, fullPage)

    suspend fun setLook(id: Long, own: Boolean, look: String?, previewLines: Int) = dao.setLook(id, own, look, previewLines)

    suspend fun setStoreImages(id: Long, on: Boolean) = dao.setStoreImages(id, on)

    suspend fun setBackgroundRefresh(id: Long, on: Boolean) = dao.setBackgroundRefresh(id, on)

    /** Unsubscribe (SPEC §8): the feed is gone from every list at once and for good once [purge] is called; [restore] takes it back. */
    suspend fun unsubscribe(id: Long) = dao.setRemoved(id, System.currentTimeMillis())

    suspend fun restore(id: Long) = dao.setRemoved(id, null)

    suspend fun purge(id: Long) {
        // Taken back meanwhile?
        if (dao.feed(id)?.removedAt == null) return
        if (onPurge(id)) {
            // Saved articles remain: the feed stays as theirs, shown nowhere else (SPEC §13.2)
            dao.entomb(id)
        } else {
            dao.delete(id)
            withContext(Dispatchers.IO) { icons.delete(id) }
        }
    }

    /** Feeds unsubscribed whose Undo never ended (the process went first); called at start. */
    suspend fun purgeRemoved() {
        dao.removedIds().forEach { purge(it) }
    }

    /** The user's own order (SPEC §6.6): every feed, in the order given; a group's order is its feeds' order within it. */
    suspend fun orderFeeds(ids: List<Long>) = dao.orderFeeds(ids)

    suspend fun orderFolders(ids: List<Long>) = dao.orderFolders(ids)

    suspend fun renameFolder(id: Long, name: String) = dao.renameFolder(id, name)

    suspend fun setCollapsed(id: Long, collapsed: Boolean) = dao.setCollapsed(id, collapsed)

    /** Deletes a folder; its feeds stay subscribed, outside it (SPEC §6.4). */
    suspend fun deleteFolder(folder: Folder): DeletedFolder {
        val inside = dao.feedIdsIn(folder.id)
        dao.emptyFolder(folder.id)
        dao.deleteFolder(folder.id)
        return DeletedFolder(folder, inside)
    }

    suspend fun restoreFolder(deleted: DeletedFolder) {
        dao.insert(deleted.folder)
        deleted.feedIds.forEach { dao.setFolder(it, deleted.folder.id) }
    }

    /** What importing a file's feeds would add: the ones not subscribed yet. */
    suspend fun plan(file: List<OpmlFeed>): ImportPlan {
        val have = dao.feeds().map { Opml.key(it.url) }.toSet()
        return ImportPlan(file.size, file.mapNotNull { it.folder?.lowercase() }.distinct().size, file.filter { Opml.key(it.xmlUrl) !in have })
    }

    /** Adds the plan's feeds; never removes or changes one (SPEC §9). Returns how many were added. */
    suspend fun import(plan: ImportPlan): Int {
        purgeRemoved()
        var position = nextPosition()
        val added = mutableListOf<Long>()
        for (entry in plan.new) {
            // Checked again: the file may be imported twice in a row
            if (subscribed(entry.xmlUrl) != null) continue
            val folder = entry.folder?.let { folderNamed(it) }
            val id = dao.insert(Feed(url = entry.xmlUrl, title = entry.title, siteUrl = entry.htmlUrl, folderId = folder, position = position++, addedAt = System.currentTimeMillis(), contentChosen = false))
            fetchIcon(id, entry.xmlUrl, entry.htmlUrl, null)
            added += id
        }
        if (added.isNotEmpty()) onAdded(added)
        return added.size
    }

    /** Every feed with its folder's name, by folder and name, for the OPML export. */
    suspend fun export(): List<OpmlFeed> {
        val folders = dao.folders().associate { it.id to it.name }
        return dao.feeds().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .map { OpmlFeed(it.title, it.url, it.siteUrl, it.folderId?.let { id -> folders[id] }) }
    }
}
