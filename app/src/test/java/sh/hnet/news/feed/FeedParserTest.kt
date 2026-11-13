package sh.hnet.news.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedParserTest {
    private val url = "https://example.org/feeds/main.xml"

    /** The shape of a Polish newspaper's RSS 2.0 feed: everything in CDATA, the link too; an image enclosure; a bare name as author; elements of its own. */
    private val newspaper = """
        <?xml version="1.0" encoding="utf-8" ?>
        <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
        <channel>
        <title><![CDATA[www.example.org]]></title>
        <link><![CDATA[https://www.example.org/]]></link>
        <description><![CDATA[Wiadomości]]></description>
        <language>pl-PL</language>
        <lastBuildDate>Fri, 13 Nov 2026 13:32:05 +0100</lastBuildDate>
        <atom:link href="https://www.example.org/rss_main" rel="self" type="application/rss+xml"></atom:link>
        <image><title><![CDATA[https://www.example.org]]></title>
        <link><![CDATA[https://www.example.org]]></link>
        <url>https://www.example.org/img/logo.png</url>
        </image>
        <item>
        <guid isPermaLink="true"><![CDATA[https://www.example.org/gospodarka/art1-zolw-i-zajac]]></guid>
        <mainProfile><![CDATA[Gospodarka]]></mainProfile>
        <title><![CDATA[Żółw wyprzedza zająca. Nowe dane „z trasy”]]></title>
        <link><![CDATA[https://www.example.org/gospodarka/art1-zolw-i-zajac]]></link>
        <description><![CDATA[Zając prowadził przez większość wyścigu – ale tylko do czasu.]]></description>
        <category>Gospodarka</category>
        <pubDate>Fri, 13 Nov 2026 13:29:48 +0100</pubDate>
        <enclosure length="0" type="image/jpeg" url="https://img.example.org/image/1/?t=crop:5464:2389:nowe:0:626,resize:fill:600:300,enlarge:1"></enclosure>
        <author>Jan Kowalski</author>
        <redirectUrl></redirectUrl>
        <pay_status>Preview</pay_status>
        </item>
        <item>
        <guid isPermaLink="true"><![CDATA[https://www.example.org/prawo/art2-drugi]]></guid>
        <title><![CDATA[Drugi artykuł]]></title>
        <link><![CDATA[https://www.example.org/prawo/art2-drugi]]></link>
        <description><![CDATA[Krótka zajawka.]]></description>
        <pubDate>Fri, 13 Nov 2026 13:27:20 +0100</pubDate>
        </item>
        </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `rss 2_0 with everything in cdata`() {
        val feed = FeedParser.parse(newspaper, url)
        assertEquals(FeedFormat.RSS, feed.format)
        assertEquals("www.example.org", feed.title)
        assertEquals("https://www.example.org/", feed.siteUrl)
        assertEquals("https://www.example.org/img/logo.png", feed.iconUrl)
        assertEquals("pl-PL", feed.language)
        assertEquals(2, feed.items.size)
        val item = feed.items[0]
        assertEquals("https://www.example.org/gospodarka/art1-zolw-i-zajac", item.id)
        assertEquals("Żółw wyprzedza zająca. Nowe dane „z trasy”", item.title)
        assertEquals("https://www.example.org/gospodarka/art1-zolw-i-zajac", item.link)
        assertEquals(Instant.parse("2026-11-13T12:29:48Z"), item.published)
        assertEquals("Jan Kowalski", item.author)
        assertEquals("Zając prowadził przez większość wyścigu – ale tylko do czasu.", item.summaryHtml)
        assertNull(item.contentHtml)
        assertEquals("https://img.example.org/image/1/?t=crop:5464:2389:nowe:0:626,resize:fill:600:300,enlarge:1", item.imageUrl)
        assertEquals(listOf("Gospodarka"), item.categories)
        assertNull(feed.items[1].author)
        assertNull(feed.items[1].imageUrl)
    }

    @Test
    fun `rss 2_0 with content encoded, dublin core and media`() {
        val feed = FeedParser.parse(
            """
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>Blog &amp; notes</title>
                <link>https://blog.example.org</link>
                <item>
                  <title>Tom &amp;amp; Jerry: a &lt;em&gt;review&lt;/em&gt;</title>
                  <link>/2026/11/tom-and-jerry</link>
                  <guid isPermaLink="false">post-4711</guid>
                  <dc:creator><![CDATA[Ann Author]]></dc:creator>
                  <dc:date>2026-11-13T08:00:00+01:00</dc:date>
                  <description>&lt;p&gt;A teaser.&lt;/p&gt;</description>
                  <content:encoded><![CDATA[<p>The whole text with <a href="/x">a link</a>.</p>]]></content:encoded>
                  <media:content url="https://blog.example.org/a.jpg" medium="image"><media:title>Not the title</media:title></media:content>
                  <category>Film</category><category>Film</category><dc:subject>Animation</dc:subject>
                </item>
              </channel>
            </rss>
            """.trimIndent(), url,
        )
        assertEquals("Blog & notes", feed.title)
        val item = feed.items.single()
        assertEquals("post-4711", item.id)
        assertEquals("Tom & Jerry: a review", item.title)
        // Relative to the feed's own address
        assertEquals("https://example.org/2026/11/tom-and-jerry", item.link)
        assertEquals("Ann Author", item.author)
        assertEquals(Instant.parse("2026-11-13T07:00:00Z"), item.published)
        assertEquals("<p>A teaser.</p>", item.summaryHtml)
        assertEquals("""<p>The whole text with <a href="/x">a link</a>.</p>""", item.contentHtml)
        assertEquals("https://blog.example.org/a.jpg", item.imageUrl)
        assertEquals(listOf("Film", "Animation"), item.categories)
    }

    @Test
    fun `rss 0_91 without guids or dates`() {
        val feed = FeedParser.parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://my.netscape.com/publish/formats/rss-0.91.dtd">
            <rss version="0.91">
              <channel>
                <title>Old site</title>
                <link>http://old.example.org/</link>
                <description>News from 2001</description>
                <language>en-us</language>
                <item><title>First</title><link>http://old.example.org/1.html</link><description>One.</description></item>
                <item><title>Second</title><link>http://old.example.org/2.html</link></item>
              </channel>
            </rss>
            """.trimIndent(), url,
        )
        assertEquals("Old site", feed.title)
        assertEquals(listOf("http://old.example.org/1.html", "http://old.example.org/2.html"), feed.items.map { it.id })
        assertNull(feed.items[0].published)
        assertEquals("One.", feed.items[0].summaryHtml)
        assertNull(feed.items[1].summaryHtml)
    }

    @Test
    fun `rss 1_0 rdf with items beside the channel`() {
        val feed = FeedParser.parse(
            """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="http://rdf.example.org/">
                <title>RDF site</title>
                <link>http://rdf.example.org/</link>
                <description>Stuff that matters</description>
                <items><rdf:Seq><rdf:li rdf:resource="http://rdf.example.org/story/1"/></rdf:Seq></items>
              </channel>
              <image rdf:about="http://rdf.example.org/logo.png"><title>RDF site</title><url>http://rdf.example.org/logo.png</url><link>http://rdf.example.org/</link></image>
              <item rdf:about="http://rdf.example.org/story/1">
                <title>A story</title>
                <link>http://rdf.example.org/story/1?from=rss</link>
                <description>Somebody writes: &lt;i&gt;hello&lt;/i&gt;</description>
                <dc:creator>msmash</dc:creator>
                <dc:date>2026-11-13T12:00:00+00:00</dc:date>
                <dc:subject>technology</dc:subject>
              </item>
            </rdf:RDF>
            """.trimIndent(), url,
        )
        assertEquals(FeedFormat.RDF, feed.format)
        assertEquals("RDF site", feed.title)
        assertEquals("http://rdf.example.org/logo.png", feed.iconUrl)
        val item = feed.items.single()
        assertEquals("http://rdf.example.org/story/1", item.id)
        assertEquals("http://rdf.example.org/story/1?from=rss", item.link)
        assertEquals("msmash", item.author)
        assertEquals(Instant.parse("2026-11-13T12:00:00Z"), item.published)
        assertEquals("Somebody writes: <i>hello</i>", item.summaryHtml)
        assertEquals(listOf("technology"), item.categories)
    }

    @Test
    fun `atom 1_0 with its text types`() {
        val feed = FeedParser.parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en" xml:base="https://atom.example.org/blog/">
              <title type="html">Atom &amp;amp; friends</title>
              <subtitle>A subtitle</subtitle>
              <link rel="self" href="https://atom.example.org/feed.atom"/>
              <link href="https://atom.example.org/"/>
              <icon>/favicon.ico</icon>
              <author><name>Feed Author</name><email>a@example.org</email></author>
              <entry>
                <id>tag:atom.example.org,2026:1</id>
                <title>1 &lt; 2</title>
                <link rel="replies" href="https://atom.example.org/1/comments"/>
                <link rel="alternate" type="text/html" href="one.html"/>
                <published>2026-11-12T10:00:00Z</published>
                <updated>2026-11-13T10:00:00Z</updated>
                <summary>Plain &amp; simple</summary>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Marked <b>up</b>.</p></div></content>
                <category term="notes" label="Notes"/>
              </entry>
              <entry>
                <id>tag:atom.example.org,2026:2</id>
                <title type="text">Second</title>
                <link href="https://atom.example.org/two.html"/>
                <updated>2026-11-11T09:30:00.123+02:00</updated>
                <author><name>Guest</name></author>
                <content type="html">&lt;p&gt;Escaped markup.&lt;/p&gt;</content>
              </entry>
            </feed>
            """.trimIndent(), url,
        )
        assertEquals(FeedFormat.ATOM, feed.format)
        assertEquals("Atom & friends", feed.title)
        assertEquals("A subtitle", feed.description)
        assertEquals("https://atom.example.org/", feed.siteUrl)
        assertEquals("https://atom.example.org/favicon.ico", feed.iconUrl)
        assertEquals("en", feed.language)
        val (first, second) = feed.items
        assertEquals("tag:atom.example.org,2026:1", first.id)
        assertEquals("1 < 2", first.title)
        assertEquals("https://atom.example.org/blog/one.html", first.link)
        assertEquals(Instant.parse("2026-11-12T10:00:00Z"), first.published)
        assertEquals("Feed Author", first.author)
        assertEquals("Plain &amp; simple", first.summaryHtml)
        assertEquals("<p>Marked <b>up</b>.</p>", first.contentHtml)
        assertEquals(listOf("Notes"), first.categories)
        assertEquals(Instant.parse("2026-11-11T07:30:00Z"), second.published)
        assertEquals("Guest", second.author)
        assertEquals("<p>Escaped markup.</p>", second.contentHtml)
    }

    @Test
    fun `atom 0_3`() {
        val feed = FeedParser.parse(
            """
            <feed version="0.3" xmlns="http://purl.org/atom/ns#">
              <title>Old Atom</title>
              <tagline>From 2004</tagline>
              <link rel="alternate" type="text/html" href="http://old.example.org/"/>
              <modified>2004-06-01T12:00:00Z</modified>
              <entry>
                <title>An entry</title>
                <link rel="alternate" type="text/html" href="http://old.example.org/entry"/>
                <id>http://old.example.org/entry</id>
                <issued>2004-06-01T08:00:00-04:00</issued>
                <modified>2004-06-01T12:00:00Z</modified>
                <content type="text/html" mode="escaped">&lt;p&gt;Hello&lt;/p&gt;</content>
              </entry>
            </feed>
            """.trimIndent(), url,
        )
        assertEquals("From 2004", feed.description)
        val item = feed.items.single()
        assertEquals(Instant.parse("2004-06-01T12:00:00Z"), item.published)
        assertEquals("<p>Hello</p>", item.contentHtml)
    }

    @Test
    fun `a video feed's media group gives the image and the summary`() {
        val feed = FeedParser.parse(
            """
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
              <title>Channel</title>
              <entry>
                <id>yt:video:abc</id>
                <title>A video</title>
                <link rel="alternate" href="https://video.example.org/watch?v=abc"/>
                <published>2026-11-10T10:00:00+00:00</published>
                <media:group>
                  <media:title>A video</media:title>
                  <media:content url="https://video.example.org/v/abc" type="application/x-shockwave-flash"/>
                  <media:thumbnail url="https://img.example.org/abc.jpg" width="480" height="360"/>
                  <media:description>What it is about</media:description>
                </media:group>
              </entry>
            </feed>
            """.trimIndent(), url,
        )
        val item = feed.items.single()
        assertEquals("A video", item.title)
        assertEquals("https://img.example.org/abc.jpg", item.imageUrl)
        assertEquals("What it is about", item.summaryHtml)
    }

    @Test
    fun `the document's own encoding wins over the server's`() {
        val text = """<?xml version="1.0" encoding="ISO-8859-2"?><rss version="2.0"><channel><title>Zażółć gęślą jaźń</title><item><title>Łódź</title><link>http://pl.example.org/1</link></item></channel></rss>"""
        val feed = FeedParser.parse(text.toByteArray(charset("ISO-8859-2")), "utf-8", url)
        assertEquals("Zażółć gęślą jaźń", feed.title)
        assertEquals("Łódź", feed.items.single().title)
        // No declaration: the server's word counts
        val bare = """<rss version="2.0"><channel><title>Zażółć</title></channel></rss>"""
        assertEquals("Zażółć", FeedParser.parse(bare.toByteArray(charset("windows-1250")), "windows-1250", url).title)
        // Neither: UTF-8; a byte order mark is not content
        assertEquals("Zażółć", FeedParser.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bare.toByteArray(), null, url).title)
    }

    @Test
    fun `careless xml is still read`() {
        val feed = FeedParser.parse(
            """

            <!-- generated by a script -->
            <RSS version="2.0">
              <Channel>
                <Title>Sloppy & proud</Title>
                <item>
                  <title>Caf&eacute; &nbsp;au lait</title>
                  <link> http://sloppy.example.org/a b.html
                  </link>
                  <pubDate>13 Nov 26 9:05 CET</pubDate>
                  <author>webmaster@sloppy.example.org (The Webmaster)</author>
                  <description>Unclosed <br> tags & bare ampersands
                </item>
                <item>
                  <title>Same link, listed twice</title>
                  <link>http://sloppy.example.org/b.html</link>
                </item>
                <item>
                  <title>Same link, listed twice</title>
                  <link>http://sloppy.example.org/b.html</link>
                </item>
                <item><description>Nothing to tell it by</description></item>
              </Channel>
            </RSS>
            """.trimIndent(), url,
        )
        assertEquals("Sloppy & proud", feed.title)
        assertEquals(2, feed.items.size)
        val item = feed.items[0]
        assertEquals("Café au lait", item.title)
        assertEquals("http://sloppy.example.org/a%20b.html", item.link)
        assertEquals(Instant.parse("2026-11-13T08:05:00Z"), item.published)
        assertEquals("The Webmaster", item.author)
    }

    @Test
    fun `authors in their usual forms`() {
        fun author(xml: String) = FeedParser.parse("<rss><channel><item><title>t</title>$xml</item></channel></rss>", url).items.single().author
        assertEquals("Jan Kowalski", author("<author>Jan Kowalski</author>"))
        assertEquals("Jan Kowalski", author("<author>jan@example.org (Jan Kowalski)</author>"))
        assertEquals("Jan Kowalski", author("<author>\"Jan Kowalski\" &lt;jan@example.org&gt;</author>"))
        assertNull(author("<author>jan@example.org</author>"))
        assertNull(author("<author></author>"))
    }

    @Test
    fun `what is not a feed is refused`() {
        assertThrows(NotAFeedException::class.java) { FeedParser.parse("<!DOCTYPE html><html><head><title>Portal</title></head><body><p>Hi</p></body></html>", url) }
        assertThrows(NotAFeedException::class.java) { FeedParser.parse("<opml version=\"2.0\"><body/></opml>", url) }
        assertThrows(NotAFeedException::class.java) { FeedParser.parse("Not found.", url) }
        assertThrows(NotAFeedException::class.java) { FeedParser.parse("", url) }
        // An empty feed is a feed
        assertTrue(FeedParser.parse("<rss version=\"2.0\"><channel><title>Empty</title></channel></rss>", url).items.isEmpty())
    }
}
