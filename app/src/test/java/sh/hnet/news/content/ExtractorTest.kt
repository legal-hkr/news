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
    fun `an article set as lines between breaks wins over the author's note in a paragraph`() {
        val extracted = Extractor.extract(
            """
            <html><body>
            <article class="full">
              <h1>Article</h1>
              <div class="author">Written by Someone on 14 November 2026.</div>
              <div class="content">First. ${sentence.repeat(2)}<br /> <br />Second. ${sentence.repeat(2)}<br /> <br />Last. ${sentence}<br /></div>
            </article>
            <div id="about-author"><div class="text"><p>Someone is the principal author of this site, and founded it, and has written, tested, measured and published more than twenty thousand articles since. ${sentence}</p></div></div>
            </body></html>
            """.trimIndent(),
            url,
        )
        assertNotNull(extracted)
        val text = Blocks.text(extracted!!.blocks)
        assertTrue(text.startsWith("First."))
        assertTrue("Last." in text)
        assertFalse("principal author" in text)
        assertEquals(3, extracted.blocks.count { it is Block.Paragraph })
    }

    @Test
    fun `headings of an article set as lines stay, the section's name before the first paragraph goes`() {
        val lines = Extractor.extract(
            """
            <html><body>
            <article class="full">
              <h1>Article</h1>
              <div class="content"><h1>Part one</h1>First. ${sentence.repeat(2)}<br /> <br />Second. ${sentence.repeat(2)}<br /> <br />Last. ${sentence}<br /></div>
            </article>
            </body></html>
            """.trimIndent(),
            url,
        )
        assertNotNull(lines)
        assertTrue("Part one" in Blocks.text(lines!!.blocks))
        val paragraphs = Extractor.extract(
            """
            <html><body>
            <article class="post">
              <h1>Opinions</h1>
              <h1>The article's own title</h1>
              <p>First. ${sentence.repeat(3)}</p>
              <h1>Part two</h1>
              <p>Last. ${sentence.repeat(3)}</p>
            </article>
            </body></html>
            """.trimIndent(),
            url,
        )
        assertNotNull(paragraphs)
        val text = Blocks.text(paragraphs!!.blocks)
        assertFalse("Opinions" in text)
        assertFalse("own title" in text)
        assertTrue("Part two" in text)
    }

    @Test
    fun `utility class names are not read as what an element is, and what is hidden in print goes`() {
        val extracted = Extractor.extract(
            """
            <html><body>
            <div class="mx-auto flex print:pt-0 top-[104px]">
              <main class="w-full lg:pr-7">
                <div class="md:hidden"><p>First. ${sentence.repeat(3)}</p></div>
                <div class="pb-5 print:hidden"><p>Related. ${sentence.repeat(3)}</p></div>
                <div class="hidden md:block"><p>Wide screens only. ${sentence.repeat(3)}</p></div>
                <p>Last. ${sentence.repeat(3)}</p>
              </main>
            </div>
            </body></html>
            """.trimIndent(),
            url,
        )
        assertNotNull(extracted)
        val text = Blocks.text(extracted!!.blocks)
        assertTrue(text.startsWith("First."))
        assertTrue("Last." in text)
        assertFalse("Related." in text)
        assertFalse("Wide screens only." in text)
    }

    @Test
    fun `the article's title goes also when it is not the page's first heading`() {
        val extracted = Extractor.extract(
            """
            <html><body>
            <h1>The Section</h1>
            <article class="post">
              <h1>Someone: A column with a title</h1>
              <div class="article-body">
                <p>First. ${sentence.repeat(3)}</p>
                <h2>Inside</h2>
                <p>Last. ${sentence.repeat(3)}</p>
              </div>
            </article>
            </body></html>
            """.trimIndent(),
            url,
            title = "Someone: A column with a title",
        )
        assertNotNull(extracted)
        val headings = extracted!!.blocks.filterIsInstance<Block.Heading>().map { it.text.text }
        assertEquals(listOf("Inside"), headings)
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
