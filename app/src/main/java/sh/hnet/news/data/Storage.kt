package sh.hnet.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.hnet.news.Preferences
import sh.hnet.news.content.Blocks
import sh.hnet.news.content.ImageStore

/** What is on the phone, in bytes: the articles' text, their images, and how much of each the two clearing actions would free. */
class Usage(val textBytes: Long, val imageBytes: Long, val clearableImageBytes: Long, val clearableArticles: Int, val clearableTextBytes: Long)

/**
 * What the app keeps and for how long (SPEC §13). Articles older than the wished age go with their
 * images, read or not; saved articles never go, and keep their images through both clearing
 * actions. An article that goes leaves its row behind, marked gone and emptied: the feed may still
 * name it, and without the row the next refresh would take it for a new one.
 */
class Storage(private val feedDao: FeedDao, private val articles: ArticleDao, private val images: ImageStore, private val preferences: Preferences) {
    /** Removes what is older than each feed's own period, or the app's. */
    suspend fun removeOld(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.Default) {
        val appDays = preferences.keepDays.first()
        for (feed in feedDao.feeds()) {
            val days = feed.keepDays ?: appDays
            if (days > 0) remove(articles.olderThan(feed.id, now - days * DAY_MS))
        }
    }

    /** "Clear images": every image but those of saved articles. */
    suspend fun clearImages() = withContext(Dispatchers.IO) { images.keepOnly(savedImages()) }

    /** "Clear stored articles": every article that is not saved, with its images. */
    suspend fun clearArticles() = withContext(Dispatchers.Default) { remove(articles.unsavedIds()) }

    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        val all = images.totalBytes()
        val saved = images.bytesOf(savedImages())
        Usage(articles.textBytes() + articles.searchBytes(), all, (all - saved).coerceAtLeast(0), articles.unsavedCount(), articles.unsavedTextBytes())
    }

    /** A feed's articles when it goes for good; its saved ones stay whole (SPEC §13.2). True when there are such. */
    suspend fun removeFeed(feedId: Long): Boolean = withContext(Dispatchers.Default) {
        val candidates = (articles.leadImages(feedId) + articles.contents(feedId).flatMap { imagesOf(it) }).toSet()
        articles.deleteContents(feedId)
        articles.deleteArticles(feedId)
        articles.forgetGone()
        deleteUnused(candidates)
        articles.count(feedId) > 0
    }

    private suspend fun savedImages(): Set<String> =
        (articles.savedLeadImages() + articles.savedContents().flatMap { imagesOf(it) }).toSet()

    private fun imagesOf(content: ArticleContent): List<String> = Blocks.images(Blocks.decode(content.feed) + Blocks.decode(content.page)).map { it.url }

    private suspend fun remove(ids: List<Long>) {
        if (ids.isEmpty()) return
        val candidates = HashSet<String>()
        ids.chunked(CHUNK).forEach { chunk ->
            candidates += articles.leadImagesOf(chunk)
            articles.contentsOf(chunk).forEach { candidates += imagesOf(it) }
            articles.deleteContentsOf(chunk)
            articles.deleteSearchOf(chunk)
            articles.setGone(chunk)
        }
        deleteUnused(candidates)
    }

    /**
     * Deletes the images among [candidates] that no article left uses. The same picture can stand
     * in two articles (one story in two feeds, an author's photo in every post): it goes with the
     * last of them. The bodies are searched as stored, without decoding them: every address in a
     * body counts, links too, which only ever keeps a file too many.
     */
    private suspend fun deleteUnused(candidates: Set<String>) {
        if (candidates.isEmpty()) return
        val unused = candidates.toHashSet()
        unused -= articles.leadImagesInUse().toSet()
        var offset = 0
        while (unused.isNotEmpty()) {
            val page = articles.contentsPage(CHUNK, offset)
            if (page.isEmpty()) break
            page.forEach { content ->
                listOfNotNull(content.feed, content.page).forEach { body -> ADDRESS.findAll(body).forEach { unused -= it.groupValues[1] } }
            }
            offset += page.size
        }
        withContext(Dispatchers.IO) { images.delete(unused) }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val CHUNK = 500
        /** An address in a stored body: the blocks' JSON writes them as "url":"…". */
        val ADDRESS = Regex(""""url":"([^"]+)"""")
    }
}
