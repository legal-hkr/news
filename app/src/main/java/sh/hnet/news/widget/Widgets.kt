package sh.hnet.news.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.news.MainActivity
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.Source
import sh.hnet.news.data.Feed

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

    /** Add a feed: the widget's "no feeds yet" state. */
    data object AddFeed : WidgetRequest

    companion object {
        const val SCHEME = "news-widget"

        fun of(address: Uri?): WidgetRequest? = when {
            address?.scheme != SCHEME -> null
            address.host == "add" -> AddFeed
            address.host == "list" -> List(Source.decode(address.getQueryParameter("source")))
            else -> null
        }
    }
}

/**
 * The home-screen widgets (SPEC-widgets): plain remote views, as Clock's and Music's. They show
 * what is stored – a widget never starts a refresh (§2.6) – and count unread articles only (§2.7).
 * Redrawn (§3.6) when a refresh ends, when the app leaves the foreground (whatever was read, added
 * or moved there), at once for Mark all as read, on a theme or language change and when the
 * launcher asks. Texts are set here, at run time: a layout's own strings would be resolved by the
 * launcher, in the system's language rather than the app's.
 */
object Widgets {
    /** Redraws every placed widget from what is stored. */
    fun update(context: Context) {
        val app = context.applicationContext as NewsApplication
        val manager = AppWidgetManager.getInstance(app)
        val counts = manager.getAppWidgetIds(ComponentName(app, CountWidget::class.java))
        if (counts.isEmpty()) return
        app.appScope.launch {
            val feeds = app.feeds.feeds.first()
            val folders = app.feeds.folders.first().mapTo(HashSet()) { it.id }
            val unread = app.articles.observeUnread().first().associate { it.feedId to it.count }
            withContext(Dispatchers.Main) {
                counts.forEach { id ->
                    val source = existing(app, id, feeds, folders)
                    manager.updateAppWidget(id, count(app, source, feeds.isEmpty(), unreadOf(source, feeds, unread)))
                }
            }
        }
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

    /** Opens the app on what the widget asks for; the address tells the intents of different widgets apart. */
    private fun open(context: Context, what: String, source: Source): PendingIntent {
        val address = Uri.Builder().scheme(WidgetRequest.SCHEME).authority(what).appendQueryParameter("source", source.encode()).build()
        val intent = Intent(Intent.ACTION_VIEW, address, context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private const val MAX_COUNT = 99
}

/** Count, 1×1 (SPEC-widgets §4). */
class CountWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.update(context)

    override fun onDeleted(context: Context, ids: IntArray) = WidgetSources.forget(context, ids)
}
