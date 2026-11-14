package sh.hnet.news.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream

/** What was cut out of an article's page. */
class Extracted(val blocks: List<Block>, val leadImage: String?)

/**
 * Finds the article in its web page ("Full page", SPEC §5 of the brief). Our own take on the
 * well-known approach of scoring containers by the text of their paragraphs: what is clearly not
 * the article goes first (navigation, forms, comment and sharing boxes), then every paragraph gives
 * points to its parent and grandparent, link-heavy containers lose theirs, and the best container
 * is the article. Pages built by scripts often carry the text as structured data instead, which is
 * the fallback. Failure is null: the feed's own content is always there to show.
 */
object Extractor {
    /** Less text than this is a teaser, a paywall notice or an error page, not an article. */
    private const val MIN_TEXT = 250
    /** A link's text from which it reads as a teaser's headline, not a photo's credit. */
    private const val TEASER_HEADLINE = 20

    private val negative = Regex("""comment|disqus|share|sharing|social|related|sidebar|promo|advert|commercial|adsense|ad-slot|adslot|banner|badge|newsletter|subscri|cookie|consent|popup|modal|breadcrumb|pagination|pager|widget|recommend|outbrain|taboola|sponsor|paywall|toolbar|masthead|menu|navbar|footer|byline|author-box|about-author|about-the-author|author-bio|author-info|author-card|tags|print|hidden|skip-link""", RegexOption.IGNORE_CASE)
    /** Names that say "not the article" even next to a word of [keep]: advertising ("contentBodyAdvert") and the line of dates and bylines ("article-meta"). */
    private val always = Regex("""advert|adsense|ad-?slot|ad-?wrapper|\bad--|commercial|outbrain|taboola|[-_]meta\b""", RegexOption.IGNORE_CASE)
    /** Inline styles of what the page itself does not show. */
    private val unseen = Regex("""display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0(\.0+)?\s*(;|$)""", RegexOption.IGNORE_CASE)
    private val keep = Regex("""article|body|content|entry|main|post|story|text|column""", RegexOption.IGNORE_CASE)
    private val positive = Regex("""article|body|content|entry|hentry|main|page|post|text|blog|story|lead""", RegexOption.IGNORE_CASE)
    private val protectedTags = setOf("html", "body", "article", "main")

    fun extract(body: ByteArray, charset: String?, url: String): Extracted? =
        extract(Jsoup.parse(ByteArrayInputStream(body), charset?.takeIf { supported(it) }, url))

    fun extract(html: String, url: String): Extracted? = extract(Jsoup.parse(html, url))

    private fun extract(document: Document): Extracted? {
        val lead = (document.selectFirst("meta[property=og:image]") ?: document.selectFirst("meta[name=twitter:image]"))?.absUrl("content")?.takeIf { it.startsWith("http", ignoreCase = true) }
        val structured = structuredBody(document)
        val title = document.selectFirst("h1")?.text()?.trim()
        document.select("script, style, noscript, template, nav, aside, footer, form, button, input, select, textarea, svg, dialog, [hidden], [aria-hidden=true]").remove()
        document.body().select("[style]").filter { unseen.containsMatchIn(it.attr("style")) }.forEach { it.remove() }
        document.body().allElements.filter { it.parent() != null && unlikely(it) }.forEach { it.remove() }

        val candidate = best(document)
        var blocks = candidate?.let { clean(it, title); HtmlBlocks.convert(it) }.orEmpty()
        if (Blocks.text(blocks).length < MIN_TEXT) {
            // Structured data: plain text, one paragraph a line
            blocks = structured?.split(Regex("""\n+"""))?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { Block.Paragraph(RichText(it)) }.orEmpty()
        }
        if (Blocks.text(blocks).length < MIN_TEXT) return null
        return Extracted(blocks, lead)
    }

    /**
     * What an element is called: its class names and its id. Utility classes say how a thing looks,
     * not what it is, and their words mislead: names with a variant or a bracket ("print:pt-0",
     * "md:hidden", "top-[104px]") are left out. A bare "hidden" stays: such an element is not shown
     * on a narrow screen, whatever a wider one does with it ("hidden md:block"), and a page that has
     * one usually carries the narrow screen's version beside it.
     */
    private fun label(element: Element): String = element.classNames().filter { ':' !in it && '[' !in it }.joinToString(" ") + " " + element.id()

    /** Containers that hold an article whatever their own name says (a "paywall wrapper" around the article's body). */
    private const val ARTICLE_INSIDE = "article, main, [itemprop=articleBody], [class*=articleBody], [class*=article-body], [class*=article--content], [class*=article__body], [class*=post-content], [class*=entry-content]"

    /** Class names by which the common style libraries hide an element in print. */
    private val PRINT_HIDDEN = setOf("print:hidden", "d-print-none", "hidden-print", "print-hidden", "no-print", "noprint")

    private fun unlikely(element: Element): Boolean {
        if (element.normalName() in protectedTags) return false
        // What a page hides when it is printed is not the article: related stories, share buttons, advertising
        if (element.classNames().any { it in PRINT_HIDDEN }) return element.selectFirst(ARTICLE_INSIDE) == null
        val label = label(element)
        if (label.isBlank()) return false
        if (!always.containsMatchIn(label) && (!negative.containsMatchIn(label) || keep.containsMatchIn(label))) return false
        return element.selectFirst(ARTICLE_INSIDE) == null
    }

    /** The length of the paragraphs' text inside: what an article is made of, as opposed to menus and captions. */
    private fun paragraphText(element: Element): Int =
        element.select("p, pre, blockquote").sumOf { p -> p.ownText().length + p.children().filter { it.normalName() !in setOf("p", "pre", "blockquote") }.sumOf { it.text().length } } +
            (element.select(BROKEN_TEXT) + element).distinct().sumOf { lines(it).sumOf { line -> line.length } }

    /** Containers that may hold their text as lines parted by line breaks instead of paragraphs. */
    private const val BROKEN_TEXT = "div:has(> br), section:has(> br), article:has(> br), td:has(> br), main:has(> br)"

    /**
     * The lines of text an element holds directly, between its line breaks, when it is written that
     * way: some sites set a whole article as one block with `<br>` between its paragraphs and no
     * `<p>` at all. Each line counts as a paragraph does.
     */
    private fun lines(element: Element): List<String> =
        if (element.children().none { it.normalName() == "br" }) emptyList()
        // One line with a break after it is a lead or a caption, not a text set in lines
        else element.textNodes().map { it.text().trim() }.filter { it.length >= 25 }.takeIf { it.size >= 2 }.orEmpty()

    private fun linkDensity(element: Element): Float {
        val length = element.text().length
        if (length == 0) return 0f
        return element.select("a").sumOf { it.text().length }.toFloat() / length
    }

    private fun best(document: Document): Element? {
        val scores = HashMap<Element, Float>()
        fun base(element: Element): Float {
            var score = when (element.normalName()) {
                "article" -> 10f
                "div", "section", "main" -> 5f
                "pre", "td", "blockquote" -> 3f
                "ul", "ol", "li", "dl", "dd", "dt", "address" -> -3f
                "h1", "h2", "h3", "h4", "h5", "h6", "th", "header" -> -5f
                else -> 0f
            }
            val label = label(element)
            if (label.isNotBlank()) {
                if (positive.containsMatchIn(label)) score += 25f
                if (negative.containsMatchIn(label)) score -= 25f
            }
            return score
        }
        for (paragraph in document.select("p, pre, td, blockquote")) {
            val text = paragraph.text()
            if (text.length < 25) continue
            val points = 1f + text.count { it == ',' } + minOf(text.length / 100, 3)
            val parent = paragraph.parent() ?: continue
            scores[parent] = (scores[parent] ?: base(parent)) + points
            parent.parent()?.let { grand -> scores[grand] = (scores[grand] ?: base(grand)) + points / 2 }
        }
        // Text set as lines between line breaks gives its points to the element that holds it, as paragraphs do to their parent
        for (holder in document.select(BROKEN_TEXT)) {
            for (line in lines(holder)) {
                val points = 1f + line.count { it == ',' } + minOf(line.length / 100, 3)
                scores[holder] = (scores[holder] ?: base(holder)) + points
                holder.parent()?.let { parent -> scores[parent] = (scores[parent] ?: base(parent)) + points / 2 }
            }
        }
        if (scores.isEmpty()) return document.selectFirst("article") ?: document.selectFirst("main") ?: document.body()
        var top = scores.maxByOrNull { (element, score) -> score * (1 - linkDensity(element)) }?.key ?: return null
        // An article split over several containers (pages cut it for an ad or a box in the middle): an ancestor
        // holding clearly more paragraph text than the best container is the better cut. Comments, sidebars and
        // footers are gone by now, so what an ancestor adds is mostly the rest of the article.
        var held = paragraphText(top)
        var parent = top.parent()
        while (parent != null && parent.normalName() != "html") {
            val wider = paragraphText(parent)
            // Only text widens the cut: a container that holds none more than the best one is the page around it
            if (wider > held && wider >= held * 1.2f && linkDensity(parent) < 0.35f) {
                top = parent
                held = wider
            }
            parent = parent.parent()
        }
        return top
    }

    /**
     * Inside the article: boxes of links (related stories, tags), leftovers by name, and the title
     * the reader shows itself. A box with pictures goes too when it is a teaser of another page: the
     * pictures are links themselves and nearly all of its text is a headline's worth of links.
     */
    private fun clean(candidate: Element, title: String?) {
        candidate.select("div, section, ul, ol, table, p").filter { element ->
            val text = element.text()
            if (element.parent() == null || text.isEmpty() || element.selectFirst("pre, blockquote") != null) return@filter false
            val links = element.select("a")
            val pictures = element.select("img, picture, figure")
            if (pictures.isNotEmpty()) {
                // The teaser's signature: its picture and its headline lead to the same page
                val headlines = links.filter { it.selectFirst("img, picture") == null && it.text().length >= TEASER_HEADLINE }.map { it.absUrl("href") }.toSet()
                return@filter linkDensity(element) > 0.6f && pictures.all { picture ->
                    picture.parents().firstOrNull { it.normalName() == "a" }?.absUrl("href")?.takeIf { it.isNotEmpty() } in headlines
                }
            }
            (linkDensity(element) > 0.6f && links.size > 1) || (element.normalName() != "p" && text.length < 25 && links.size == 1 && linkDensity(element) > 0.9f)
        }.forEach { it.remove() }
        if (title != null) candidate.select("h1, h2").firstOrNull { it.text().trim().equals(title, ignoreCase = true) }?.remove()
        candidate.select("header").filter { it.selectFirst("p, img, figure") == null }.forEach { it.remove() }
    }

    /** `articleBody` of a schema.org article in the page's JSON-LD, if it carries one. */
    private fun structuredBody(document: Document): String? {
        for (script in document.select("script[type=application/ld+json]")) {
            val root = try {
                Json.parseToJsonElement(script.data())
            } catch (_: Exception) {
                continue
            }
            find(root)?.let { return it }
        }
        return null
    }

    private fun find(element: JsonElement): String? = when (element) {
        is JsonObject -> (element["articleBody"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length >= MIN_TEXT }
            ?: element.values.firstNotNullOfOrNull { find(it) }
        is JsonArray -> element.firstNotNullOfOrNull { find(it) }
        else -> null
    }

    private fun supported(charset: String): Boolean = try {
        java.nio.charset.Charset.isSupported(charset)
    } catch (_: IllegalArgumentException) {
        false
    }
}
