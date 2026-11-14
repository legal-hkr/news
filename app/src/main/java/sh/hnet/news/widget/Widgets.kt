package sh.hnet.news.widget

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

    private fun key(id: Int) = "source:$id"
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
 * Mark all as read, on a theme or language change, when a widget is resized and when the launcher
 * asks. Texts are set here, at run time: a layout's own strings would be resolved by the launcher,
 * in the system's language rather than the app's. Pictures come from the phone's store alone.
 */
object Widgets {
    /** Redraws every placed widget from what is stored. */
    fun update(context: Context) {
        val app = context.applicationContext as NewsApplication
        val manager = AppWidgetManager.getInstance(app)
        val counts = manager.getAppWidgetIds(ComponentName(app, CountWidget::class.java))
        val newest = manager.getAppWidgetIds(ComponentName(app, NewestWidget::class.java))
        val headlines = manager.getAppWidgetIds(ComponentName(app, HeadlinesWidget::class.java))
        if (counts.isEmpty() && newest.isEmpty() && headlines.isEmpty()) return
        app.appScope.launch {
            val feeds = app.feeds.feeds.first()
            val folders = app.feeds.folders.first().mapTo(HashSet()) { it.id }
            val unread = app.articles.observeUnread().first().associate { it.feedId to it.count }
            val noFeeds = feeds.isEmpty()
            val density = app.resources.displayMetrics.density
            suspend fun rows(source: Source, limit: Int) = app.articles.unreadRows((source as? Source.Feed)?.id, (source as? Source.Folder)?.id, limit)

            val drawn = HashMap<Int, RemoteViews>()
            counts.forEach { id ->
                val source = existing(app, id, feeds, folders)
                drawn[id] = count(app, source, noFeeds, unreadOf(source, feeds, unread))
            }
            newest.forEach { id ->
                val source = existing(app, id, feeds, folders)
                val row = if (noFeeds) null else rows(source, 1).firstOrNull()
                // As wide as the widget can get on this screen; the image view crops it to its box
                val width = (manager.getAppWidgetOptions(id).width() * density).toInt().coerceIn(MIN_PICTURE_PX, MAX_PICTURE_PX)
                val picture = row?.article?.imageUrl?.let { app.images.load(it, width) }?.atMost(width)
                drawn[id] = newest(app, source, noFeeds, unreadOf(source, feeds, unread), row, picture)
            }
            headlines.forEach { id ->
                val source = existing(app, id, feeds, folders)
                val list = if (noFeeds) emptyList() else rows(source, MAX_HEADLINES)
                val thumbnailWidth = (THUMBNAIL_DP * density).toInt()
                val thumbnails = list.associate { row -> row.article.id to row.article.imageUrl?.let { app.images.load(it, thumbnailWidth) }?.atMost(thumbnailWidth) }
                // Narrower than three cells' worth there is no room for the age (§6)
                val options = manager.getAppWidgetOptions(id)
                val showAge = options.width() >= AGE_FROM_DP
                drawn[id] = headlines(app, source, noFeeds, unreadOf(source, feeds, unread), list, thumbnails, showAge, stripHeight(options.height()))
            }
            withContext(Dispatchers.Main) { drawn.forEach { (id, views) -> manager.updateAppWidget(id, views) } }
        }
    }

    /**
     * The picture no wider than [width]: the store decodes to about the asked width, up to twice
     * it, and all the bitmaps of one widget update together have a limit set by the system (§3.5).
     */
    private fun Bitmap.atMost(width: Int): Bitmap = if (this.width <= width) this else scale(width, (height.toLong() * width / this.width).toInt().coerceAtLeast(1))

    /** The widget's width in dp as the launcher reports it (in portrait), or what a four-cell widget has when it doesn't. */
    private fun Bundle.width(): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 328

    /** The widget's height in dp as the launcher reports it (in portrait), or what a two-cell widget has when it doesn't. */
    private fun Bundle.height(): Int = getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180

    /**
     * How tall a strip is in a Headlines widget of this height, in dp: as many whole strips as fit
     * at the designed 56 dp with 6 dp between them, then stretched so that they fill the height
     * exactly – no part of a further strip shows at the bottom (the owner's rule over §6's fixed
     * 56 dp).
     */
    private fun stripHeight(widgetHeight: Int): Float {
        val room = (widgetHeight - LIST_TOP_DP).coerceAtLeast(STRIP_DP)
        val strips = ((room + STRIP_GAP_DP) / (STRIP_DP + STRIP_GAP_DP)).coerceAtLeast(1)
        return (room - (strips - 1) * STRIP_GAP_DP).toFloat() / strips
    }

    /** A widget's source; one that stops existing falls back to All, as the list's does (SPEC-ui §4.6). */
    private fun existing(context: Context, id: Int, feeds: List<Feed>, folders: Set<Long>): Source {
        val source = WidgetSources.get(context, id)
        val exists = when (source) {
            Source.All -> true
            is Source.Feed -> feeds.any { it.id == source.id }
            is Source.Folder -> source.id in folders
        }
        if (!exists) WidgetSources.set(context, id, Source.All)
        return if (exists) source else Source.All
    }

    private fun unreadOf(source: Source, feeds: List<Feed>, unread: Map<Long, Int>): Int = when (source) {
        Source.All -> feeds.sumOf { unread[it.id] ?: 0 }
        is Source.Feed -> unread[source.id] ?: 0
        is Source.Folder -> feeds.filter { it.folderId == source.id }.sumOf { unread[it.id] ?: 0 }
    }

    /** Count, 1×1 (§4): the number up to 99, then "99+"; a check for nothing unread, a plus for no feeds yet. */
    private fun count(context: Context, source: Source, noFeeds: Boolean, unread: Int): RemoteViews = RemoteViews(context.packageName, R.layout.widget_count).apply {
        val number = unread > 0 && !noFeeds
        setViewVisibility(R.id.count, if (number) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.state, if (number) View.GONE else View.VISIBLE)
        if (number) {
            setTextViewText(R.id.count, if (unread > MAX_COUNT) "$MAX_COUNT+" else unread.toString())
        } else {
            setImageViewResource(R.id.state, if (noFeeds) R.drawable.ic_add else R.drawable.ic_check)
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

    /** The badge on a frame's or the first strip's corner (§3.3): the full number, a check for nothing unread; none without feeds. */
    private fun RemoteViews.badge(context: Context, source: Source, noFeeds: Boolean, unread: Int) {
        setViewVisibility(R.id.badge, if (noFeeds) View.GONE else View.VISIBLE)
        if (noFeeds) return
        setViewVisibility(R.id.badge_count, if (unread > 0) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.badge_state, if (unread > 0) View.GONE else View.VISIBLE)
        if (unread > 0) setTextViewText(R.id.badge_count, unread.toString())
        setContentDescription(R.id.badge, if (unread > 0) context.getString(R.string.content_description_widget_unread, unread) else context.getString(R.string.content_description_widget_all_read))
        setOnClickPendingIntent(R.id.badge, open(context, "list", source))
    }

    /** Newest article, 2×2 (§5): the newest unread article of the source in one frame, with its picture when that is on the phone. */
    private fun newest(context: Context, source: Source, noFeeds: Boolean, unread: Int, row: ArticleRow?, picture: Bitmap?): RemoteViews {
        if (row == null) {
            return RemoteViews(context.packageName, R.layout.widget_newest_state).apply {
                setImageViewResource(R.id.state, if (noFeeds) R.drawable.ic_add else R.drawable.ic_check)
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

    /** Headlines, 4×2 and other sizes (§6): the newest unread articles of the source as a list that scrolls inside the widget. */
    private fun headlines(context: Context, source: Source, noFeeds: Boolean, unread: Int, rows: List<ArticleRow>, thumbnails: Map<Long, Bitmap?>, showAge: Boolean, stripHeight: Float): RemoteViews {
        if (rows.isEmpty()) {
            return RemoteViews(context.packageName, R.layout.widget_headlines_state).apply {
                setImageViewResource(R.id.strip_icon, if (noFeeds) R.drawable.ic_add else R.drawable.ic_check)
                setTextViewText(R.id.strip_headline, context.getString(if (noFeeds) R.string.label_add_first_feed else R.string.msg_empty_all_read))
                setViewVisibility(R.id.strip_age, View.GONE)
                setOnClickPendingIntent(R.id.strip, open(context, if (noFeeds) "add" else "list", source))
                badge(context, source, noFeeds, unread)
            }
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val locale = context.resources.configuration.locales[0]
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        rows.forEach { row ->
            val article = row.article
            // The time today, then "yesterday", then the date
            val day = dayOf(article.published, zone)
            val age = when (day) {
                today -> timeOfDay(context, article.published)
                today.minusDays(1) -> context.getString(R.string.label_age_yesterday)
                else -> shortDate(locale, article.published, zone)
            }
            val thumbnail = thumbnails[article.id]
            items.addItem(
                article.id,
                RemoteViews(context.packageName, R.layout.widget_headline_row).apply {
                    setViewLayoutHeight(R.id.strip, stripHeight, TypedValue.COMPLEX_UNIT_DIP)
                    setTextViewText(R.id.strip_headline, article.title)
                    setTextViewText(R.id.strip_age, age)
                    setViewVisibility(R.id.strip_age, if (showAge) View.VISIBLE else View.GONE)
                    // A row without a picture keeps its shape: the tile with the feed symbol stays under the empty thumbnail
                    setViewVisibility(R.id.strip_thumbnail, if (thumbnail != null) View.VISIBLE else View.GONE)
                    if (thumbnail != null) setImageViewBitmap(R.id.strip_thumbnail, thumbnail)
                    setContentDescription(R.id.strip, context.getString(R.string.content_description_widget_article, row.feedTitle, article.title))
                    // The list's one intent, filled in with this row's address
                    setOnClickFillInIntent(R.id.strip, Intent().setData(WidgetRequest.address("article", source, article.id)))
                },
            )
        }
        return RemoteViews(context.packageName, R.layout.widget_headlines).apply {
            setRemoteAdapter(R.id.list, items.build())
            // The rows share one intent the system fills in for each: it has to be changeable, and carries no address of its own
            val template = Intent(Intent.ACTION_VIEW, null, context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(context, REQUEST_ROWS, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
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
    /** As many articles as the Headlines widget carries (§6). */
    private const val MAX_HEADLINES = 20
    private const val REQUEST_ROWS = 1
    /** A thumbnail's box is 64 × 44 dp; decoded a little larger than its width. */
    private const val THUMBNAIL_DP = 80
    private const val AGE_FROM_DP = 300
    /** A strip as designed, the gap between two, and the list's distance from the widget's top (§3.4, §6). */
    private const val STRIP_DP = 56
    private const val STRIP_GAP_DP = 6
    private const val LIST_TOP_DP = 12
    private const val MIN_PICTURE_PX = 320
    private const val MAX_PICTURE_PX = 900
}

/** A widget's provider: it is redrawn with all the others, and forgets its source when it is removed. */
abstract class NewsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.update(context)

    /** Resized: the Headlines widget hides the age when narrow, the Newest article's picture follows the width. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = Widgets.update(context)

    override fun onDeleted(context: Context, ids: IntArray) = WidgetSources.forget(context, ids)
}

/** Count, 1×1 (SPEC-widgets §4). */
class CountWidget : NewsWidget()

/** Newest article, 2×2 (SPEC-widgets §5). */
class NewestWidget : NewsWidget()

/** Headlines, 4×2 (SPEC-widgets §6). */
class HeadlinesWidget : NewsWidget()
