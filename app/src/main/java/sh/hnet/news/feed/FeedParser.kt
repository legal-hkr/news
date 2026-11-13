package sh.hnet.news.feed

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

/**
 * Reads a feed of any of the formats still met: RSS 0.91, 0.92 and 2.0, RSS 0.90 and 1.0 (RDF),
 * Atom 0.3 and 1.0, with the usual extensions (content:encoded, Dublin Core, Media RSS, Atom links
 * inside RSS). Old and careless feeds are the rule, so reading is forgiving: the XML is parsed by
 * jsoup's lenient parser (unclosed tags, bare ampersands, HTML entities, junk before the prolog),
 * the encoding is taken from the byte order mark or the XML declaration before the server's word
 * for it, unknown elements are skipped, and a missing field is null rather than an error.
 */
object FeedParser {
    /**
     * @param bytes The document as received
     * @param charset The charset the server named, if any; used only when the document names none
     * @param url The address the document came from; relative addresses in it are resolved against it
     * @throws NotAFeedException When the document is not a feed
     */
    fun parse(bytes: ByteArray, charset: String?, url: String): ParsedFeed {
        val document = Jsoup.parse(ByteArrayInputStream(bytes), declaredCharset(bytes) ?: charset?.takeIf { supported(it) }, url, Parser.xmlParser())
        return parse(document, url)
    }

    /** For text already decoded (tests, documents held as strings). */
    fun parse(text: String, url: String): ParsedFeed = parse(Jsoup.parse(text, url, Parser.xmlParser()), url)

    private fun parse(document: Document, url: String): ParsedFeed {
        val root = document.children().firstOrNull { it.local in setOf("rss", "rdf", "feed") }
            ?: throw NotAFeedException("Root element: ${document.children().firstOrNull()?.tagName() ?: "none"}")
        return when (root.local) {
            "feed" -> atom(root, url)
            else -> rss(root, url)
        }
    }

    // RSS 0.9x, 2.0 and RDF (RSS 0.90, 1.0)

    private fun rss(root: Element, url: String): ParsedFeed {
        val rdf = root.local == "rdf"
        val channel = root.plain("channel") ?: root
        val base = base(channel, base(root, url))
        val site = channel.plainText("link")?.let { resolve(base, it) } ?: channel.atomLink("alternate", base)
        // In RDF the items stand beside the channel, in RSS inside it; careless feeds do either
        val items = (channel.children() + root.children()).filter { it.local == "item" && it.prefix.isEmpty() }
        // RSS: <image><url>; RDF: <image> beside the channel
        val image = channel.plain("image")?.plainText("url") ?: root.plain("image")?.plainText("url") ?: channel.child("icon")?.cleanText()
        return ParsedFeed(
            format = if (rdf) FeedFormat.RDF else FeedFormat.RSS,
            title = channel.plain("title")?.let { plainText(it.wholeText()) },
            siteUrl = site,
            description = (channel.plain("description") ?: channel.child("subtitle"))?.let { plainText(it.wholeText()) },
            iconUrl = image?.let { resolve(base, it) },
            language = channel.plainText("language") ?: channel.child("language")?.cleanText() ?: channel.attributeValue("xml:lang"),
            items = items.mapNotNull { rssItem(it, base) }.distinctBy { it.id },
        )
    }

    private fun rssItem(item: Element, feedBase: String): ParsedItem? {
        val base = base(item, feedBase)
        val guid = item.plain("guid")
        val guidText = guid?.cleanText()
        val permalink = guidText?.takeIf { !guid.attr("isPermaLink").equals("false", ignoreCase = true) && it.startsWith("http", ignoreCase = true) }
        // The link, else a guid that is an address, else an Atom link, else RDF's rdf:about
        val link = (item.plainText("link") ?: permalink ?: item.attributeValue("rdf:about")?.takeIf { it.startsWith("http", ignoreCase = true) })
            ?.let { resolve(base, it) } ?: item.atomLink("alternate", base)
        val title = item.plain("title")?.let { plainText(it.wholeText()) }
        val description = item.plain("description")?.wholeText()?.trim()?.takeIf { it.isNotEmpty() }
        // content:encoded is the full text; some feeds use Atom's content or summary inside RSS
        val encoded = (item.child("encoded") ?: item.child("content", prefixed = "atom") ?: item.child("full-text"))?.let { textConstruct(it) }
        val summary = description ?: item.child("summary")?.let { textConstruct(it) }
        val id = guidText ?: item.attributeValue("rdf:about") ?: link ?: title ?: return null
        return ParsedItem(
            id = id,
            title = title,
            link = link,
            published = date(item, "pubDate", "date", "published", "issued", "updated", "modified"),
            author = (item.child("creator") ?: item.plain("author") ?: item.child("author"))?.let { author(it) },
            summaryHtml = if (encoded != null && summary == encoded) null else summary,
            contentHtml = encoded,
            imageUrl = image(item, base),
            categories = categories(item),
        )
    }

    // Atom 1.0 and 0.3

    private fun atom(root: Element, url: String): ParsedFeed {
        val base = base(root, url)
        return ParsedFeed(
            format = FeedFormat.ATOM,
            title = root.child("title")?.let { plainText(textConstruct(it, atom = true)) },
            siteUrl = root.atomLink("alternate", base),
            description = (root.child("subtitle") ?: root.child("tagline"))?.let { plainText(textConstruct(it, atom = true)) },
            iconUrl = (root.child("icon") ?: root.child("logo"))?.cleanText()?.let { resolve(base, it) },
            language = root.attributeValue("xml:lang"),
            items = root.children().filter { it.local == "entry" }.mapNotNull { atomEntry(it, base) }.distinctBy { it.id },
        )
    }

    private fun atomEntry(entry: Element, feedBase: String): ParsedItem? {
        val base = base(entry, feedBase)
        val link = entry.atomLink("alternate", base)
        val title = entry.child("title")?.let { plainText(textConstruct(it, atom = true)) }
        val content = entry.child("content")?.takeIf { !it.hasAttr("src") }?.let { textConstruct(it, atom = true) }
        // Media RSS carries the description for feeds without Atom's own (video feeds)
        val summary = entry.child("summary")?.let { textConstruct(it, atom = true) } ?: entry.descendant("description")?.let { escape(it.wholeText().trim()) }?.takeIf { it.isNotEmpty() }
        val id = entry.child("id")?.cleanText() ?: link ?: title ?: return null
        return ParsedItem(
            id = id,
            title = title,
            link = link,
            // When it was published, not when it was last touched; Atom 0.3 says issued or created
            published = date(entry, "published", "issued", "created", "updated", "modified", "date"),
            author = (entry.child("author") ?: entry.parent()?.child("author") ?: entry.child("creator"))?.let { author(it) },
            summaryHtml = summary,
            contentHtml = content,
            imageUrl = image(entry, base) ?: entry.children().firstOrNull { it.local == "link" && it.attr("rel") == "enclosure" && it.attr("type").startsWith("image/") }
                ?.attr("href")?.let { resolve(base, it) },
            categories = entry.children().filter { it.local == "category" }.mapNotNull { c -> (c.attr("label").ifBlank { c.attr("term") }.ifBlank { c.text() }).trim().takeIf { it.isNotEmpty() } }.distinct(),
        )
    }

    /**
     * A text construct as HTML. Atom: type "html" holds escaped markup (the text is the markup), "xhtml"
     * holds elements (the markup is what's inside, without the wrapping div), "text" holds plain text,
     * escaped here; Atom 0.3 says a MIME type and a mode instead. With no type, Atom means plain text,
     * but feeds put markup there too, so text that holds tags is taken as markup. RSS elements have no
     * type and hold markup by custom.
     */
    private fun textConstruct(element: Element, atom: Boolean = false): String? {
        val type = element.attr("type").lowercase()
        val text = when {
            type == "xhtml" || type.endsWith("/xhtml+xml") || (element.attr("mode") == "xml" && element.children().isNotEmpty()) ->
                (element.children().singleOrNull()?.takeIf { it.local == "div" } ?: element).html()
            type == "text" || type == "text/plain" -> escape(element.wholeText())
            type.isEmpty() && atom && !markup.containsMatchIn(element.wholeText()) -> escape(element.wholeText())
            else -> element.wholeText()
        }
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private val markup = Regex("""</?[A-Za-z][^<>]*>|&#?\w+;""")

    // Shared parts

    private fun date(element: Element, vararg names: String): Instant? =
        names.firstNotNullOfOrNull { name -> element.children().firstOrNull { it.local.equals(name, ignoreCase = true) }?.let { FeedDates.parse(it.text()) } }

    /**
     * An author's name. RSS 2.0 wants "address (Name)" and feeds also write "Name <address>" or the
     * name alone; Atom nests a name element. An address alone is not a name anyone wants to read.
     */
    private fun author(element: Element): String? {
        element.children().firstOrNull { it.local == "name" }?.let { return it.cleanText() }
        // Before the markup is taken out: "<address>" would go with it
        val raw = element.wholeText().trim()
        val named = Regex("""^\S+@\S+\s*\((.+)\)$""").find(raw)?.groupValues?.get(1)
            ?: Regex("""^(.+?)\s*<\S+@\S+>$""").find(raw)?.groupValues?.get(1)
        val text = plainText(named ?: raw)?.trim('"', ' ')?.takeIf { it.isNotEmpty() } ?: return null
        return text.takeIf { !Regex("""^\S+@\S+$""").matches(it) }
    }

    private fun categories(item: Element): List<String> =
        item.children().filter { it.local == "category" || it.local == "subject" }.mapNotNull { plainText(it.wholeText()) }.distinct()

    /** The lead image: Media RSS thumbnail or image content (also inside media:group), an image enclosure, a podcast-style image. */
    private fun image(item: Element, base: String): String? {
        val media = item.children().flatMap { if (it.local == "group") it.children() + it else listOf(it) }
        val address = media.firstOrNull { it.local == "thumbnail" && it.hasAttr("url") }?.attr("url")
            ?: media.firstOrNull { it.local == "content" && it.hasAttr("url") && (it.attr("medium") == "image" || it.attr("type").startsWith("image/")) }?.attr("url")
            // A media:content may hold its thumbnail
            ?: media.filter { it.local == "content" }.firstNotNullOfOrNull { c -> c.children().firstOrNull { it.local == "thumbnail" && it.hasAttr("url") }?.attr("url") }
            ?: item.children().firstOrNull { it.local == "enclosure" && it.attr("type").startsWith("image/") }?.attr("url")
            ?: item.children().firstOrNull { it.local == "image" && it.prefix.isNotEmpty() && it.hasAttr("href") }?.attr("href")
        return address?.trim()?.takeIf { it.isNotEmpty() }?.let { resolve(base, it) }
    }

    /** An Atom link of the given relation ("alternate" is also what no relation means); a type of text/html wins among several. */
    private fun Element.atomLink(rel: String, base: String): String? {
        val links = children().filter { it.local == "link" && it.hasAttr("href") && it.attr("rel").ifEmpty { "alternate" } == rel }
        val link = links.firstOrNull { it.attr("type").isEmpty() || it.attr("type").contains("html") } ?: links.firstOrNull()
        return link?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }?.let { resolve(base(link, base), it) }
    }

    private fun base(element: Element, inherited: String): String =
        element.attributeValue("xml:base")?.let { resolve(inherited, it) } ?: inherited

    /** Markup and entities out, white space collapsed: a title or a name as plain text. */
    internal fun plainText(text: String?): String? {
        var value = text ?: return null
        // Titles are often escaped twice ("&amp;quot;"), and some hold markup
        if ('<' in value || '&' in value) value = Jsoup.parse(value).text()
        if ('&' in value) value = Parser.unescapeEntities(value, false)
        return collapse(value)
    }

    /** White space of every kind (the non-breaking one too) to single spaces. */
    internal fun collapse(text: String): String? = text.replace(Regex("""[\s\u00A0]+"""), " ").trim().takeIf { it.isNotEmpty() }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** An absolute http(s) address from one that may be relative, or null when it is neither. Feeds write spaces and other characters a URI may not hold. */
    internal fun resolve(base: String, reference: String): String? {
        val cleaned = reference.trim().replace(" ", "%20").replace("\n", "").replace("\t", "")
        if (cleaned.isEmpty()) return null
        val resolved = try {
            URI(base).resolve(URI(cleaned)).toString()
        } catch (_: URISyntaxException) {
            cleaned
        } catch (_: IllegalArgumentException) {
            cleaned
        }
        return resolved.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    /**
     * The encoding the document itself names in its XML declaration, which wins over the server's
     * header (servers say ISO-8859-1 or nothing for files in any encoding). jsoup detects a byte
     * order mark by itself.
     */
    private fun declaredCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val name = Regex("""<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._:-]+)["']""").find(head)?.groupValues?.get(1)
        return name?.takeIf { supported(it) }
    }

    private fun supported(charset: String): Boolean = try {
        java.nio.charset.Charset.isSupported(charset)
    } catch (_: IllegalArgumentException) {
        false
    }

    // Element names: feeds differ in prefixes and in case, so elements are matched by local name

    private val Element.prefix: String get() = tagName().substringBefore(':', "").lowercase()
    private val Element.local: String get() = tagName().substringAfter(':').lowercase()

    /** The first child of this local name, whatever its prefix (or only with [prefixed]). */
    private fun Element.child(name: String, prefixed: String? = null): Element? =
        children().firstOrNull { it.local == name.lowercase() && (prefixed == null || it.prefix == prefixed) }

    /** The first child of this name without a prefix: RSS's own elements, as opposed to an extension's of the same name (atom:link, media:title, itunes:image). */
    private fun Element.plain(name: String): Element? = children().firstOrNull { it.prefix.isEmpty() && it.local == name.lowercase() }

    private fun Element.plainText(name: String): String? = plain(name)?.cleanText()

    private fun Element.descendant(name: String): Element? = allElements.firstOrNull { it !== this && it.local == name }

    private fun Element.cleanText(): String? = wholeText().trim().takeIf { it.isNotEmpty() }

    private fun Element.attributeValue(name: String): String? = attributes().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
}
