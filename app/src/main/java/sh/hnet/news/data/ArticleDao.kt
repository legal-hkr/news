package sh.hnet.news.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    /**
     * The articles of a source, newest first: every feed's when both are null, one feed's, or one
     * folder's. Read and unread alike; the list filters and orders what it shows itself (SPEC §2.8).
     * Saved articles of a feed unsubscribed from are still here (§13.2).
     */
    @Query(
        "SELECT a.*, f.title AS feedTitle, f.hasIcon AS feedHasIcon, f.folderId AS folderId, f.ownLook AS ownLook, f.look AS look, f.previewLines AS previewLines, f.language AS feedLanguage, f.fullPage AS feedFullPage " +
            "FROM article a JOIN feed f ON f.id = a.feedId " +
            "WHERE a.gone = 0 AND (f.removedAt IS NULL OR a.saved = 1) AND (:feedId IS NULL OR a.feedId = :feedId) AND (:folderId IS NULL OR f.folderId = :folderId) " +
            "ORDER BY a.published DESC, a.id DESC",
    )
    fun observeRows(feedId: Long?, folderId: Long?): Flow<List<ArticleRow>>

    /** The newest article's id; ids only grow, so an article with a greater one was stored later. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM article")
    suspend fun newestId(): Long

    /** Unread articles stored after the one with id [after], by feed, most first: what the icon badge counts (SPEC-widgets §8.4). */
    @Query(
        "SELECT f.title AS title, COUNT(*) AS count FROM article a JOIN feed f ON f.id = a.feedId " +
            "WHERE a.gone = 0 AND a.read = 0 AND a.id > :after AND f.removedAt IS NULL GROUP BY a.feedId ORDER BY count DESC, f.title",
    )
    suspend fun newByFeed(after: Long): List<NewInFeed>

    /** The newest unread articles of a source, for the widgets and for the reader opened from one (SPEC-widgets §5–§7). */
    @Query(
        "SELECT a.*, f.title AS feedTitle, f.hasIcon AS feedHasIcon, f.folderId AS folderId, f.ownLook AS ownLook, f.look AS look, f.previewLines AS previewLines, f.language AS feedLanguage, f.fullPage AS feedFullPage " +
            "FROM article a JOIN feed f ON f.id = a.feedId " +
            "WHERE a.gone = 0 AND a.read = 0 AND f.removedAt IS NULL AND (:feedId IS NULL OR a.feedId = :feedId) AND (:folderId IS NULL OR f.folderId = :folderId) " +
            "ORDER BY a.published DESC, a.id DESC LIMIT :limit",
    )
    suspend fun unreadRows(feedId: Long?, folderId: Long?, limit: Int): List<ArticleRow>

    /** One article to show a look on: the feed's newest, or with [preferImage] the newest that names an image. */
    @Query(
        "SELECT a.*, f.title AS feedTitle, f.hasIcon AS feedHasIcon, f.folderId AS folderId, f.ownLook AS ownLook, f.look AS look, f.previewLines AS previewLines, f.language AS feedLanguage, f.fullPage AS feedFullPage " +
            "FROM article a JOIN feed f ON f.id = a.feedId WHERE a.gone = 0 AND f.removedAt IS NULL AND (:feedId IS NULL OR a.feedId = :feedId) " +
            "ORDER BY CASE WHEN :preferImage AND a.imageUrl IS NOT NULL THEN 0 ELSE 1 END, a.published DESC LIMIT 1",
    )
    suspend fun sample(feedId: Long?, preferImage: Boolean): ArticleRow?

    @Query("SELECT a.feedId AS feedId, COUNT(*) AS count FROM article a JOIN feed f ON f.id = a.feedId WHERE a.read = 0 AND a.gone = 0 AND f.removedAt IS NULL GROUP BY a.feedId")
    fun observeUnread(): Flow<List<UnreadCount>>

    /** What the list could show at its widest: saved articles of feeds unsubscribed from are among it (SPEC §13.2). */
    @Query("SELECT COUNT(*) FROM article a JOIN feed f ON f.id = a.feedId WHERE a.gone = 0 AND (f.removedAt IS NULL OR a.saved = 1)")
    fun observeCount(): Flow<Int>

    @Query("SELECT guid FROM article WHERE feedId = :feedId")
    suspend fun guids(feedId: Long): List<String>

    /** -1 when the feed already has an article of this guid. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(article: Article): Long

    @Upsert
    suspend fun upsert(content: ArticleContent)

    @Query("SELECT * FROM article_content WHERE articleId = :articleId")
    suspend fun content(articleId: Long): ArticleContent?

    @Query("UPDATE article SET state = :state, imageUrl = :imageUrl, preview = :preview, words = :words WHERE id = :id")
    suspend fun setFetched(id: Long, state: Int, imageUrl: String?, preview: String?, words: Int)

    @Query("UPDATE article SET state = :state, imageUrl = :imageUrl, words = :words WHERE id = :id")
    suspend fun setCompleted(id: Long, state: Int, imageUrl: String?, words: Int)

    /** A feed's articles the next refresh completes (SPEC §10.5): the newest of those first seen since [since]. */
    @Query("SELECT * FROM article WHERE feedId = :feedId AND state = 2 AND gone = 0 AND firstSeen >= :since ORDER BY published DESC LIMIT :limit")
    suspend fun incomplete(feedId: Long, since: Long, limit: Int): List<Article>

    @Query("SELECT * FROM article WHERE id = :id")
    suspend fun article(id: Long): Article?

    @Query("UPDATE article SET readIndex = :index, readOffset = :offset WHERE id = :id")
    suspend fun setPosition(id: Long, index: Int, offset: Int)

    @Query("UPDATE article SET version = :version WHERE id = :id")
    suspend fun setVersion(id: Long, version: Int)

    @Query("UPDATE article SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

    @Upsert
    suspend fun upsert(search: ArticleSearch)

    /** By containment of the folded text; `instr` takes the text as it is, with no wildcards to escape. */
    @Query("SELECT articleId FROM article_search WHERE instr(folded, :query) > 0")
    suspend fun find(query: String): List<Long>

    @Query("SELECT id FROM article WHERE gone = 0 AND id NOT IN (SELECT articleId FROM article_search)")
    suspend fun unindexed(): List<Long>

    @Query("DELETE FROM article_search WHERE articleId NOT IN (SELECT id FROM article)")
    suspend fun forgetGone()

    @Query("DELETE FROM article_content WHERE articleId NOT IN (SELECT id FROM article)")
    suspend fun forgetOrphans()

    // Which images the articles that stay still use (data/Storage)

    @Query("SELECT imageUrl FROM article WHERE gone = 0 AND imageUrl IS NOT NULL")
    suspend fun leadImagesInUse(): List<String>

    @Query("SELECT * FROM article_content ORDER BY articleId LIMIT :limit OFFSET :offset")
    suspend fun contentsPage(limit: Int, offset: Int): List<ArticleContent>

    @Query("SELECT COUNT(*) FROM article WHERE feedId = :feedId")
    suspend fun count(feedId: Long): Int

    // Backup and restore (SPEC §14.6)

    @Query("SELECT * FROM article WHERE saved = 1 AND gone = 0 ORDER BY published DESC")
    suspend fun saved(): List<Article>

    @Query("DELETE FROM article")
    suspend fun deleteAll()

    @Query("DELETE FROM article_content")
    suspend fun deleteAllContents()

    @Query("DELETE FROM article_search")
    suspend fun deleteAllSearch()

    /**
     * The articles whose page can be fetched again (Settings › Storage): those with an address whose
     * feed is set to full pages or which have a page stored, newest first; with [unread] or [saved]
     * only such ones.
     */
    @Query(
        "SELECT a.* FROM article a JOIN feed f ON f.id = a.feedId LEFT JOIN article_content c ON c.articleId = a.id " +
            "WHERE a.gone = 0 AND a.link IS NOT NULL AND (f.fullPage = 1 OR c.page IS NOT NULL) AND (:unread = 0 OR a.read = 0) AND (:saved = 0 OR a.saved = 1) " +
            "ORDER BY a.published DESC",
    )
    suspend fun refetchable(unread: Boolean, saved: Boolean): List<Article>

    @Query(
        "SELECT COUNT(*) FROM article a JOIN feed f ON f.id = a.feedId LEFT JOIN article_content c ON c.articleId = a.id " +
            "WHERE a.gone = 0 AND a.link IS NOT NULL AND (f.fullPage = 1 OR c.page IS NOT NULL) AND (:unread = 0 OR a.read = 0) AND (:saved = 0 OR a.saved = 1)",
    )
    suspend fun refetchableCount(unread: Boolean, saved: Boolean): Int

    // Storage (SPEC §13): what goes by age or by the clearing actions, and what is in use

    @Query("SELECT id FROM article WHERE feedId = :feedId AND saved = 0 AND gone = 0 AND published < :before")
    suspend fun olderThan(feedId: Long, before: Long): List<Long>

    @Query("SELECT id FROM article WHERE saved = 0 AND gone = 0")
    suspend fun unsavedIds(): List<Long>

    @Query("SELECT COUNT(*) FROM article WHERE saved = 0 AND gone = 0")
    suspend fun unsavedCount(): Int

    @Query("SELECT imageUrl FROM article WHERE id IN (:ids) AND imageUrl IS NOT NULL")
    suspend fun leadImagesOf(ids: List<Long>): List<String>

    @Query("SELECT * FROM article_content WHERE articleId IN (:ids)")
    suspend fun contentsOf(ids: List<Long>): List<ArticleContent>

    @Query("SELECT imageUrl FROM article WHERE saved = 1 AND imageUrl IS NOT NULL")
    suspend fun savedLeadImages(): List<String>

    @Query("SELECT c.feed AS feed, c.page AS page, c.articleId AS articleId FROM article_content c JOIN article a ON a.id = c.articleId WHERE a.saved = 1")
    suspend fun savedContents(): List<ArticleContent>

    @Query("DELETE FROM article_content WHERE articleId IN (:ids)")
    suspend fun deleteContentsOf(ids: List<Long>)

    @Query("DELETE FROM article_search WHERE articleId IN (:ids)")
    suspend fun deleteSearchOf(ids: List<Long>)

    /** The rows stay, emptied, so their feeds' next refresh does not bring the articles back as new. */
    @Query("UPDATE article SET gone = 1, read = 1, preview = NULL, imageUrl = NULL WHERE id IN (:ids)")
    suspend fun setGone(ids: List<Long>)

    @Query("SELECT id, guid FROM article WHERE feedId = :feedId AND gone = 1")
    suspend fun gone(feedId: Long): List<GoneArticle>

    @Query("DELETE FROM article WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COALESCE(SUM(COALESCE(LENGTH(CAST(feed AS BLOB)), 0) + COALESCE(LENGTH(CAST(page AS BLOB)), 0)), 0) FROM article_content")
    suspend fun textBytes(): Long

    @Query(
        "SELECT COALESCE(SUM(COALESCE(LENGTH(CAST(c.feed AS BLOB)), 0) + COALESCE(LENGTH(CAST(c.page AS BLOB)), 0)), 0) " +
            "FROM article_content c JOIN article a ON a.id = c.articleId WHERE a.saved = 0",
    )
    suspend fun unsavedTextBytes(): Long

    @Query("SELECT COALESCE(SUM(LENGTH(CAST(folded AS BLOB))), 0) FROM article_search")
    suspend fun searchBytes(): Long

    @Query("UPDATE article SET read = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean)

    /** Articles a refresh left half fetched when the process went: they are incomplete, not "fetching" for ever. */
    @Query("UPDATE article SET state = 2 WHERE state = 1")
    suspend fun endFetching()

    // What goes with a feed unsubscribed from: everything of it but its saved articles (SPEC §13.2)
    @Query("SELECT imageUrl FROM article WHERE feedId = :feedId AND imageUrl IS NOT NULL AND saved = 0")
    suspend fun leadImages(feedId: Long): List<String>

    @Query("SELECT c.feed AS feed, c.page AS page, c.articleId AS articleId FROM article_content c JOIN article a ON a.id = c.articleId WHERE a.feedId = :feedId AND a.saved = 0")
    suspend fun contents(feedId: Long): List<ArticleContent>

    @Query("DELETE FROM article_content WHERE articleId IN (SELECT id FROM article WHERE feedId = :feedId AND saved = 0)")
    suspend fun deleteContents(feedId: Long)

    @Query("DELETE FROM article WHERE feedId = :feedId AND saved = 0")
    suspend fun deleteArticles(feedId: Long)
}
