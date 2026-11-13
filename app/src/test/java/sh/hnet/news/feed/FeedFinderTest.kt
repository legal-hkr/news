package sh.hnet.news.feed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.news.net.FetchFailure
import sh.hnet.news.net.HttpFetcher
import sh.hnet.news.net.HttpResponse
import sh.hnet.news.net.Validators
import sh.hnet.news.net.failureOf
import sh.hnet.news.net.parseContentType

class FeedFinderTest {
    private val rss = """<rss version="2.0"><channel><title>The feed</title><item><title>One</title><link>https://site.example/1</link></item></channel></rss>"""

    /** A web of addresses: a body, or a failure to throw; anything else is 404. Notes what was asked for. */
    private class Web(private val pages: Map<String, Any>) : HttpFetcher {
        val asked = mutableListOf<String>()

        override suspend fun get(url: String, maxBytes: Int, validators: Validators?): HttpResponse {
            asked += url
            return when (val page = pages[url]) {
                is String -> HttpResponse(url, null, null, page.toByteArray())
                is FetchFailure -> throw page
                else -> throw FetchFailure.NotFound(404)
            }
        }
    }

    private fun find(web: Web, input: String) = runBlocking { FeedFinder(web).find(input) }

    @Test
    fun `a feed's own address is the feed`() {
        val found = find(Web(mapOf("https://site.example/rss" to rss)), "https://site.example/rss").single()
        assertEquals("https://site.example/rss", found.url)
        assertEquals("The feed", found.title)
        assertNotNull(found.feed)
    }

    @Test
    fun `a bare site name is tried over https, then over http`() {
        val web = Web(mapOf("https://old.example" to FetchFailure.NoAnswer("refused"), "http://old.example" to rss))
        assertEquals("http://old.example", find(web, " old.example ").single().url)
        assertEquals(listOf("https://old.example", "http://old.example"), web.asked)
    }

    @Test
    fun `a page's one linked feed is loaded, several are offered`() {
        val one = """<html><head><link rel="alternate" type="application/rss+xml" title="Articles" href="/rss"></head></html>"""
        val found = find(Web(mapOf("https://site.example" to one, "https://site.example/rss" to rss)), "site.example").single()
        assertEquals("https://site.example/rss", found.url)
        assertNotNull(found.feed)

        val several = """
            <html><head>
              <link rel="stylesheet" href="/style.css">
              <link rel="alternate" type="application/rss+xml" title="Articles" href="/rss">
              <link rel="ALTERNATE" type="application/atom+xml" title="Comments" href="https://site.example/comments.atom">
              <link rel="alternate" type="application/rss+xml" title="Articles again" href="https://www.site.example/rss/">
              <link rel="alternate" hreflang="de" href="/de/">
            </head></html>
        """.trimIndent()
        val offered = find(Web(mapOf("https://site.example" to several)), "site.example")
        assertEquals(listOf("https://site.example/rss" to "Articles", "https://site.example/comments.atom" to "Comments"), offered.map { it.url to it.title })
        assertTrue(offered.all { it.feed == null })
    }

    @Test
    fun `a page without links is searched at the usual places`() {
        val web = Web(mapOf("https://site.example/blog/" to "<html><body>Hello</body></html>", "https://site.example/atom.xml" to rss))
        assertEquals("https://site.example/atom.xml", find(web, "https://site.example/blog/").single().url)
    }

    @Test
    fun `nothing found and failures are told apart`() {
        assertThrows(NoFeedFoundException::class.java) { find(Web(mapOf("https://site.example" to "<html></html>")), "site.example") }
        assertThrows(FetchFailure.NoNetwork::class.java) { find(Web(mapOf("https://site.example" to FetchFailure.NoNetwork())), "site.example") }
        // Of https and http, the first failure is told
        assertThrows(FetchFailure.Refused::class.java) { find(Web(mapOf("https://site.example" to FetchFailure.Refused(403))), "site.example") }
        assertThrows(FetchFailure.NotFound::class.java) { find(Web(emptyMap()), "https://site.example/gone.xml") }
        assertThrows(NoFeedFoundException::class.java) { find(Web(emptyMap()), "ftp://site.example/feed") }
    }

    @Test
    fun `addresses as people type them`() {
        assertEquals(listOf("https://example.org", "http://example.org"), Addresses.candidates("example.org"))
        assertEquals(listOf("http://example.org/feed"), Addresses.candidates("http://example.org/feed"))
        assertEquals(listOf("HTTPS://Example.org/"), Addresses.candidates("HTTPS://Example.org/"))
        assertEquals(listOf("http://example.org/rss"), Addresses.candidates("feed://example.org/rss"))
        assertEquals(listOf("https://example.org/rss"), Addresses.candidates("feed:https://example.org/rss"))
        assertTrue(Addresses.candidates("  ").isEmpty())
    }

    @Test
    fun `the content advice follows the middle article's length`() {
        fun feed(vararg lengths: Int) = ParsedFeed(FeedFormat.RSS, null, null, null, null, null, lengths.mapIndexed { index, length ->
            ParsedItem("$index", null, null, null, null, "<p>" + "a".repeat(length) + "</p>", null, null, emptyList())
        })
        assertEquals(ContentAdvice.FULL_PAGE, ContentAdvice.of(feed(200, 250, 9000)))
        assertEquals(ContentAdvice.FEED, ContentAdvice.of(feed(3000, 100, 4000)))
        assertEquals(ContentAdvice.UNKNOWN, ContentAdvice.of(feed(900, 800, 1000)))
        assertEquals(ContentAdvice.UNKNOWN, ContentAdvice.of(feed()))
    }

    @Test
    fun `a page's icons, the largest first, without vectors`() {
        val html = """
            <html><head>
              <link rel="icon" href="/favicon.svg" type="image/svg+xml">
              <link rel="shortcut icon" href="/favicon.ico">
              <link rel="icon" type="image/png" sizes="32x32" href="/icon-32.png">
              <link rel="apple-touch-icon" href="https://cdn.example/touch.png">
              <link rel="icon" sizes="192x192" href="/icon-192.png">
            </head></html>
        """.trimIndent()
        assertEquals(
            listOf("https://site.example/icon-192.png", "https://cdn.example/touch.png", "https://site.example/icon-32.png", "https://site.example/favicon.ico"),
            IconLinks.find(html, "https://site.example/"),
        )
    }

    @Test
    fun `statuses and content types`() {
        assertNull(failureOf(200))
        assertTrue(failureOf(404) is FetchFailure.NotFound)
        assertTrue(failureOf(403) is FetchFailure.Refused)
        assertTrue(failureOf(503) is FetchFailure.NoAnswer)
        assertEquals("text/html" to "ISO-8859-2", parseContentType("Text/HTML; charset=ISO-8859-2"))
        assertEquals("xml" to null, parseContentType("xml"))
        assertEquals(null to null, parseContentType(null))
    }
}
