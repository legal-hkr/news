package sh.hnet.news.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OpmlTest {
    @Test
    fun `feeds and folders are read, nested folders flattened`() {
        val feeds = Opml.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="1.0">
              <head><title>Subscriptions</title></head>
              <body>
                <outline text="Loose feed" type="rss" xmlUrl="https://loose.example.org/feed" htmlUrl="https://loose.example.org/"/>
                <outline text="Tech" title="Tech">
                  <outline type="rss" text="Kernel &amp; co" title="Kernel &amp; co" xmlUrl="https://kernel.example.org/rss.xml"/>
                  <outline text="Deeper">
                    <outline text="Nested" xmlurl="http://nested.example.org/atom.xml"/>
                  </outline>
                </outline>
                <outline text="Empty folder"/>
                <outline title="No type, no text" xmlUrl="feed://old.example.org/index.rdf"/>
                <outline xmlUrl="https://www.noname.example.org/feed/"/>
                <outline text="A link, not a feed" type="link" url="https://example.org/"/>
              </body>
            </opml>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                OpmlFeed("Loose feed", "https://loose.example.org/feed", "https://loose.example.org/", null),
                OpmlFeed("Kernel & co", "https://kernel.example.org/rss.xml", null, "Tech"),
                OpmlFeed("Nested", "http://nested.example.org/atom.xml", null, "Tech"),
                OpmlFeed("No type, no text", "http://old.example.org/index.rdf", null, null),
                OpmlFeed("noname.example.org", "https://www.noname.example.org/feed/", null, null),
            ),
            feeds,
        )
    }

    @Test
    fun `an address listed twice is one feed`() {
        val feeds = Opml.parse(
            """
            <opml version="2.0"><body>
              <outline text="A" xmlUrl="https://www.example.org/feed/"/>
              <outline text="Folder"><outline text="A again" xmlUrl="http://example.org/feed"/></outline>
              <outline text="B" xmlUrl="https://example.org/other"/>
            </body></opml>
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B"), feeds.map { it.title })
    }

    @Test
    fun `what is written is read back the same`() {
        val feeds = listOf(
            OpmlFeed("Law \"daily\" <news> & more", "https://law.example.org/rss?a=1&b=2", "https://law.example.org/", "Prawo & życie"),
            OpmlFeed("Loose", "https://loose.example.org/feed", null, null),
            OpmlFeed("Kernel", "https://kernel.example.org/rss.xml", null, "Tech"),
            OpmlFeed("Second law", "https://law2.example.org/rss", null, "Prawo & życie"),
        )
        val text = Opml.write(feeds, "News", Instant.parse("2026-11-13T12:00:00Z"))
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">"))
        assertTrue("<dateCreated>Fri, 13 Nov 2026 12:00:00 GMT</dateCreated>" in text)
        // Without a folder first, then folder by folder
        assertEquals(listOf(feeds[1], feeds[0], feeds[3], feeds[2]), Opml.parse(text))
        assertEquals(listOf(feeds[1], feeds[0], feeds[3], feeds[2]), Opml.parse(text.toByteArray()))
    }

    @Test
    fun `what is not opml is refused`() {
        assertThrows(NotOpmlException::class.java) { Opml.parse("<rss version=\"2.0\"><channel/></rss>") }
        assertThrows(NotOpmlException::class.java) { Opml.parse("<html><body>Hello</body></html>") }
        assertThrows(NotOpmlException::class.java) { Opml.parse("just text") }
        assertTrue(Opml.parse("<opml version=\"2.0\"><head/><body/></opml>").isEmpty())
    }
}
