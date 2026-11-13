package sh.hnet.news.feed

import java.time.Instant

/** A feed as its file describes it, whatever the format; every address is absolute. */
data class ParsedFeed(
    val format: FeedFormat,
    val title: String?,
    /** The site the feed belongs to. */
    val siteUrl: String?,
    val description: String?,
    /** The feed's own picture (RSS `image`, Atom `icon` or `logo`). */
    val iconUrl: String?,
    val language: String?,
    val items: List<ParsedItem>,
)

enum class FeedFormat { RSS, RDF, ATOM }

/** One article of a feed. */
data class ParsedItem(
    /** What tells this article from the feed's others across refreshes: its guid or id, else its link, else its title. */
    val id: String,
    /** Plain text. */
    val title: String?,
    val link: String?,
    val published: Instant?,
    val author: String?,
    /** The teaser, as HTML. */
    val summaryHtml: String?,
    /** The full text when the feed carries one apart from the teaser, as HTML. */
    val contentHtml: String?,
    /** The lead image the feed names (media thumbnail, image enclosure). */
    val imageUrl: String?,
    val categories: List<String>,
)

/** The document is not RSS, RDF or Atom (a web page, an error page, something else). */
class NotAFeedException(message: String) : Exception(message)
