package sh.hnet.news.feed

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import sh.hnet.news.net.FetchFailure
import sh.hnet.news.net.HttpFetcher
import sh.hnet.news.net.HttpLimits
import java.io.ByteArrayInputStream
import java.net.URI

/** A feed found at or behind an address the user gave. [feed] is there when its document was already fetched. */
data class FoundFeed(val url: String, val title: String?, val feed: ParsedFeed? = null)

/** The address answered, but no feed is there or linked from there. */
class NoFeedFoundException : Exception("No feed found")

/**
 * From whatever the user has – a feed's address or just a site's ("example.org") – to the feeds
 * there (SPEC §7.1): the address itself when it is a feed; else the feeds its page links to in its
 * head; else the usual places on that site.
 */
class FeedFinder(private val http: HttpFetcher) {
    /**
     * One feed (with its document) or several to choose from (without).
     * @throws FetchFailure When no address could be fetched
     * @throws NoFeedFoundException When the site answered and has no feed we can find
     */
    suspend fun find(input: String): List<FoundFeed> {
        var failure: FetchFailure? = null
        for (address in Addresses.candidates(input)) {
            val response = try {
                http.get(address, HttpLimits.DOCUMENT)
            } catch (e: FetchFailure.NoNetwork) {
                throw e
            } catch (e: FetchFailure) {
                // The first candidate's failure is the one worth telling (https before http)
                failure = failure ?: e
                continue
            }
            try {
                val feed = FeedParser.parse(response.body, response.charset, response.url)
                return listOf(FoundFeed(response.url, feed.title, feed))
            } catch (_: NotAFeedException) {
                // A page: look at what it links to
            }
            val linked = FeedLinks.find(response.body, response.charset, response.url)
            if (linked.size == 1) return listOf(load(linked.single().url))
            if (linked.isNotEmpty()) return linked
            for (guess in Addresses.usualPlaces(response.url)) {
                try {
                    return listOf(load(guess))
                } catch (e: FetchFailure.NoNetwork) {
                    throw e
                } catch (_: FetchFailure) {
                } catch (_: NotAFeedException) {
                }
            }
            throw NoFeedFoundException()
        }
        throw failure ?: NoFeedFoundException()
    }

    /**
     * The feed at exactly this address.
     * @throws FetchFailure
     * @throws NotAFeedException
     */
    suspend fun load(url: String): FoundFeed {
        val response = http.get(url, HttpLimits.DOCUMENT)
        val feed = FeedParser.parse(response.body, response.charset, response.url)
        return FoundFeed(response.url, feed.title, feed)
    }
}

object Addresses {
    /** The addresses to try for what the user typed: as written when it has a scheme, else HTTPS first and plain HTTP after it. */
    fun candidates(input: String): List<String> {
        var text = input.trim().replace(" ", "%20")
        if (text.isEmpty()) return emptyList()
        // An old browser scheme for "a feed over http"
        if (text.startsWith("feed://", ignoreCase = true)) text = "http://" + text.substring(7)
        else if (text.startsWith("feed:", ignoreCase = true)) text = text.substring(5)
        return when {
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> listOf(text)
            "://" in text -> emptyList()
            else -> listOf("https://$text", "http://$text")
        }
    }

    /** Where feeds usually live on a site, for pages that link to none. */
    fun usualPlaces(pageUrl: String): List<String> {
        val root = try {
            val uri = URI(pageUrl)
            "${uri.scheme}://${uri.rawAuthority}"
        } catch (_: Exception) {
            return emptyList()
        }
        return listOf("/feed", "/rss", "/rss.xml", "/atom.xml", "/feed.xml", "/index.xml").map { root + it }
    }
}

/** The feeds a web page names in its head: `<link rel="alternate" type="application/rss+xml" href="…">` and its Atom and RDF kin. */
object FeedLinks {
    private val types = setOf("application/rss+xml", "application/atom+xml", "application/rdf+xml", "application/x.atom+xml", "application/x-atom+xml", "text/xml", "application/xml")

    fun find(body: ByteArray, charset: String?, url: String): List<FoundFeed> = find(Jsoup.parse(ByteArrayInputStream(body), charset?.takeIf { supported(it) }, url))

    fun find(html: String, url: String): List<FoundFeed> = find(Jsoup.parse(html, url))

    private fun find(document: Document): List<FoundFeed> =
        document.select("link[href]").filter { link ->
            link.attr("rel").lowercase().split(Regex("\\s+")).contains("alternate") && link.attr("type").trim().lowercase() in types
        }.mapNotNull { link ->
            val address = link.absUrl("href").takeIf { it.startsWith("http", ignoreCase = true) } ?: return@mapNotNull null
            FoundFeed(address, FeedParser.collapse(link.attr("title")))
        }.distinctBy { Opml.key(it.url) }

    private fun supported(charset: String): Boolean = try {
        java.nio.charset.Charset.isSupported(charset)
    } catch (_: IllegalArgumentException) {
        false
    }
}

/** What the app recommends as a new feed's content source (SPEC §7.2). */
enum class ContentAdvice {
    /** The feed sends only teasers: Full page recommended. */
    FULL_PAGE,
    /** The feed already sends the full text. */
    FEED,
    /** Can't tell (no articles, or something in between): Feed's content, without a note. */
    UNKNOWN;

    companion object {
        private const val TEASER = 500
        private const val FULL = 1500

        /** By the length of the text of the latest few articles; the middle one decides, so one long or short article doesn't. */
        fun of(feed: ParsedFeed): ContentAdvice {
            val lengths = feed.items.take(5).map { item -> Jsoup.parse(item.contentHtml ?: item.summaryHtml ?: "").text().length }.sorted()
            if (lengths.isEmpty()) return UNKNOWN
            val middle = lengths[lengths.size / 2]
            return when {
                middle < TEASER -> FULL_PAGE
                middle >= FULL -> FEED
                else -> UNKNOWN
            }
        }
    }
}
