package sh.hnet.news.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Query("SELECT * FROM feed WHERE removedAt IS NULL")
    fun observeFeeds(): Flow<List<Feed>>

    @Query("SELECT * FROM feed WHERE removedAt IS NULL")
    suspend fun feeds(): List<Feed>

    /** Removed feeds too: those kept for their saved articles (SPEC §13.2). */
    @Query("SELECT * FROM feed")
    suspend fun everyFeed(): List<Feed>

    @Query("DELETE FROM feed")
    suspend fun deleteAllFeeds()

    @Query("DELETE FROM folder")
    suspend fun deleteAllFolders()

    @Query("SELECT * FROM feed WHERE id = :id AND removedAt IS NULL")
    fun observeFeed(id: Long): Flow<Feed?>

    @Query("SELECT * FROM feed WHERE id = :id")
    suspend fun feed(id: Long): Feed?

    @Insert
    suspend fun insert(feed: Feed): Long

    @Query("UPDATE feed SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    @Query("UPDATE feed SET url = :url WHERE id = :id")
    suspend fun setUrl(id: Long, url: String)

    @Query("UPDATE feed SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: Long, folderId: Long?)

    @Query("UPDATE feed SET fullPage = :fullPage WHERE id = :id")
    suspend fun setFullPage(id: Long, fullPage: Boolean)

    @Query("UPDATE feed SET storeImages = :on WHERE id = :id")
    suspend fun setStoreImages(id: Long, on: Boolean)

    @Query("UPDATE feed SET keepDays = :days WHERE id = :id")
    suspend fun setKeepDays(id: Long, days: Int?)

    @Query("UPDATE feed SET backgroundRefresh = :on WHERE id = :id")
    suspend fun setBackgroundRefresh(id: Long, on: Boolean)

    @Query("UPDATE feed SET hasIcon = :on WHERE id = :id")
    suspend fun setHasIcon(id: Long, on: Boolean)

    @Query("UPDATE feed SET removedAt = :at WHERE id = :id")
    suspend fun setRemoved(id: Long, at: Long?)

    @Query("SELECT id FROM feed WHERE removedAt IS NOT NULL")
    suspend fun removedIds(): List<Long>

    /** A removed feed kept for its saved articles gives its address up; ids are unique, so is this. */
    @Query("UPDATE feed SET url = 'removed:' || id, folderId = NULL WHERE id = :id AND url NOT LIKE 'removed:%'")
    suspend fun entomb(id: Long)

    @Query("DELETE FROM feed WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE feed SET fullPage = :fullPage, contentChosen = 1 WHERE id = :id")
    suspend fun chooseContent(id: Long, fullPage: Boolean)

    @Query("UPDATE feed SET refreshedAt = :at, lastNew = :new, etag = :etag, lastModified = :lastModified, lastError = NULL, failingSince = NULL, failedAttempts = 0 WHERE id = :id")
    suspend fun setRefreshed(id: Long, at: Long, new: Int, etag: String?, lastModified: String?)

    @Query("UPDATE feed SET lastError = :error, failingSince = COALESCE(failingSince, :at), failedAttempts = failedAttempts + 1 WHERE id = :id")
    suspend fun setFailed(id: Long, error: String, at: Long)

    @Query("UPDATE feed SET lastError = :error WHERE id = :id")
    suspend fun setLastError(id: Long, error: String)

    @Query("UPDATE feed SET siteUrl = :siteUrl WHERE id = :id AND siteUrl IS NULL")
    suspend fun setSiteUrlIfNone(id: Long, siteUrl: String)

    @Query("UPDATE feed SET language = :language WHERE id = :id")
    suspend fun setLanguage(id: Long, language: String?)

    @Query("UPDATE feed SET ownLook = :own, look = :look, previewLines = :previewLines WHERE id = :id")
    suspend fun setLook(id: Long, own: Boolean, look: String?, previewLines: Int)

    @Query("UPDATE feed SET position = :position WHERE id = :id")
    suspend fun setFeedPosition(id: Long, position: Int)

    @Query("UPDATE folder SET position = :position WHERE id = :id")
    suspend fun setFolderPosition(id: Long, position: Int)

    /** In one transaction, so the lists are told once, with the whole order in place. */
    @Transaction
    suspend fun orderFeeds(ids: List<Long>) = ids.forEachIndexed { position, id -> setFeedPosition(id, position) }

    @Transaction
    suspend fun orderFolders(ids: List<Long>) = ids.forEachIndexed { position, id -> setFolderPosition(id, position) }

    @Query("SELECT * FROM folder")
    fun observeFolders(): Flow<List<Folder>>

    @Query("SELECT * FROM folder")
    suspend fun folders(): List<Folder>

    @Insert
    suspend fun insert(folder: Folder): Long

    @Query("UPDATE folder SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("UPDATE folder SET collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: Long, collapsed: Boolean)

    @Query("SELECT id FROM feed WHERE folderId = :folderId")
    suspend fun feedIdsIn(folderId: Long): List<Long>

    @Query("UPDATE feed SET folderId = NULL WHERE folderId = :folderId")
    suspend fun emptyFolder(folderId: Long)

    @Query("DELETE FROM folder WHERE id = :id")
    suspend fun deleteFolder(id: Long)
}
