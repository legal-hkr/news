package sh.hnet.news.feed

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A subscription as OPML carries it. */
data class OpmlFeed(
    val title: String,
    /** The feed's address. */
    val xmlUrl: String,
    /** The site's address, when the file names it. */
    val htmlUrl: String? = null,
    /** The folder it is in; null for none. */
    val folder: String? = null,
)

/** The document is not OPML. */
class NotOpmlException(message: String) : Exception(message)

/**
 * Subscription lists in OPML, the format every feed reader exports and imports. Reading takes OPML
 * 1.0 and 2.0 as readers really write them: an outline with a feed address is a feed whatever its
 * type attribute says, one without is a folder, attribute names in any case, the name in title or in
 * text. Folders are of one level here (SPEC §9): a feed in nested folders belongs to the outermost.
 * Writing gives OPML 2.0 that other readers take.
 */
object Opml {
    /**
     * The feeds of an OPML document, in the file's order, each address once.
     * @throws NotOpmlException When the document is not OPML
     */
    fun parse(bytes: ByteArray): List<OpmlFeed> = parse(Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser()))

    fun parse(text: String): List<OpmlFeed> = parse(Jsoup.parse(text, "", Parser.xmlParser()))

    private fun parse(document: Document): List<OpmlFeed> {
        val root = document.children().firstOrNull { it.tagName().equals("opml", ignoreCase = true) }
            ?: throw NotOpmlException("Root element: ${document.children().firstOrNull()?.tagName() ?: "none"}")
        val body = root.children().firstOrNull { it.tagName().equals("body", ignoreCase = true) } ?: root
        val feeds = mutableListOf<OpmlFeed>()
        collect(body, null, feeds)
        return feeds.distinctBy { key(it.xmlUrl) }
    }

    private fun collect(parent: Element, folder: String?, into: MutableList<OpmlFeed>) {
        for (outline in parent.children()) {
            if (!outline.tagName().equals("outline", ignoreCase = true)) continue
            val name = (outline.attributeValue("title") ?: outline.attributeValue("text"))?.let { FeedParser.collapse(it) }
            val address = outline.attributeValue("xmlUrl")?.let { address(it) }
            if (address != null) {
                into += OpmlFeed(
                    title = name ?: host(address) ?: address,
                    xmlUrl = address,
                    htmlUrl = outline.attributeValue("htmlUrl")?.let { address(it) },
                    folder = folder,
                )
            }
            // A folder; nested ones keep the outermost name. (A feed outline with children is rare but met: its children are read too.)
            if (outline.children().isNotEmpty()) collect(outline, folder ?: name.takeIf { address == null }, into)
        }
    }

    /** OPML 2.0 with the feeds grouped under their folders: feeds without a folder first, then the folders in the order they first appear. */
    fun write(feeds: List<OpmlFeed>, title: String, created: Instant): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head>\n")
        append("    <title>").append(escape(title)).append("</title>\n")
        append("    <dateCreated>").append(rfc822.format(created.atOffset(ZoneOffset.UTC))).append("</dateCreated>\n")
        append("  </head>\n")
        append("  <body>\n")
        feeds.filter { it.folder == null }.forEach { outline(it, "    ") }
        feeds.filter { it.folder != null }.groupBy { it.folder!! }.forEach { (folder, inside) ->
            val name = escape(folder)
            append("    <outline text=\"").append(name).append("\" title=\"").append(name).append("\">\n")
            inside.forEach { outline(it, "      ") }
            append("    </outline>\n")
        }
        append("  </body>\n")
        append("</opml>\n")
    }

    private fun StringBuilder.outline(feed: OpmlFeed, indent: String) {
        val name = escape(feed.title)
        append(indent).append("<outline type=\"rss\" text=\"").append(name).append("\" title=\"").append(name).append("\" xmlUrl=\"").append(escape(feed.xmlUrl)).append('"')
        feed.htmlUrl?.let { append(" htmlUrl=\"").append(escape(it)).append('"') }
        append("/>\n")
    }

    private val rfc822 = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** An http(s) address, or null; files hold "feed://" (an old browser scheme for http) and addresses without a scheme. */
    private fun address(text: String): String? {
        var value = text.trim()
        if (value.startsWith("feed://", ignoreCase = true)) value = "http://" + value.substring(7)
        if (value.startsWith("feed:", ignoreCase = true)) value = value.substring(5)
        if (!value.contains("://") && value.contains('.') && !value.contains(' ')) value = "https://$value"
        return value.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    /** What makes two addresses the same subscription: scheme, a leading "www." and a trailing slash aside. */
    fun key(address: String): String =
        address.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun host(address: String): String? = try {
        URI(address).host?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }

    private fun Element.attributeValue(name: String): String? = attributes().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
}
