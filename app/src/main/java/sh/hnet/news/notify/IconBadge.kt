package sh.hnet.news.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.hnet.news.NewsApplication
import sh.hnet.news.Preferences
import sh.hnet.news.R
import sh.hnet.news.data.ArticleDao

/**
 * The badge on the app's icon (SPEC-widgets §8). Android draws one only for an app that has a
 * notification, so News keeps one silent notification while the badge has something to say –
 * optional, off by default, and the only notification the app has.
 *
 * What it counts (§8.4) is not the unread articles but those that arrived since the list was last
 * on screen and are still unread: "something new came". It goes (§8.5) when the list comes on
 * screen, when its number reaches zero and when the user swipes it away; each of those starts the
 * count again from that moment. The launcher decides what the mark looks like: a dot on a Pixel, a
 * number where the launcher shows one.
 */
class IconBadge(private val app: NewsApplication, private val preferences: Preferences, private val articles: ArticleDao, private val stamps: SharedPreferences) {
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    /** One at a time, in the order asked: starting over on leaving the list must be done before the update that follows it. */
    private val order = Mutex()

    /** The list is on screen: what arrives now is seen as it arrives. */
    @Volatile private var listVisible = false

    /**
     * The id of the newest article there was when the count last started over: the articles stored
     * after it are the new ones. An id rather than a time, because an article's time is that of its
     * feed's turn in a refresh, which may lie before the moment the user left the list although the
     * article itself arrived after it.
     */
    private var after: Long
        get() = stamps.getLong(AFTER, Long.MAX_VALUE)
        set(value) = stamps.edit { putLong(AFTER, value) }

    /** The list came on screen or left it: either way everything so far has been seen. */
    fun listShown(visible: Boolean) {
        listVisible = visible
        startOver()
    }

    /** Swiped away, or the switch was just turned on: the count starts again from here, and nothing shows until new articles come. */
    fun startOver() {
        manager.cancel(ID)
        app.appScope.launch { order.withLock { after = articles.newestId() } }
    }

    /** The switch was turned off (or Restore defaults did it): the notification goes; the permission and the channel are left as they are. */
    fun off() = manager.cancel(ID)

    /**
     * Whether the notification can be shown at all: the permission, notifications for the app and
     * the channel. With the switch on and this false, Settings says so (§8.1).
     */
    fun canShow(): Boolean {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Brings the notification in line with what is stored: after a refresh, and when the app leaves the foreground. */
    suspend fun update() = order.withLock {
        if (!preferences.iconBadge.first()) return@withLock
        if (listVisible) {
            after = articles.newestId()
            return@withLock
        }
        val feeds = articles.newByFeed(after)
        val count = feeds.sumOf { it.count }
        if (count == 0 || !canShow()) {
            manager.cancel(ID)
            return@withLock
        }
        channel()
        // The feeds the new articles came from, most articles first: three by name, the rest counted
        val named = feeds.take(NAMED_FEEDS).map { app.getString(R.string.notification_feed_count, it.title, it.count) }
        val others = feeds.size - NAMED_FEEDS
        val text = (named + listOfNotNull(app.getString(R.string.notification_other_feeds, others).takeIf { others > 0 })).joinToString(", ")
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)
        manager.notify(
            ID,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.notification_new_articles, count))
                .setContentText(text)
                .setNumber(count)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                // Opens the app as its icon does, with the list's filters as they were
                .setContentIntent(open?.let { PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) })
                .setDeleteIntent(PendingIntent.getBroadcast(app, 0, Intent(app, BadgeDismissReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .build(),
        )
    }

    /** The one channel, made when it is first needed: low importance – the lowest that still gives a badge – so no sound, no vibration, no heads-up. Its texts follow the app's language. */
    private fun channel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.channel_new_articles), NotificationManager.IMPORTANCE_LOW).apply {
                description = app.getString(R.string.channel_new_articles_description)
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    private companion object {
        const val CHANNEL = "new_articles"
        const val ID = 1
        const val AFTER = "badge_after_id"
        const val NAMED_FEEDS = 3
    }
}

/** The notification was swiped away: it stays away until new articles come (SPEC-widgets §8.5). */
class BadgeDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as NewsApplication).badge.startOver()
    }
}
