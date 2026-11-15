package sh.hnet.news.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlBlocksTest {
    private val base = "https://site.example/blog/post.html"
    private fun convert(html: String) = HtmlBlocks.convert(html, base)
    private fun p(text: String, vararg spans: Span) = Block.Paragraph(RichText(text, spans.toList()))

    @Test
    fun `paragraphs with inline styles and links`() {
        assertEquals(
            listOf(
                p("Plain and bold and a link.", Span(10, 14, Style.BOLD), Span(19, 25, Style.LINK, "https://site.example/x")),
                p("Second, with code().", Span(13, 19, Style.CODE)),
            ),
            convert("""<p>Plain and <strong>bold</strong> and <a href="/x">a link</a>.</p> <p>  Second,   with <code>code()</code>.  </p>"""),
        )
    }

    @Test
    fun `text without paragraphs, with line breaks`() {
        assertEquals(listOf(p("One line\nanother line"), p("After a div")), convert("One line<br>another   line<div>After a div</div>"))
        assertEquals(listOf(p("Just text & more")), convert("Just text &amp; more"))
        // Two breaks in a row part paragraphs
        assertEquals(listOf(p("First paragraph\nits second line"), p("Second paragraph")), convert("First paragraph<br>its second line<br /> <br />Second paragraph<br>"))
        // …but not inside an inline element, which would lose its style or its link, nor in a heading or a caption
        assertEquals(listOf(p("First\n\nsecond", Span(0, 13, Style.ITALIC))), convert("<p><em>First<br><br>second</em></p>"))
        assertEquals(listOf(Block.Heading(1, RichText("Title\n\nSubtitle"))), convert("<h2>Title<br><br>Subtitle</h2>"))
        assertEquals(
            listOf(Block.Image("https://site.example/a.jpg", caption = RichText("What it shows.\n\nPhoto: Someone"))),
            convert("""<figure><img src="/a.jpg"><figcaption>What it shows.<br><br>Photo: Someone</figcaption></figure>"""),
        )
        assertTrue(convert("  <p> </p><div><br></div>").isEmpty())
    }

    @Test
    fun `headings fall on three levels`() {
        assertEquals(
            listOf(Block.Heading(1, RichText("One")), Block.Heading(1, RichText("Two")), Block.Heading(2, RichText("Three")), Block.Heading(3, RichText("Six"))),
            convert("<h1>One</h1><h2>Two</h2><h3>Three</h3><h6>Six</h6>"),
        )
    }

    @Test
    fun `images, lazy ones, captions, pixels and vector drawings`() {
        assertEquals(
            listOf(
                Block.Image("https://site.example/a.jpg", alt = "A"),
                Block.Image("https://cdn.example/lazy.jpg"),
                Block.Image("https://site.example/blog/big.jpg"),
                Block.Image("https://site.example/fig.png", caption = RichText("A caption with emphasis", listOf(Span(15, 23, Style.ITALIC)))),
            ),
            convert(
                """
                <p><img src="/a.jpg" alt="A"><img src="/pixel.gif" width="1" height="1"><img src="/logo.svg?v=2" alt="Logo"></p>
                <img src="data:image/gif;base64,R0lGOD" data-src="https://cdn.example/lazy.jpg">
                <picture><source srcset="small.jpg 400w, big.jpg 1200w"><img></picture>
                <figure><a href="/full"><img src="/fig.png"></a><figcaption>A caption with <em>emphasis</em></figcaption></figure>
                """,
            ),
        )
    }

    @Test
    fun `a srcset whose addresses hold commas, behind a lazy loader's placeholder`() {
        assertEquals(
            listOf(Block.Image("https://cdn.example/i/?t=crop:10:10,resize:fill:800:600,enlarge:1")),
            convert(
                """
                <picture><source srcset="data:image/svg+xml,%3Csvg%20viewBox=&#039;0%200%20192%20120&#039;%3E%3C/svg%3E" data-srcset="https://cdn.example/i/?t=crop:10:10,resize:fill:400:300,enlarge:1 1x,https://cdn.example/i/?t=crop:10:10,resize:fill:800:600,enlarge:1 2x"></picture>
                <a href="/other"><img data-src="https://cdn.example/thumb.jpg" width="108" height="108"></a>
                """,
            ),
        )
    }

    @Test
    fun `a gallery's figures keep their own captions, a wrapped caption is not read twice`() {
        assertEquals(
            listOf(
                Block.Image("https://site.example/one.jpg", caption = RichText("One")),
                Block.Image("https://site.example/two.jpg", caption = RichText("Two")),
                Block.Paragraph(RichText("The gallery")),
                Block.Image("https://site.example/three.jpg", caption = RichText("Three")),
            ),
            convert(
                """
                <figure class="gallery">
                  <figure><img src="/one.jpg"><figcaption>One</figcaption></figure>
                  <figure><img src="/two.jpg"><figcaption>Two</figcaption></figure>
                  <figcaption>The gallery</figcaption>
                </figure>
                <figure><div><img src="/three.jpg"><figcaption>Three</figcaption></div></figure>
                """,
            ),
        )
    }

    @Test
    fun `captions written without a figure belong to their image, once`() {
        assertEquals(
            listOf(
                Block.Image("https://site.example/a.jpg", caption = RichText("What it shows\nPhoto: Someone")),
                p("The article goes on."),
                Block.Image("https://site.example/b.jpg", caption = RichText("A launch. Credit: The company")),
                p("Text between."),
                p("A caption class without an image before it is text."),
            ),
            convert(
                """
                <div class="article--image"><picture><img src="/a.jpg"></picture><p class="article--media--lead">What it shows</p><p class="image--author">Photo: Someone</p></div>
                <p>The article goes on.</p>
                <div class="lightbox"><a href="/b-full.jpg"><img src="/b.jpg"></a><div class="pswp-caption-content"><div class="caption">A launch. <span class="caption-credit">Credit: The company</span></div></div></div>
                <div class="caption wide">A launch. Credit: The company</div>
                <p>Text between.</p>
                <p class="caption">A caption class without an image before it is text.</p>
                """,
            ),
        )
    }

    @Test
    fun `quotes, lists, code, tables and rules`() {
        val blocks = convert(
            """
            <blockquote><p>Quoted.</p><p>Twice.</p></blockquote>
            <ol start="3"><li>Three</li><li>Four<ul><li>Nested</li></ul></li></ol>
            <pre><code>fun main() {
                println("hi")
            }</code></pre>
            <hr><hr>
            <table><tr><th>Name</th><th>Value</th></tr><tr><td>a</td><td><b>1</b></td></tr></table>
            <hr>
            """.trimIndent(),
        )
        assertEquals(Block.Quote(listOf(p("Quoted."), p("Twice."))), blocks[0])
        assertEquals(
            Block.ItemList(true, 3, listOf(listOf(p("Three")), listOf(p("Four"), Block.ItemList(false, 1, listOf(listOf(p("Nested"))))))),
            blocks[1],
        )
        assertEquals(Block.Code("fun main() {\n    println(\"hi\")\n}"), blocks[2])
        assertEquals(Block.Rule, blocks[3])
        assertEquals(
            Block.Table(listOf(listOf(Cell(RichText("Name"), true), Cell(RichText("Value"), true)), listOf(Cell(RichText("a")), Cell(RichText("1", listOf(Span(0, 1, Style.BOLD))))))),
            blocks[4],
        )
        // Rules in a row are one; none at the end
        assertEquals(5, blocks.size)
    }

    @Test
    fun `what the app does not show becomes a placeholder, what is not content goes`() {
        assertEquals(
            listOf(
                p("Before"),
                Block.Unsupported(Block.Kind.VIDEO, "https://www.youtube.com/embed/abc"),
                Block.Unsupported(Block.Kind.SOCIAL, "https://twitter.com/a/status/1"),
                Block.Unsupported(Block.Kind.AUDIO, "https://site.example/a.mp3"),
                p("After"),
            ),
            convert(
                """
                <p>Before</p><script>alert(1)</script><style>p{}</style>
                <iframe src="https://www.youtube.com/embed/abc"></iframe>
                <blockquote class="twitter-tweet"><p>Tweet text</p><a href="https://twitter.com/a/status/1">date</a></blockquote>
                <audio controls><source src="/a.mp3"></audio>
                <form><input value="x"><button>Send</button></form>
                <p>After</p>
                """,
            ),
        )
    }

    @Test
    fun `blocks survive their json`() {
        val blocks = convert("""<h2>T</h2><p>a <b>b</b></p><ul><li>x</li></ul><img src="/i.png"><hr><p>end</p>""")
        assertEquals(blocks, Blocks.decode(Blocks.encode(blocks)))
        assertEquals("T a b x end", Blocks.text(blocks))
        assertEquals(5, Blocks.words(blocks))
        assertEquals(listOf("https://site.example/i.png"), Blocks.images(blocks).map { it.url })
        assertTrue(Blocks.decode("not json").isEmpty())
    }
}
