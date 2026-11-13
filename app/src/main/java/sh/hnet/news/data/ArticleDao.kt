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
     */
    @Query(
        "SELECT a.*, f.title AS feedTitle, f.hasIcon AS feedHasIcon, f.folderId AS folderId, f.ownLook AS ownLook, f.look AS look, f.previewLines AS previewLines " +
            "FROM article a JOIN feed f ON f.id = a.feedId " +
            "WHERE f.removedAt IS NULL AND (:feedId IS NULL OR a.feedId = :feedId) AND (:folderId IS NULL OR f.folderId = :folderId) " +
            "ORDER BY a.published DESC, a.id DESC",
    )
    fun observeRows(feedId: Long?, folderId: Long?): Flow<List<ArticleRow>>

    /** One article to show a look on: the feed's newest, or with [preferImage] the newest that names an image. */
    @Query(
        "SELECT a.*, f.title AS feedTitle, f.hasIcon AS feedHasIcon, f.folderId AS folderId, f.ownLook AS ownLook, f.look AS look, f.previewLines AS previewLines " +
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

    @Query("UPDATE article SET read = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean)

    /** Articles a refresh left half fetched when the process went: they are incomplete, not "fetching" for ever. */
    @Query("UPDATE article SET state = 2 WHERE state = 1")
    suspend fun endFetching()

    @Query("SELECT imageUrl FROM article WHERE feedId = :feedId AND imageUrl IS NOT NULL")
    suspend fun leadImages(feedId: Long): List<String>

    @Query("SELECT c.feed AS feed, c.page AS page, c.articleId AS articleId FROM article_content c JOIN article a ON a.id = c.articleId WHERE a.feedId = :feedId")
    suspend fun contents(feedId: Long): List<ArticleContent>

    @Query("DELETE FROM article_content WHERE articleId IN (SELECT id FROM article WHERE feedId = :feedId)")
    suspend fun deleteContents(feedId: Long)

    @Query("DELETE FROM article WHERE feedId = :feedId")
    suspend fun deleteArticles(feedId: Long)
}
