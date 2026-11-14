package sh.hnet.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.news.content.Blocks
import sh.hnet.news.content.Fold

/** A passage of an article's text with the place of the match in it. */
class Passage(val text: String, val start: Int, val end: Int)

/**
 * Search over what is on the phone (SPEC §11): each article's title and the text the reader shows,
 * folded (content/Fold) into one row of `article_search`, found by containment of the typed text.
 * No network, no ranking: results come in the list's own order.
 */
class SearchIndex(private val dao: ArticleDao) {
    /** Writes an article's row again; called whenever its text changed. */
    suspend fun index(articleId: Long) {
        val article = dao.article(articleId) ?: return
        dao.upsert(ArticleSearch(articleId, Fold.fold(article.title + "\n" + body(articleId))))
    }

    /** Articles stored before the index existed, or whose row never got written. */
    suspend fun indexMissing() = withContext(Dispatchers.Default) {
        dao.forgetGone()
        dao.unindexed().forEach { index(it) }
    }

    /** The ids of the articles holding [query] (already folded). */
    suspend fun find(query: String): Set<Long> = dao.find(query).toHashSet()

    /**
     * Where [query] (already folded) stands in an article's text, with some words around it; null
     * when only the title holds it.
     */
    suspend fun passage(articleId: Long, query: String): Passage? = withContext(Dispatchers.Default) {
        val text = body(articleId).replace('\n', ' ')
        val at = Fold.fold(text).indexOf(query)
        if (at < 0) return@withContext null
        // From the start of a word a little before the match, so the match shows within two lines
        var from = (at - BEFORE).coerceAtLeast(0)
        if (from > 0) from = text.indexOf(' ', from).takeIf { it in 0 until at }?.plus(1) ?: at
        val to = (at + query.length + AFTER).coerceAtMost(text.length)
        val lead = if (from > 0) "…" else ""
        Passage(lead + text.substring(from, to), lead.length + at - from, lead.length + at - from + query.length)
    }

    private suspend fun body(articleId: Long): String {
        val content = dao.content(articleId)
        return Blocks.text(content?.page?.let { Blocks.decode(it) }?.takeIf { it.isNotEmpty() } ?: Blocks.decode(content?.feed))
    }

    private companion object {
        const val BEFORE = 30
        const val AFTER = 200
    }
}
