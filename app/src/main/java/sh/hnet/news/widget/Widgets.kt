package sh.hnet.news.widget

import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.news.MainActivity
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.Source
import sh.hnet.news.data.ArticleRow
import sh.hnet.news.data.Feed
import sh.hnet.news.ui.articles.dayOf
import sh.hnet.news.ui.articles.shortDate
import sh.hnet.news.ui.articles.timeOfDay
import java.time.LocalDate
import java.time.ZoneId

/**
 * The source each placed widget shows (SPEC-widgets §7.1), by widget id, in a store of its own:
 * not a setting, so Restore defaults and backups leave it alone.
 */
object WidgetSources {
    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun has(context: Context, id: Int): Boolean = prefs(context).contains(key(id))

    fun get(context: Context, id: Int): Source = Source.decode(prefs(context).getString(key(id), null))

    fun set(context: Context, id: Int, source: Source) = prefs(context).edit { putString(key(id), source.encode()) }

    fun forget(context: Context, ids: IntArray) = prefs(context).edit { ids.forEach { remove(key(it)) } }

    /**
     * After a restore, which gives every feed and folder a new id: each widget follows its feed or
     * folder to the new id ([feeds] and [folders] map old to new). One the backup doesn't have keeps
     * its old source and shows All.
     */
    fun remap(context: Context, feeds: Map<Long, Long>, folders: Map<Long, Long>) {
        val prefs = prefs(context)
        prefs.edit {
            prefs.all.forEach { (key, value) ->
                val moved = when (val source = Source.decode(value as? String)) {
                    is Source.Feed -> feeds[source.id]?.let { Source.Feed(it) }
                    is Source.Folder -> folders[source.id]?.let { Source.Folder(it) }
                    Source.All -> null
                }
                if (key.startsWith(KEY) && moved != null) putString(key, moved.encode())
            }
        }
    }

    private const val KEY = "source:"

    private fun key(id: Int) = "$KEY$id"
}

/** What a tap on a widget asks the app for (SPEC-widgets §7.2), carried as the intent's address so that each widget's intent is its own. */
sealed interface WidgetRequest {
    /** The list on the widget's source, unread only. */
    data class List(val source: Source) : WidgetRequest

    /** The reader on an article, paging over the unread articles of the widget's source. */
    data class Article(val id: Long, val source: Source) : WidgetRequest

    /** Add a feed: the widget's "no feeds yet" state. */
    data object AddFeed : WidgetRequest

    companion object {
        const val SCHEME = "news-widget"

        fun of(address: Uri?): WidgetRequest? {
            if (address?.scheme != SCHEME) return null
            val source = Source.decode(address.getQueryParameter("source"))
            return when (address.host) {
                "add" -> AddFeed
                "list" -> List(source)
                "article" -> address.getQueryParameter("id")?.toLongOrNull()?.let { Article(it, source) }
                else -> null
            }
        }

        fun address(what: String, source: Source, articleId: Long? = null): Uri = Uri.Builder().scheme(SCHEME).authority(what)
            .appendQueryParameter("source", source.encode())
            .apply { articleId?.let { appendQueryParameter("id", it.toString()) } }
            .build()
    }
}

/**
 * The home-screen widgets (SPEC-widgets): Count (1×1), Newest article (2×2) and Headlines (4×2),
 * plain remote views as Clock's and Music's. They show what is stored – a widget never starts a
 * refresh (§2.6) – and count and list unread articles only (§2.7). Redrawn (§3.6) when a refresh
 * ends, when the app leaves the foreground (whatever was read, added or moved there), at once for
 * Mark all as read, on a theme or language change, when a widget is resized, just after midnight
 * (the Headlines' ages) and when the launcher asks. Texts are set here, at run time: a layout's own strings would be resolved by the launcher,
 * in the system's language rather than the app's. Pictures come from the phone's store alone.
 */
object Widgets {
    /** The moment the redraw after midnight is set for; 0 for none. */
    @Volatile private var dayChangeAt = 0L

    /** Asked for a redraw; several requests waiting become one. */
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val waiting = ConcurrentLinkedQueue<() -> Unit>()
    private val started = AtomicBoolean(false)

    /**
     * Redraws every placed widget from what is stored. The redraws run one after another, never two
     * at once (a slower, older one could otherwise write over a newer one), and requests that come
     * while one runs are met by a single redraw after it.
     *
     * @param done Called once a redraw that started after this request has finished: a broadcast receiver keeps its process until then
     */
    fun update(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext as NewsApplication
        done?.let { waiting += it }
        if (started.compareAndSet(false, true)) {
            app.appScope.launch {
                for (request in requests) {
                    // Those asked before this redraw starts are answered by it
                    val answered = generateSequence { waiting.poll() }.toList()
                    try {
                        draw(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Drawn again at the next request; a widget must never take the app down
                    } catch (_: OutOfMemoryError) {
                        // A picture too large to prepare: the same
                    } finally {
                        // Whoever waits is answered however the redraw ended: a receiver's process, a refresh's end
                        answered.forEach { runCatching(it) }
                    }
                }
            }
        }
        requests.trySend(Unit)
    }

    /** As [update], returning when the redraw is done: a background refresh ends only then. */
    suspend fun updateAndWait(context: Context) = suspendCancellableCoroutine { continuation -> update(context) { continuation.resume(Unit) } }

    private suspend fun draw(app: NewsApplication) {
        val manager = AppWidgetManager.getInstance(app)
        val counts = manager.getAppWidgetIds(ComponentName(app, CountWidget::class.java))
        val newest = manager.getAppWidgetIds(ComponentName(app, NewestWidget::class.java))
        val headlines = manager.getAppWidgetIds(ComponentName(app, HeadlinesWidget::class.java))
        scheduleDayChange(app, headlines.isNotEmpty())
        if (counts.isEmpty() && newest.isEmpty() && headlines.isEmpty()) return
        val feeds = app.feeds.feeds.first()
        val feedIds = feeds.mapTo(HashSet()) { it.id }
        val folders = app.feeds.folders.first().mapTo(HashSet()) { it.id }
        val unread = app.articles.observeUnread().first().associate { it.feedId to it.count }
        val noFeeds = feeds.isEmpty()
        val density = app.resources.displayMetrics.density
        suspend fun rows(source: Source, limit: Int) = app.articles.unreadRows((source as? Source.Feed)?.id, (source as? Source.Folder)?.id, limit)
        // A source that is not there falls back to All (SPEC-ui §4.6) for as long as it is not there: the widget keeps
        // its own, so a feed that returns (Undo after Unsubscribe) has its widget back
        fun source(id: Int) = WidgetSources.get(app, id).takeIf { it.exists(feedIds, folders) } ?: Source.All

        val drawn = HashMap<Int, RemoteViews>()
        counts.forEach { id ->
            val source = source(id)
            drawn[id] = count(app, source, noFeeds, unreadOf(source, feeds, unread))
        }
        newest.forEach { id ->
            val source = source(id)
            val row = if (noFeeds) null else rows(source, 1).firstOrNull()
            // As wide as the widget is on this screen; the image view crops it to its box
            val width = (manager.getAppWidgetOptions(id).width() * density).toInt().coerceIn(MIN_PICTURE_PX, MAX_PICTURE_PX)
            // The box is as tall as the widget at most: a widget drawn tall keeps a tall picture
            val height = (manager.getAppWidgetOptions(id).height() * density).toInt().coerceAtLeast(width)
            val picture = row?.article?.imageUrl?.let { app.images.load(it, width) }?.within(width, height)
            drawn[id] = newest(app, source, noFeeds, unreadOf(source, feeds, unread), row, picture)
        }
        headlines.forEach { id ->
            val source = source(id)
            val options = manager.getAppWidgetOptions(id)
            // As many strips as fill the widget's height: the list does not scroll
            val strips = strips(options.height())
            // About how tall a strip then is: what the thumbnail and the headline are fitted to
            val stripDp = (room(options.height()) - (strips - 1) * STRIP_GAP_DP) / strips
            // The thumbnail fills the strip's height less its padding and keeps the designed 64 : 44
            val thumbnailDp = ((stripDp - 2 * STRIP_PADDING_DP) * 64 / 44).coerceIn(64, MAX_THUMBNAIL_DP)
            val list = if (noFeeds) emptyList() else rows(source, strips)
            val thumbnailWidth = (thumbnailDp * density).toInt()
            val thumbnails = list.associate { row -> row.article.id to row.article.imageUrl?.let { app.images.load(it, thumbnailWidth) }?.within(thumbnailWidth, thumbnailWidth) }
            // Narrower than three cells' worth there is no room for the age (§6)
            val showAge = options.width() >= AGE_FROM_DP
            drawn[id] = headlines(app, source, noFeeds, unreadOf(source, feeds, unread), list, thumbnails, showAge, strips, thumbnailDp, headlineLines = ((stripDp - 2 * TEXT_PADDING_DP - FEED_LINE_DP) / HEADLINE_LINE_DP).coerceIn(2, MAX_HEADLINE_LINES))
        }
        withContext(Dispatchers.Main) {
            drawn.forEach { (id, views) ->
                try {
                    manager.updateAppWidget(id, views)
                } catch (_: RuntimeException) {
                    // The system refused this widget's views (too much bitmap memory): it keeps what it shows
                }
            }
        }
    }

    /**
     * The picture for a box of at most [width] by [height]: scaled down to that width and, when it
     * is still taller than the box, cut to the box's height from its middle. The store decodes to
     * about the asked width, up to twice it, and a very tall picture (a long infographic) would
     * otherwise go over the limit the system sets for all the bitmaps of one widget update (§3.5).
     * The image view crops to its box anyway, so nothing that would be shown is lost.
     */
    private fun Bitmap.within(width: Int, height: Int): Bitmap {
        val scaled = if (this.width <= width) this else scale(width, (this.height.toLong() * width / this.width).toInt().coerceAtLeast(1))
        return if (scaled.height <= height) scaled else Bitmap.createBitmap(scaled, 0, (scaled.height - height) / 2, scaled.width, height)
    }

    /**
     * The ages in the Headlines widget are words of the day they were drawn on ("14:20",
     * "yesterday"): one redraw just after midnight keeps them true. An inexact alarm, which needs no
     * permission; none while no such widget is placed.
     */
    private fun scheduleDayChange(context: Context, wanted: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, REQUEST_DAY, Intent(context, HeadlinesWidget::class.java).setAction(ACTION_DAY_CHANGE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!wanted) {
            if (dayChangeAt != 0L) alarms.cancel(intent)
            dayChangeAt = 0
            return
        }
        val zone = ZoneId.systemDefault()
        val next = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 60_000
        // Set once for a midnight, not at every redraw; another time zone or clock gives another midnight
        if (next != dayChangeAt) {
            alarms.set(AlarmManager.RTC, next, intent)
            dayChangeAt = next
        }
    }

    /** The widget's width in dp as the launcher reports it (in portrait), or what a four-cell widget has when it doesn't. */
    private fun Bundle.width(): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 328

    /** The widget's height in dp as the launcher reports it (in portrait), or what a two-cell widget has when it doesn't. */
    private fun Bundle.height(): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180

    /**
     * How many strips a Headlines widget of this height shows: as many whole ones as fit at 60 dp
     * – the designed 56 and the room the feed's name over two lines of headline needs – with 6 dp
     * between them. The layout then shares the real height among them, so
     * they are a little taller than designed and fill the widget exactly – no part of a further
     * strip at the bottom, and nothing to scroll (the owner's rules over §6's fixed 56 dp and
     * scrolling list).
     */
    private fun strips(widgetHeight: Int): Int {
        return ((room(widgetHeight) + STRIP_GAP_DP) / (STRIP_DP + STRIP_GAP_DP)).coerceAtLeast(1)
    }

    /** The height the strips of a Headlines widget share, in dp: the widget's less the room the badge takes above the first strip. */
    private fun room(widgetHeight: Int): Int = (widgetHeight - LIST_TOP_DP).coerceAtLeast(STRIP_DP)

    private fun unreadOf(source: Source, feeds: List<Feed>, unread: Map<Long, Int>): Int = when (source) {
        Source.All -> feeds.sumOf { unread[it.id] ?: 0 }
        is Source.Feed -> unread[source.id] ?: 0
        is Source.Folder -> feeds.filter { it.folderId == source.id }.sumOf { unread[it.id] ?: 0 }
    }

    /** Count, 1×1 (§4): the number up to 99, then "99+"; a double check for nothing unread, a plus for no feeds yet. */
    private fun count(context: Context, source: Source, noFeeds: Boolean, unread: Int): RemoteViews = RemoteViews(context.packageName, R.layout.widget_count).apply {
        val number = unread > 0 && !noFeeds
        setViewVisibility(R.id.count, if (number) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.state, if (number) View.GONE else View.VISIBLE)
        if (number) {
            setTextViewText(R.id.count, if (unread > MAX_COUNT) "$MAX_COUNT+" else unread.toString())
        } else {
            setImageViewResource(R.id.state, if (noFeeds) R.drawable.ic_add else R.drawable.ic_done_all_bold)
        }
        // "99+" is wider: its end padding is 12 dp instead of 14
        val density = context.resources.displayMetrics.density
        setViewPadding(R.id.pill, (10 * density).toInt(), 0, ((if (unread > MAX_COUNT) 12 else 14) * density).toInt(), 0)
        setContentDescription(
            R.id.pill,
            when {
                noFeeds -> context.getString(R.string.label_add_first_feed)
                unread == 0 -> context.getString(R.string.content_description_widget_all_read)
                unread > MAX_COUNT -> context.getString(R.string.content_description_widget_unread_many)
                else -> context.getString(R.string.content_description_widget_unread, unread)
            },
        )
        setOnClickPendingIntent(R.id.pill, open(context, if (noFeeds) "add" else "list", source))
    }

    /** The badge on a frame's or the first strip's corner (§3.3): the full number, or the feed symbol alone in a circle for nothing unread (the widget says "Everything read." itself then); none without feeds. */
    private fun RemoteViews.badge(context: Context, source: Source, noFeeds: Boolean, unread: Int) {
        setViewVisibility(R.id.badge, if (noFeeds) View.GONE else View.VISIBLE)
        if (noFeeds) return
        setViewVisibility(R.id.badge_count, if (unread > 0) View.VISIBLE else View.GONE)
        if (unread > 0) setTextViewText(R.id.badge_count, unread.toString())
        // The symbol alone stands in the middle of a circle
        val density = context.resources.displayMetrics.density
        setViewPadding(R.id.badge, ((if (unread > 0) 9 else 7) * density).toInt(), 0, ((if (unread > 0) 12 else 7) * density).toInt(), 0)
        setContentDescription(R.id.badge, if (unread > 0) context.getString(R.string.content_description_widget_unread, unread) else context.getString(R.string.content_description_widget_all_read))
        setOnClickPendingIntent(R.id.badge, open(context, "list", source))
    }

    /** Newest article, 2×2 (§5): the newest unread article of the source in one frame, with its picture when that is on the phone. */
    private fun newest(context: Context, source: Source, noFeeds: Boolean, unread: Int, row: ArticleRow?, picture: Bitmap?): RemoteViews {
        if (row == null) {
            return RemoteViews(context.packageName, R.layout.widget_newest_state).apply {
                setImageViewResource(R.id.state, if (noFeeds) R.drawable.ic_add else R.drawable.ic_done_all)
                setTextViewText(R.id.state_text, context.getString(if (noFeeds) R.string.label_add_first_feed else R.string.msg_empty_all_read))
                setOnClickPendingIntent(R.id.frame, open(context, if (noFeeds) "add" else "list", source))
                badge(context, source, noFeeds, unread)
            }
        }
        return RemoteViews(context.packageName, if (picture != null) R.layout.widget_newest else R.layout.widget_newest_text).apply {
            if (picture != null) setImageViewBitmap(R.id.picture, picture)
            setTextViewText(R.id.feed, row.feedTitle)
            setTextViewText(R.id.headline, row.article.title)
            setContentDescription(R.id.frame, context.getString(R.string.content_description_widget_article, row.feedTitle, row.article.title))
            setOnClickPendingIntent(R.id.frame, open(context, "article", source, row.article.id))
            badge(context, source, noFeeds, unread)
        }
    }

    /** Headlines, 4×2 and other sizes (§6): the newest unread articles of the source, one strip each, as many as the widget's height holds. */
    private fun headlines(context: Context, source: Source, noFeeds: Boolean, unread: Int, rows: List<ArticleRow>, thumbnails: Map<Long, Bitmap?>, showAge: Boolean, strips: Int, thumbnailDp: Int, headlineLines: Int): RemoteViews {
        if (rows.isEmpty()) {
            return RemoteViews(context.packageName, R.layout.widget_headlines_state).apply {
                setImageViewResource(R.id.strip_icon, if (noFeeds) R.drawable.ic_add else R.drawable.ic_done_all)
                setTextViewText(R.id.strip_headline, context.getString(if (noFeeds) R.string.label_add_first_feed else R.string.msg_empty_all_read))
                setViewVisibility(R.id.strip_age, View.GONE)
                setOnClickPendingIntent(R.id.strip, open(context, if (noFeeds) "add" else "list", source))
                badge(context, source, noFeeds, unread)
            }
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val locale = context.resources.configuration.locales[0]
        return RemoteViews(context.packageName, R.layout.widget_headlines).apply {
            removeAllViews(R.id.list)
            rows.forEach { row ->
                val article = row.article
                // The time today, then "yesterday", then the date
                val age = when (dayOf(article.published, zone)) {
                    today -> timeOfDay(context, article.published)
                    today.minusDays(1) -> context.getString(R.string.label_age_yesterday)
                    else -> shortDate(locale, article.published, zone)
                }
                val thumbnail = thumbnails[article.id]
                addStableView(
                    R.id.list,
                    RemoteViews(context.packageName, R.layout.widget_headline_row).apply {
                        // A taller strip has a larger thumbnail and room for more of the headline
                        setViewLayoutWidth(R.id.strip_tile, thumbnailDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                        setInt(R.id.strip_headline, "setMaxLines", headlineLines)
                        setTextViewText(R.id.strip_feed, row.feedTitle)
                        setTextViewText(R.id.strip_headline, article.title)
                        setTextViewText(R.id.strip_age, age)
                        setViewVisibility(R.id.strip_age, if (showAge) View.VISIBLE else View.GONE)
                        // A row without a picture keeps its shape: the tile with the feed symbol stays under the empty thumbnail
                        setViewVisibility(R.id.strip_thumbnail, if (thumbnail != null) View.VISIBLE else View.GONE)
                        if (thumbnail != null) setImageViewBitmap(R.id.strip_thumbnail, thumbnail)
                        setContentDescription(R.id.strip, context.getString(R.string.content_description_widget_article, row.feedTitle, article.title))
                        setOnClickPendingIntent(R.id.strip, open(context, "article", source, article.id))
                    },
                    // The article's id folded into an int: the launcher keeps a strip that stays and redraws only what changed
                    (article.id xor (article.id ushr 32)).toInt(),
                )
            }
            // Fewer articles than places: empty places keep the strips above at their height
            repeat(strips - rows.size) { place ->
                addStableView(R.id.list, RemoteViews(context.packageName, R.layout.widget_headline_row).apply { setViewVisibility(R.id.strip, View.INVISIBLE) }, -1 - place)
            }
            badge(context, source, noFeeds, unread)
        }
    }

    /** Opens the app on what the widget asks for; the address tells the intents of different widgets apart. */
    private fun open(context: Context, what: String, source: Source, articleId: Long? = null): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, WidgetRequest.address(what, source, articleId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private const val MAX_COUNT = 99
    private const val REQUEST_DAY = 2
    const val ACTION_DAY_CHANGE = "sh.hnet.news.widget.DAY_CHANGE"
    /** A strip's padding above and below its content, a headline's line, and how far both the thumbnail and the headline grow with a taller strip. */
    private const val STRIP_PADDING_DP = 6
    private const val HEADLINE_LINE_DP = 18
    /** The text's own padding in a strip, and the feed's line with the space under it. */
    private const val TEXT_PADDING_DP = 4
    private const val FEED_LINE_DP = 15
    private const val MAX_HEADLINE_LINES = 4
    private const val MAX_THUMBNAIL_DP = 120
    private const val AGE_FROM_DP = 300
    /** A strip as designed, the gap between two, and the list's distance from the widget's top (§3.4, §6). */
    private const val STRIP_DP = 60
    private const val STRIP_GAP_DP = 6
    private const val LIST_TOP_DP = 12
    private const val MIN_PICTURE_PX = 320
    private const val MAX_PICTURE_PX = 900
}

/**
 * A widget's provider: it is redrawn with all the others, and forgets its source when it is
 * removed. The redraw runs off the receiver's thread, so the receiver asks the system to keep the
 * process until it is done – after a restart of the phone the process is started for this alone.
 */
abstract class NewsWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        // A new day, or another time zone or clock: the Headlines' ages are words of the day and the zone they were drawn in
        when (intent.action) {
            Widgets.ACTION_DAY_CHANGE, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> redraw(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    /** Resized: the Headlines widget fits its strips and hides the age when narrow, the Newest article's picture follows the width. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    override fun onDeleted(context: Context, ids: IntArray) = WidgetSources.forget(context, ids)

    private fun redraw(context: Context) {
        val pending = goAsync()
        Widgets.update(context) { pending.finish() }
    }
}

/** Count, 1×1 (SPEC-widgets §4). */
class CountWidget : NewsWidget()

/** Newest article, 2×2 (SPEC-widgets §5). */
class NewestWidget : NewsWidget()

/** Headlines, 4×2 (SPEC-widgets §6). */
class HeadlinesWidget : NewsWidget()
