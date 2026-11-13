package sh.hnet.news

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.hnet.news.data.FeedRepository
import sh.hnet.news.data.NewsDatabase
import sh.hnet.news.feed.FeedFinder
import sh.hnet.news.feed.FeedIcons
import sh.hnet.news.net.HttpFetcher
import sh.hnet.news.net.SystemHttp

class NewsApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    val http: HttpFetcher by lazy { SystemHttp(this) }

    val finder: FeedFinder by lazy { FeedFinder(http) }

    val icons: FeedIcons by lazy { FeedIcons(this, http) }

    val feeds: FeedRepository by lazy { FeedRepository(NewsDatabase.get(this).feedDao(), icons, appScope) }

    override fun onCreate() {
        super.onCreate()
        // Feeds unsubscribed just before the process went, whose Undo can't come any more
        appScope.launch { feeds.purgeRemoved() }
    }
}
