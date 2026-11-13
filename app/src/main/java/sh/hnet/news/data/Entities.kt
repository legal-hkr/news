package sh.hnet.news.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** A folder of feeds; one level (SPEC §6). */
@Entity(tableName = "folder")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Its place among the folders in the user's own order (SPEC §6.6). */
    val position: Int = 0,
    /** Collapsed on Settings › Feeds; remembered. */
    val collapsed: Boolean = false,
)

/** A subscription. */
@Entity(tableName = "feed", indices = [Index(value = ["url"], unique = true), Index("folderId")])
data class Feed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The feed's address. */
    val url: String,
    /** The name the user sees: the feed's own until renamed. */
    val title: String,
    /** The site the feed belongs to, as the feed names it. */
    val siteUrl: String? = null,
    /** Null for no folder. */
    val folderId: Long? = null,
    /** Its place in its group in the user's own order. */
    val position: Int = 0,
    /** Content: true fetches each article's page (Full page), false keeps what the feed sends (SPEC §8). */
    val fullPage: Boolean = false,
    val storeImages: Boolean = true,
    val backgroundRefresh: Boolean = true,
    /** The feed's or its site's icon is stored (FeedIcons.file). */
    val hasIcon: Boolean = false,
    val addedAt: Long = 0,
    /** Unsubscribed and waiting for the Undo to pass (SPEC §8); such a feed is shown nowhere and is deleted for good afterwards or at the next start. */
    val removedAt: Long? = null,
)
