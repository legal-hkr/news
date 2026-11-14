package sh.hnet.news.refresh

import kotlinx.coroutines.flow.first
import sh.hnet.news.data.SearchIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import sh.hnet.news.Preferences
import sh.hnet.news.content.Block
import sh.hnet.news.content.Blocks
import sh.hnet.news.content.Extractor
import sh.hnet.news.content.HtmlBlocks
import sh.hnet.news.content.ImageStore
import sh.hnet.news.data.Article
import sh.hnet.news.data.ArticleContent
import sh.hnet.news.data.ArticleDao
import sh.hnet.news.data.ArticleState
import sh.hnet.news.data.Feed
import sh.hnet.news.data.FeedDao
import sh.hnet.news.feed.ContentAdvice
import sh.hnet.news.feed.FeedParser
import sh.hnet.news.feed.NotAFeedException
import sh.hnet.news.feed.ParsedItem
import sh.hnet.news.net.FetchFailure
import sh.hnet.news.net.HttpFetcher
import sh.hnet.news.net.HttpLimits
import sh.hnet.news.net.Validators
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Why a feed's refresh failed, as its page names it (SPEC §10.4); stored by name. */
enum class FailureKind { NO_NETWORK, NO_ANSWER, NOT_FOUND, NOT_A_FEED, REFUSED }

/** A refresh under way: [done] of [total] feeds. */
data class RefreshProgress(val done: Int, val total: Int)

/**
 * Refreshing (SPEC §2.1, §10): for each feed, its document (not sent again when unchanged), the
 * articles not seen before, and for each of those everything the feed's settings ask for – the
 * article's page with the text cut out of it, the images. An article is in the list as soon as the
 * feed named it, marked "Fetching" until its page and images are in, "Incomplete" if some of that
 * failed. One feed's failure is its own: noted on the feed, silent everywhere else.
 */
class Refresher(
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    private val http: HttpFetcher,
    private val images: ImageStore,
    private val preferences: Preferences,
    private val search: SearchIndex,
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<RefreshProgress?>(null)

    /** Null while no refresh runs. */
    val progress: StateFlow<RefreshProgress?> = _progress
    /** Called when a refresh ends, however it ends. */
    var afterRun: suspend () -> Unit = {}
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    /**
     * Starts a refresh unless one runs; false when one does.
     * @param feedIds The feeds to refresh; null for all
     * @param background Only the feeds that take part in background refresh
     * @param textOnly Images are not fetched (a metered network, SPEC §10.5); articles with images are then incomplete
     */
    fun start(feedIds: Collection<Long>? = null, background: Boolean = false, textOnly: Boolean = false): Job? {
        if (!running.compareAndSet(false, true)) return null
        return scope.launch(Dispatchers.Default) {
            try {
                run(feedIds, background, textOnly)
            } finally {
                withContext(NonCancellable) {
                    articleDao.endFetching()
                    // Bodies written for articles that went meanwhile (their feed was unsubscribed from during its fetch)
                    articleDao.forgetOrphans()
                    try {
                        afterRun()
                    } catch (_: Exception) {
                        // Tidying up is tried again after the next refresh
                    }
                }
                _progress.value = null
                running.set(false)
            }
        }.also { job = it }
    }

    /** The refresh under way, to wait for or cancel. */
    fun current(): Job? = job?.takeIf { it.isActive }

    private suspend fun run(feedIds: Collection<Long>?, background: Boolean, textOnly: Boolean) {
        val feeds = feedDao.feeds().filter { (feedIds == null || it.id in feedIds) && (!background || it.backgroundRefresh) }
        if (feeds.isEmpty()) return
        _progress.value = RefreshProgress(0, feeds.size)
        val new = AtomicInteger()
        val offline = AtomicBoolean(false)
        val reached = AtomicBoolean(false)
        val limit = Semaphore(PARALLEL_FEEDS)
        coroutineScope {
            feeds.map { feed ->
                async {
                    limit.withPermit {
                        // Without a network there is nothing to try the other feeds for
                        if (!offline.get()) {
                            try {
                                new.addAndGet(refresh(feed, textOnly))
                                reached.set(true)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: FetchFailure.NoNetwork) {
                                offline.set(true)
                                feedDao.setLastError(feed.id, FailureKind.NO_NETWORK.name)
                            } catch (e: FetchFailure) {
                                reached.set(true)
                                val kind = when (e) {
                                    is FetchFailure.NotFound -> FailureKind.NOT_FOUND
                                    is FetchFailure.Refused -> FailureKind.REFUSED
                                    else -> FailureKind.NO_ANSWER
                                }
                                feedDao.setFailed(feed.id, kind.name, System.currentTimeMillis())
                            } catch (_: NotAFeedException) {
                                reached.set(true)
                                feedDao.setFailed(feed.id, FailureKind.NOT_A_FEED.name, System.currentTimeMillis())
                            } catch (_: Exception) {
                                // Anything else (the phone's storage is full, a file can't be written): this feed's failure too,
                                // never the end of the other feeds' refresh or of the app
                                reached.set(true)
                                feedDao.setFailed(feed.id, FailureKind.NO_ANSWER.name, System.currentTimeMillis())
                            }
                        }
                        _progress.update { it?.copy(done = it.done + 1) }
                    }
                }
            }.awaitAll()
        }
        if (offline.get() && !reached.get()) preferences.setRefreshOffline() else preferences.setLastRefresh(System.currentTimeMillis(), new.get())
    }

    /** An article just inserted; [more] when its page or images are still to fetch. */
    private class Pending(val id: Long, val item: ParsedItem, val feedBlocks: List<Block>, val more: Boolean)

    /** One feed; the number of new articles. */
    private suspend fun refresh(feed: Feed, textOnly: Boolean): Int {
        val response = http.get(feed.url, HttpLimits.DOCUMENT, Validators(feed.etag, feed.lastModified))
        val now = System.currentTimeMillis()
        if (response.notModified) {
            feedDao.setRefreshed(feed.id, now, 0, response.etag, response.lastModified)
            if (!textOnly) completeEarlier(feed, feed.fullPage, now)
            return 0
        }
        val parsed = FeedParser.parse(response.body, response.charset, response.url)
        var fullPage = feed.fullPage
        if (!feed.contentChosen) {
            // An imported feed: the recommendation is worked out now (SPEC §9)
            fullPage = ContentAdvice.of(parsed) == ContentAdvice.FULL_PAGE
            feedDao.chooseContent(feed.id, fullPage)
        }
        parsed.siteUrl?.let { feedDao.setSiteUrlIfNone(feed.id, it) }
        parsed.language?.trim()?.takeIf { it.isNotEmpty() && it != feed.language }?.let { feedDao.setLanguage(feed.id, it) }
        // What an earlier refresh left incomplete, before the new articles: those are tried once in this one
        if (!textOnly) completeEarlier(feed, fullPage, now)
        val known = articleDao.guids(feed.id).toHashSet()
        // Rows of removed articles (SPEC §13) the feed no longer names have nothing left to guard against
        val named = parsed.items.mapTo(HashSet()) { it.id }
        articleDao.gone(feed.id).filter { it.guid !in named }.map { it.id }.chunked(500).forEach { articleDao.delete(it) }
        // What is older than the feed keeps articles for is not fetched to be removed at once: it is noted as gone
        val keepDays = feed.keepDays ?: preferences.keepDays.first()
        val oldest = if (keepDays > 0) now - keepDays * DAY_MS else Long.MIN_VALUE
        val pending = parsed.items.filter { it.id !in known }.mapNotNull { item ->
            val date = item.published?.toEpochMilli()
            if (date != null && date < oldest) {
                articleDao.insert(Article(feedId = feed.id, guid = item.id, title = item.title ?: item.link ?: "", link = item.link, author = null, published = date, firstSeen = now, preview = null, imageUrl = null, read = true, gone = true))
                return@mapNotNull null
            }
            val feedBlocks = HtmlBlocks.convert(item.contentHtml ?: item.summaryHtml ?: "", item.link ?: feed.url)
            val more = (fullPage && item.link != null) || (feed.storeImages && (item.imageUrl != null || Blocks.images(feedBlocks).isNotEmpty()))
            val article = Article(
                feedId = feed.id,
                guid = item.id,
                title = item.title ?: item.link ?: "",
                link = item.link,
                author = item.author,
                // No date, or one in the future (a wrong clock, a scheduled post): when we first saw it
                published = item.published?.toEpochMilli()?.takeIf { it <= now + DAY_MS } ?: now,
                firstSeen = now,
                preview = preview(item, feedBlocks),
                imageUrl = item.imageUrl,
                state = if (more) ArticleState.FETCHING else ArticleState.COMPLETE,
                words = Blocks.words(feedBlocks),
            )
            val id = articleDao.insert(article)
            if (id < 0) return@mapNotNull null
            articleDao.upsert(ArticleContent(id, Blocks.encode(feedBlocks), null))
            search.index(id)
            Pending(id, item, feedBlocks, more)
        }
        feedDao.setRefreshed(feed.id, now, pending.size, response.etag, response.lastModified)
        // The feed's own order is newest first: the pages of the first ones are fetched, the tail of a long first fetch waits
        pending.filter { it.more }.forEachIndexed { index, article -> complete(feed, fullPage, article, textOnly, pageAllowed = index < MAX_PAGES) }
        return pending.size
    }

    /** The article's page and images, as the feed's settings ask; then its final state. */
    private suspend fun complete(feed: Feed, fullPage: Boolean, pending: Pending, textOnly: Boolean, pageAllowed: Boolean) {
        // Unsubscribed from meanwhile: nothing more is fetched for it
        if (feedDao.feed(feed.id)?.removedAt != null) return
        val item = pending.item
        val rest = fetchRest(feed, fullPage, pending.id, item.link, item.imageUrl, pending.feedBlocks, null, textOnly, pageAllowed)
        articleDao.setFetched(
            pending.id, rest.state, rest.lead,
            preview(item, pending.feedBlocks) ?: Blocks.text(rest.shown).take(PREVIEW_CHARS).takeIf { it.isNotEmpty() },
            Blocks.words(rest.shown),
        )
    }

    /**
     * Articles an earlier refresh left incomplete – no answer then, or a text-only refresh (SPEC
     * §10.5) – get their page and images now. Recent ones only, and a few at a time: what keeps
     * failing is not asked for for ever.
     */
    private suspend fun completeEarlier(feed: Feed, fullPage: Boolean, now: Long) {
        for (article in articleDao.incomplete(feed.id, now - RETRY_DAYS * DAY_MS, MAX_RETRIES)) {
            val content = articleDao.content(article.id)
            val rest = fetchRest(feed, fullPage, article.id, article.link, article.imageUrl, Blocks.decode(content?.feed), content?.page?.let { Blocks.decode(it) }?.takeIf { it.isNotEmpty() }, textOnly = false, pageAllowed = true)
            articleDao.setCompleted(article.id, rest.state, rest.lead, Blocks.words(rest.shown))
        }
    }

    /** What [fetchRest] left: the article's state, its lead image and the blocks the reader shows; [page] when those are the page's. */
    private class Rest(val state: Int, val lead: String?, val shown: List<Block>, val page: Boolean)

    /**
     * Fetches what an article still lacks – its page when the feed wants one and [storedPage] is
     * none, then the images of what will be shown – and stores the bodies.
     */
    private suspend fun fetchRest(feed: Feed, fullPage: Boolean, id: Long, link: String?, knownLead: String?, feedBlocks: List<Block>, storedPage: List<Block>?, textOnly: Boolean, pageAllowed: Boolean): Rest {
        var state = ArticleState.COMPLETE
        var page = storedPage
        var lead = knownLead
        if (fullPage && link != null && page == null) {
            if (!pageAllowed) {
                state = ArticleState.INCOMPLETE
            } else {
                try {
                    val response = http.get(link, HttpLimits.DOCUMENT)
                    val extracted = Extractor.extract(response.body, response.charset, response.url)
                    if (extracted != null) {
                        page = extracted.blocks
                        lead = lead ?: extracted.leadImage
                    } else {
                        state = ArticleState.NOT_EXTRACTED
                    }
                } catch (_: FetchFailure) {
                    state = ArticleState.INCOMPLETE
                }
            }
        }
        var shown = page ?: feedBlocks
        lead = lead ?: Blocks.images(shown).firstOrNull()?.url
        if (feed.storeImages) {
            val wanted = (listOfNotNull(lead) + Blocks.images(shown).map { it.url }).distinct().take(MAX_IMAGES)
            if (textOnly) {
                if (state == ArticleState.COMPLETE && wanted.any { !images.has(it) }) state = ArticleState.INCOMPLETE
            } else {
                val fetched = wanted.associateWith { images.fetchResult(it) }
                if (state == ArticleState.COMPLETE && fetched.values.any { it is ImageStore.Fetched.Failed }) state = ArticleState.INCOMPLETE
                shown = sized(shown, fetched)
            }
        }
        // The article went while its page and images were fetched (its feed was unsubscribed from): nothing is stored for it
        if (articleDao.article(id) == null) return Rest(state, lead, shown, page != null)
        articleDao.upsert(ArticleContent(id, feed = Blocks.encode(if (page == null) shown else feedBlocks), page = if (page == null) null else Blocks.encode(shown)))
        search.index(id)
        return Rest(state, lead, shown, page != null)
    }

    /**
     * One article's full page with its images, when the reader asks for it (SPEC §5.8, §5.10): for
     * a feed kept on its own content, or an article whose page is not on the phone. False when the
     * page could not be fetched or nothing could be cut out of it. The article's state follows for a
     * feed set to full pages; a feed kept on its own content was never incomplete for lacking one.
     */
    suspend fun fetchPage(article: Article): Boolean = withContext(Dispatchers.IO) {
        try {
            fetchPageOf(article)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The phone's storage is full, a file can't be written: not fetched, as the reader then says
            false
        }
    }

    private suspend fun fetchPageOf(article: Article): Boolean {
        if (article.link == null) return false
        val feed = feedDao.feed(article.feedId) ?: return false
        val content = articleDao.content(article.id)
        val rest = fetchRest(feed, fullPage = true, article.id, article.link, article.imageUrl, Blocks.decode(content?.feed), null, textOnly = false, pageAllowed = true)
        if (feed.fullPage) {
            articleDao.setCompleted(article.id, rest.state, rest.lead, Blocks.words(rest.shown))
        } else if (rest.page) {
            articleDao.setCompleted(article.id, article.state, rest.lead, Blocks.words(rest.shown))
        }
        return rest.page
    }

    /**
     * The blocks after their images were fetched: a stored image carries its size, so the reader
     * reserves its space before drawing it; what turned out not to be a picture is dropped rather
     * than left as a placeholder no fetch would ever fill.
     */
    private fun sized(blocks: List<Block>, fetched: Map<String, ImageStore.Fetched>): List<Block> =
        Blocks.mapImages(Blocks.withoutImages(blocks, fetched.filterValues { it is ImageStore.Fetched.NotAPicture }.keys)) { image ->
            (fetched[image.url] as? ImageStore.Fetched.Stored)?.let { image.copy(width = it.width, height = it.height) } ?: image
        }

    /** The start of the article's text for the row: the feed's teaser, else the start of its content (SPEC §4.10). */
    private fun preview(item: ParsedItem, feedBlocks: List<Block>): String? {
        val teaser = item.summaryHtml?.let { Blocks.text(HtmlBlocks.convert(it, item.link ?: "")) }?.takeIf { it.isNotEmpty() }
        return (teaser ?: Blocks.text(feedBlocks)).replace('\n', ' ').take(PREVIEW_CHARS).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val PARALLEL_FEEDS = 4
        /** Article pages fetched for one feed in one refresh; a first fetch of a long feed leaves the rest incomplete. */
        const val MAX_PAGES = 30
        const val MAX_IMAGES = 40
        /** Incomplete articles of one feed tried again in one refresh, and for how many days after they were first seen. */
        const val MAX_RETRIES = 20
        const val RETRY_DAYS = 3
        const val PREVIEW_CHARS = 400
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
