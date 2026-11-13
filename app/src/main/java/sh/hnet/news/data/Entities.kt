package sh.hnet.news.data

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
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
    /** False until the content source was chosen: by the user at subscribing, or by the first refresh for an imported feed (SPEC §9). */
    @ColumnInfo(defaultValue = "1") val contentChosen: Boolean = true,
    /** Validators of the last answer, sent back so an unchanged feed costs no download. */
    val etag: String? = null,
    val lastModified: String? = null,
    /** The last refresh that reached the feed, and what it brought. */
    val refreshedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val lastNew: Int = 0,
    /** The kind of the last failure ([sh.hnet.news.refresh.FailureKind] by name); null after a success. */
    val lastError: String? = null,
    /** Since when every refresh has failed, and how many did (SPEC §10.3); "No network" doesn't count. */
    val failingSince: Long? = null,
    @ColumnInfo(defaultValue = "0") val failedAttempts: Int = 0,
    /** Its rows' own look instead of the app's (SPEC §4.10): [look] is a [sh.hnet.news.Look] by name. */
    @ColumnInfo(defaultValue = "0") val ownLook: Boolean = false,
    val look: String? = null,
    @ColumnInfo(defaultValue = "0") val previewLines: Int = 0,
    /** The language the feed names for itself (a tag such as "pl" or "en-US"), for hyphenation in the reader (SPEC §5.4). */
    val language: String? = null,
)

/** An article of a feed, without its body: what the list shows. */
@Entity(tableName = "article", indices = [Index(value = ["feedId", "guid"], unique = true), Index("published")])
data class Article(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    /** What tells it from the feed's other articles across refreshes. */
    val guid: String,
    val title: String,
    val link: String?,
    val author: String?,
    /** The feed's date; the time the app first saw the article when the feed gives none or one in the future. */
    val published: Long,
    val firstSeen: Long,
    /** The start of its text, plain, for the row's preview lines. */
    val preview: String?,
    /** The lead image's address; drawn only when stored (ImageStore). */
    val imageUrl: String?,
    val read: Boolean = false,
    val saved: Boolean = false,
    /** [ArticleState] by number. */
    val state: Int = ArticleState.COMPLETE,
    /** Words of the text shown, for the reading time. */
    val words: Int = 0,
    /** Where reading stopped (SPEC §5.9): the reader's first visible item and its offset; 0 and 0 for the top. */
    @ColumnInfo(defaultValue = "0") val readIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val readOffset: Int = 0,
    /** The version chosen for this article alone ([ArticleVersion] by number, SPEC §5.8). */
    @ColumnInfo(defaultValue = "0") val version: Int = 0,
)

/** Which of an article's two bodies the reader shows. */
object ArticleVersion {
    /** The full page when it is on the phone, else the feed's content. */
    const val DEFAULT = 0
    const val FEED = 1
    const val PAGE = 2
}

/** Whether everything the app meant to fetch for an article is on the phone (SPEC §4.3). */
object ArticleState {
    const val COMPLETE = 0
    /** Seen in the feed; its page or images are still being fetched in this refresh. */
    const val FETCHING = 1
    /** The full page could not be extracted, or images are missing. */
    const val INCOMPLETE = 2
}

/** An article's body as blocks in JSON (content/Blocks): what the feed sent and what was cut out of the page. Apart from [Article] so the list never reads it. */
@Entity(tableName = "article_content")
data class ArticleContent(
    @PrimaryKey val articleId: Long,
    val feed: String?,
    val page: String?,
)

/** An article with what the list needs of its feed. */
data class ArticleRow(
    @Embedded val article: Article,
    val feedTitle: String,
    val feedHasIcon: Boolean,
    val folderId: Long?,
    val ownLook: Boolean,
    val look: String?,
    val previewLines: Int,
    val feedLanguage: String?,
)

/** Unread articles of a feed. */
data class UnreadCount(val feedId: Long, val count: Int)
