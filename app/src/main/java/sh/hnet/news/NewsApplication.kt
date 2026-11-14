package sh.hnet.news

import sh.hnet.news.data.SearchIndex
import sh.hnet.news.refresh.RefreshRequests
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.news.content.Blocks
import sh.hnet.news.content.ImageStore
import sh.hnet.news.data.ArticleDao
import sh.hnet.news.data.FeedRepository
import sh.hnet.news.data.NewsDatabase
import sh.hnet.news.feed.FeedFinder
import sh.hnet.news.feed.FeedIcons
import sh.hnet.news.net.HttpFetcher
import sh.hnet.news.net.SystemHttp
import sh.hnet.news.refresh.RefreshJobService
import sh.hnet.news.refresh.Refresher

class NewsApplication : Application() {
    /** Work that outlives a screen; lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob())

    val preferences: Preferences by lazy { Preferences(this) }

    val http: HttpFetcher by lazy { SystemHttp(this) }

    val finder: FeedFinder by lazy { FeedFinder(http) }

    val icons: FeedIcons by lazy { FeedIcons(this, http) }

    val images: ImageStore by lazy { ImageStore(this, http) }

    private val database: NewsDatabase by lazy { NewsDatabase.get(this) }

    val articles: ArticleDao by lazy { database.articleDao() }

    val feeds: FeedRepository by lazy {
        FeedRepository(database.feedDao(), icons, appScope).also {
            it.onPurge = ::deleteArticles
            it.onAdded = ::fetchFirst
        }
    }

    val search: SearchIndex by lazy { SearchIndex(articles) }

    val refresher: Refresher by lazy { Refresher(database.feedDao(), articles, http, images, preferences, search, appScope) }

    val refreshRequests: RefreshRequests by lazy { RefreshRequests(this, preferences, refresher, appScope) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Feeds unsubscribed just before the process went, whose Undo can't come any more
            feeds.purgeRemoved()
            // Articles a refresh was still fetching when the process went
            articles.endFetching()
            search.indexMissing()
            RefreshJobService.schedule(this@NewsApplication, preferences.refreshSettings())
        }
    }

    /** The first fetch of feeds just subscribed or imported, after the refresh under way if there is one. */
    private fun fetchFirst(feedIds: List<Long>) = refreshRequests.request(feedIds, afterCurrent = true)

    /** A feed's articles with their images, when the feed goes for good; its saved articles stay whole (SPEC §13.2). True when there are such. */
    private suspend fun deleteArticles(feedId: Long): Boolean {
        val urls = articles.leadImages(feedId) + articles.contents(feedId).flatMap { content ->
            Blocks.images(Blocks.decode(content.feed) + Blocks.decode(content.page)).map { it.url }
        }
        withContext(Dispatchers.IO) { images.delete(urls.toSet()) }
        articles.deleteContents(feedId)
        articles.deleteArticles(feedId)
        articles.forgetGone()
        return articles.count(feedId) > 0
    }
}
