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
            "WHERE (f.removedAt IS NULL OR a.saved = 1) AND (:feedId IS NULL OR a.feedId = :feedId) AND (:folderId IS NULL OR f.folderId = :folderId) " +
            "ORDER BY a.published DESC, a.id DESC",
    )
    fun observeRows(feedId: Long?, folderId: Long?): Flow<List<ArticleRow>>

    /** One article to show a look on: the feed's newest, or with [preferImage] the newest that names an image. */
    @Query(
        "SELECT a.*, f.title AS feedTitle, f.hasIcon AS feedHasIcon, f.folderId AS folderId, f.ownLook AS ownLook, f.look AS look, f.previewLines AS previewLines, f.language AS feedLanguage, f.fullPage AS feedFullPage " +
            "FROM article a JOIN feed f ON f.id = a.feedId WHERE f.removedAt IS NULL AND (:feedId IS NULL OR a.feedId = :feedId) " +
            "ORDER BY CASE WHEN :preferImage AND a.imageUrl IS NOT NULL THEN 0 ELSE 1 END, a.published DESC LIMIT 1",
    )
    suspend fun sample(feedId: Long?, preferImage: Boolean): ArticleRow?

    @Query("SELECT a.feedId AS feedId, COUNT(*) AS count FROM article a JOIN feed f ON f.id = a.feedId WHERE a.read = 0 AND f.removedAt IS NULL GROUP BY a.feedId")
    fun observeUnread(): Flow<List<UnreadCount>>

    @Query("SELECT COUNT(*) FROM article a JOIN feed f ON f.id = a.feedId WHERE f.removedAt IS NULL")
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
    @Query("SELECT * FROM article WHERE feedId = :feedId AND state = 2 AND firstSeen >= :since ORDER BY published DESC LIMIT :limit")
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

    @Query("SELECT id FROM article WHERE id NOT IN (SELECT articleId FROM article_search)")
    suspend fun unindexed(): List<Long>

    @Query("DELETE FROM article_search WHERE articleId NOT IN (SELECT id FROM article)")
    suspend fun forgetGone()

    @Query("SELECT COUNT(*) FROM article WHERE feedId = :feedId")
    suspend fun count(feedId: Long): Int

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
