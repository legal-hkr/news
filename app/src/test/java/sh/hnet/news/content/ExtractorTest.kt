package sh.hnet.news.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorTest {
    private val url = "https://site.example/news/article-1"
    private val sentence = "The committee met on Tuesday, reviewed the draft, and agreed, after a long discussion, to publish it next month. "

    private fun page(article: String, head: String = "") = """
        <!DOCTYPE html><html><head><title>Article – Site</title>$head</head><body>
        <header class="masthead"><a href="/">Site</a><nav><ul><li><a href="/a">World</a></li><li><a href="/b">Business</a></li></ul></nav></header>
        <div class="layout">
          <div class="main-column">$article</div>
          <aside class="sidebar"><h3>Most read</h3><ul><li><a href="/1">One long headline about something that happened today</a></li><li><a href="/2">Another long headline about something else entirely</a></li></ul></aside>
        </div>
        <div class="comments"><p>${sentence.repeat(3)}</p><p>${sentence.repeat(3)}</p></div>
        <footer><p>Copyright, terms, privacy, and a long legal sentence that goes on, and on, and on.</p></footer>
        <script>var tracking = "${sentence}";</script>
        </body></html>
    """.trimIndent()

    @Test
    fun `the article is found among the page's furniture`() {
        val extracted = Extractor.extract(
            page(
                """
                <article class="post">
                  <h1>Article</h1>
                  <div class="share-buttons"><a href="#">Share</a> <a href="#">Tweet</a></div>
                  <div class="article-body">
                    <p>First. ${sentence.repeat(2)}</p>
                    <figure><img src="/img/lead.jpg"><figcaption>The committee</figcaption></figure>
                    <p>Second. ${sentence.repeat(2)}</p>
                    <h2>Background</h2>
                    <p>Third. ${sentence.repeat(2)}</p>
                    <div class="related-articles"><ul><li><a href="/r1">Related story one, with a long title</a></li><li><a href="/r2">Related story two, with a long title</a></li></ul></div>
                    <p>Last. ${sentence}</p>
                  </div>
                </article>
                """,
                head = """<meta property="og:image" content="https://cdn.example/og.jpg">""",
            ),
            url,
        )
        assertNotNull(extracted)
        val text = Blocks.text(extracted!!.blocks)
        assertTrue(text.startsWith("First."))
        assertTrue("Last." in text)
        assertTrue("Background" in text)
        assertFalse("Most read" in text)
        assertFalse("Related story" in text)
        assertFalse("Share" in text)
        assertFalse("Copyright" in text)
        assertEquals(listOf("https://site.example/img/lead.jpg"), Blocks.images(extracted.blocks).map { it.url })
        assertEquals("https://cdn.example/og.jpg", extracted.leadImage)
        assertTrue(extracted.blocks.any { it is Block.Heading })
    }

    @Test
    fun `an advertisement inside the article goes with its images`() {
        val extracted = Extractor.extract(
            page(
                """
                <article class="post">
                  <div class="article-body">
                    <p>First. ${sentence.repeat(3)}</p>
                    <div id="commercialBreakFallback" class="fallback"><a href="https://ads.example/offer" rel="nofollow"><picture><img data-src="https://cdn.example/offer.png" width="320" height="370"></picture></a></div>
                    <p>Last. ${sentence.repeat(3)}</p>
                  </div>
                </article>
                """,
            ),
            url,
        )
        assertNotNull(extracted)
        assertTrue(Blocks.images(extracted!!.blocks).isEmpty())
    }

    @Test
    fun `teasers of other pages, advertising labels and the line of dates go, the article's own picture stays`() {
        val extracted = Extractor.extract(
            page(
                """
                <article class="post">
                  <div class="article-body">
                    <p class="article--meta">Updated: 13.11.2026 19:48</p>
                    <p>First. ${sentence.repeat(3)}</p>
                    <div class="contentBodySectionAdvert"><div class="label" style="opacity: 0">Advertisement</div><div class="slot"></div></div>
                    <div class="content--excerpt">
                      <p class="subtitle">Read more</p>
                      <a href="/other-story"><picture><img src="/img/teaser.jpg" width="384" height="240"></picture></a>
                      <a href="/other-story">Another story with a headline of its own</a>
                    </div>
                    <figure><a href="/img/full.jpg"><img src="/img/own.jpg"></a><figcaption>The committee, by <a href="/people/someone">a photographer of ours</a></figcaption></figure>
                    <p>Last. ${sentence.repeat(3)}</p>
                  </div>
                </article>
                """,
            ),
            url,
        )
        assertNotNull(extracted)
        val text = Blocks.text(extracted!!.blocks)
        assertFalse("Updated" in text)
        assertFalse("Advertisement" in text)
        assertFalse("Read more" in text)
        assertFalse("Another story" in text)
        assertEquals(listOf("https://site.example/img/own.jpg"), Blocks.images(extracted.blocks).map { it.url })
    }

    @Test
    fun `a page without article markup is cut by its paragraphs`() {
        val extracted = Extractor.extract(page("""<div id="tresc"><p>Pierwszy. ${sentence.repeat(2)}</p><p>Drugi. ${sentence.repeat(2)}</p><p>Trzeci. ${sentence.repeat(2)}</p></div>"""), url)
        val text = Blocks.text(extracted!!.blocks)
        assertTrue(text.startsWith("Pierwszy."))
        assertFalse("Most read" in text)
    }

    @Test
    fun `a page built by scripts gives its structured data`() {
        val body = "Opening paragraph. ${sentence.repeat(2)}\n\nClosing paragraph. ${sentence.repeat(2)}".replace("\n", "\\n")
        val extracted = Extractor.extract(
            """<html><head><script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"NewsArticle","headline":"H","articleBody":"$body"}]}</script></head><body><div id="root"></div></body></html>""",
            url,
        )
        assertEquals(2, extracted!!.blocks.size)
        assertTrue((extracted.blocks[0] as Block.Paragraph).text.text.startsWith("Opening paragraph."))
    }

    @Test
    fun `too little text is a failure`() {
        assertNull(Extractor.extract(page("""<article><h1>Article</h1><p>Subscribe to read the rest of this story.</p></article>"""), url))
        assertNull(Extractor.extract("<html><body><div id=\"app\"></div></body></html>", url))
        assertNull(Extractor.extract("", url))
    }
}
