package sh.hnet.news.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Query("SELECT * FROM feed WHERE removedAt IS NULL")
    fun observeFeeds(): Flow<List<Feed>>

    @Query("SELECT * FROM feed WHERE removedAt IS NULL")
    suspend fun feeds(): List<Feed>

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

    @Query("UPDATE feed SET backgroundRefresh = :on WHERE id = :id")
    suspend fun setBackgroundRefresh(id: Long, on: Boolean)

    @Query("UPDATE feed SET hasIcon = :on WHERE id = :id")
    suspend fun setHasIcon(id: Long, on: Boolean)

    @Query("UPDATE feed SET removedAt = :at WHERE id = :id")
    suspend fun setRemoved(id: Long, at: Long?)

    @Query("SELECT id FROM feed WHERE removedAt IS NOT NULL")
    suspend fun removedIds(): List<Long>

    @Query("DELETE FROM feed WHERE id = :id")
    suspend fun delete(id: Long)

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
