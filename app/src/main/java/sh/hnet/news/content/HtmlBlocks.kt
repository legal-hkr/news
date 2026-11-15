package sh.hnet.news.content

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Markup to blocks: the one place that reads an article's HTML, whether a feed sent it or the
 * extractor cut it out of a page. Scripts, styles, forms and everything else that isn't content
 * are dropped; frames and players become [Block.Unsupported]; unknown elements give their text.
 */
object HtmlBlocks {
    /** @param baseUrl What relative addresses of links and images are resolved against */
    fun convert(html: String, baseUrl: String): List<Block> = convert(Jsoup.parseBodyFragment(html, baseUrl).body())

    fun convert(root: Element): List<Block> = Walker(splitting = true).apply { children(root); flush() }.out.let { tidy(it) }

    private val skipped = setOf("script", "style", "noscript", "template", "svg", "form", "input", "button", "select", "textarea", "nav", "head", "title", "link", "meta", "canvas", "map", "dialog")
    private val styles = mapOf(
        "b" to Style.BOLD, "strong" to Style.BOLD, "i" to Style.ITALIC, "em" to Style.ITALIC, "cite" to Style.ITALIC, "dfn" to Style.ITALIC, "var" to Style.ITALIC,
        "code" to Style.CODE, "kbd" to Style.CODE, "samp" to Style.CODE, "tt" to Style.CODE,
        "sup" to Style.SUPERSCRIPT, "sub" to Style.SUBSCRIPT, "s" to Style.STRIKE, "del" to Style.STRIKE, "strike" to Style.STRIKE,
    )
    private val blockTags = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "aside", "address", "center", "details", "summary", "fieldset", "figcaption", "hgroup",
        "li", "dd", "dt", "tr", "td", "th", "tbody", "thead", "tfoot", "caption", "body", "html",
    )

    /**
     * @param splitting Two line breaks in a row end the paragraph. Only for an article's own text: a walker
     *   that gathers one piece of text (a caption, a table's cell) keeps the breaks as line ends
     */
    /** Class names pages give a caption: "caption", "wp-caption-text", "photo-credit", "image--author", "media--lead". */
    private val captionName = Regex("""(^|[-_])(fig)?caption([-_]|$)|(^|[-_])credits?([-_]|$)|(image|img|photo|picture|media|foto)[-_]+(author|lead|desc|description|source|title)([-_]|$)""", RegexOption.IGNORE_CASE)

    private class Walker(private val splitting: Boolean = false) {
        val out = mutableListOf<Block>()
        /** Inside an inline element or a heading, whose text is one piece: ending the paragraph there would lose its style, its link or its level. */
        private var whole = 0
        private val text = StringBuilder()
        private val spans = mutableListOf<Span>()

        /** Ends the paragraph in progress, if there is one. */
        fun flush() {
            rich()?.let { out += Block.Paragraph(it) }
        }

        /** The inline text gathered so far, trimmed, with its spans; null when there is none. Clears it. */
        fun rich(): RichText? {
            val raw = text.toString()
            val lead = raw.length - raw.trimStart().length
            val trimmed = raw.trim()
            val result = if (trimmed.isEmpty()) null else RichText(
                trimmed,
                spans.mapNotNull { span ->
                    val start = (span.start - lead).coerceIn(0, trimmed.length)
                    val end = (span.end - lead).coerceIn(0, trimmed.length)
                    if (end > start) span.copy(start = start, end = end) else null
                },
            )
            text.clear()
            spans.clear()
            return result
        }

        fun children(element: Element) = element.childNodes().forEach { node(it) }

        private fun node(node: Node) {
            when (node) {
                is TextNode -> append(node.wholeText)
                is Element -> element(node)
            }
        }

        /** White space collapsed as a browser does: runs to one space, none at a paragraph's start. */
        private fun append(raw: String) {
            val collapsed = raw.replace(Regex("""[ \t\r\n\u000C]+"""), " ")
            val piece = if (collapsed.startsWith(' ') && (text.isEmpty() || text.endsWith(' ') || text.endsWith('\n'))) collapsed.drop(1) else collapsed
            text.append(piece)
        }

        private fun element(element: Element) {
            val tag = element.normalName()
            when {
                tag in skipped -> Unit
                tag == "br" -> when {
                    text.isBlank() -> Unit
                    // A second break in a row parts paragraphs: text set without <p> is written that way
                    splitting && whole == 0 && text.trimEnd(' ', '\t', '\u00A0').endsWith('\n') -> flush()
                    else -> text.append('\n')
                }
                tag in styles -> styled(styles.getValue(tag), null) { children(element) }
                tag == "a" -> {
                    val address = element.absUrl("href").takeIf { it.startsWith("http", ignoreCase = true) || it.startsWith("mailto:", ignoreCase = true) }
                    // A link around an image is the image; the link is lost with the markup
                    if (address != null && element.selectFirst("img, picture, figure") == null) styled(Style.LINK, address) { children(element) } else children(element)
                }
                tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> {
                    flush()
                    whole++
                    children(element)
                    whole--
                    // The source's six levels on our three; the article's title is above them all
                    rich()?.let { out += Block.Heading(when (tag[1]) { '1', '2' -> 1; '3' -> 2; else -> 3 }, it) }
                }
                tag == "img" -> image(element)?.let { flush(); out += it }
                tag == "picture" -> (element.selectFirst("img")?.let { image(it) } ?: source(element)?.let { Block.Image(it) })?.let { flush(); out += it }
                tag == "figure" -> figure(element)
                // Named as a caption and standing right after an image: the image's caption, though the page uses no <figcaption>
                isCaption(element) && captionOf(element) -> Unit
                tag == "blockquote" -> {
                    flush()
                    if (element.hasClass("twitter-tweet") || element.hasClass("instagram-media") || element.hasClass("tiktok-embed") || element.hasClass("bluesky-embed")) {
                        out += Block.Unsupported(Block.Kind.SOCIAL, element.select("a[href]").lastOrNull()?.absUrl("href")?.takeIf { it.isNotEmpty() })
                    } else {
                        val inside = convert(element)
                        if (inside.isNotEmpty()) out += Block.Quote(inside)
                    }
                }
                tag == "ul" || tag == "ol" || tag == "menu" -> {
                    flush()
                    val items = element.children().filter { it.normalName() == "li" }.map { convert(it) }.filter { it.isNotEmpty() }
                    if (items.isNotEmpty()) out += Block.ItemList(tag == "ol", element.attr("start").toIntOrNull() ?: 1, items)
                }
                tag == "pre" -> {
                    flush()
                    val code = element.wholeText().trim('\n', '\r').trimEnd()
                    if (code.isNotBlank()) out += Block.Code(code)
                }
                tag == "table" -> table(element)
                tag == "hr" -> { flush(); if (out.lastOrNull() != Block.Rule) out += Block.Rule }
                tag == "iframe" || tag == "embed" || tag == "object" -> {
                    flush()
                    val address = (element.absUrl("src").ifEmpty { element.absUrl("data-src") }.ifEmpty { element.absUrl("data") }).takeIf { it.startsWith("http", ignoreCase = true) }
                    if (address != null) out += Block.Unsupported(frameKind(address), address)
                }
                tag == "video" || tag == "audio" -> {
                    flush()
                    val address = (element.absUrl("src").ifEmpty { element.selectFirst("source[src]")?.absUrl("src").orEmpty() }).takeIf { it.startsWith("http", ignoreCase = true) }
                    out += Block.Unsupported(if (tag == "video") Block.Kind.VIDEO else Block.Kind.AUDIO, address)
                }
                tag == "dt" -> { flush(); styled(Style.BOLD, null) { children(element) }; flush() }
                tag in blockTags || tag == "dl" -> { flush(); children(element); flush() }
                // Inline or unknown: its text belongs to the paragraph around it
                else -> children(element)
            }
        }

        private fun styled(style: Style, url: String?, content: () -> Unit) {
            val start = text.length
            // Blocks inside an inline element (a link around a div) end the span with the paragraph
            val before = out.size
            whole++
            content()
            whole--
            if (out.size == before && text.length > start) spans += Span(start, text.length, style, url)
        }

        /**
         * Takes [element]'s text as the caption of the image just before it; false when there is no
         * such image, and the element is then read as any other. Pages write captions in many ways
         * without `<figure>`: a description and a credit as two elements (both are taken, on two
         * lines), or the same caption twice, for two screen widths (the second is passed over).
         */
        private fun captionOf(element: Element): Boolean {
            if (text.isNotBlank()) return false
            val image = out.lastOrNull() as? Block.Image ?: return false
            val caption = Walker().apply { children(element) }.cellText()
            val had = image.caption
            fun plain(text: String) = text.replace(Regex("""\s+"""), " ").trim()
            val merged = when {
                caption.text.isBlank() -> had
                had == null -> caption
                plain(caption.text) in plain(had.text) -> had
                plain(had.text) in plain(caption.text) -> caption
                else -> RichText(had.text + "\n" + caption.text, had.spans + caption.spans.map { it.copy(start = it.start + had.text.length + 1, end = it.end + had.text.length + 1) })
            }
            out[out.lastIndex] = image.copy(caption = merged)
            return true
        }

        private fun isCaption(element: Element): Boolean = element.classNames().any { captionName.containsMatchIn(it) }

        private fun figure(element: Element) {
            flush()
            // The figure's own caption: not one of a figure inside it (a gallery of figures), though it may stand in a wrapper
            val own = element.select("figcaption").firstOrNull { caption -> caption.parents().firstOrNull { it.normalName() == "figure" } === element }
            val caption = own?.let { Walker().apply { children(it) }.rich() }
            // Out of the way while the figure's content is walked, so it is not read a second time as text
            own?.remove()
            val before = out.size
            element.childNodes().forEach { child -> node(child) }
            flush()
            // The caption belongs to the figure's last image; a figure of something else (a quote, a table) keeps it as text
            val last = out.indexOfLast { it is Block.Image }
            if (caption != null) {
                // An image that has its own caption already (a figure inside this one) keeps it
                val image = (out.getOrNull(last) as? Block.Image)?.takeIf { last >= before && it.caption == null }
                if (image != null) out[last] = image.copy(caption = caption) else out += Block.Paragraph(caption)
            }
        }

        private fun table(element: Element) {
            flush()
            val rows = element.select("tr").filter { it.parents().firstOrNull { p -> p.normalName() == "table" } === element }.map { row ->
                row.children().filter { it.normalName() == "td" || it.normalName() == "th" }.map { cell ->
                    Cell(Walker().apply { children(cell) }.let { walker -> walker.cellText() }, header = cell.normalName() == "th")
                }
            }.filter { it.isNotEmpty() }
            when {
                rows.isEmpty() -> Unit
                // One column is a table used for layout: its cells are plain content
                rows.all { it.size == 1 } -> element.select("td, th").filter { it.parents().firstOrNull { p -> p.normalName() == "table" } === element }.forEach { children(it); flush() }
                else -> out += Block.Table(rows)
            }
        }

        /** A cell's content on one line of text: blocks inside a cell are joined by spaces. */
        fun cellText(): RichText {
            val parts = out.map { Blocks.text(listOf(it)) }.filter { it.isNotEmpty() }
            val own = rich()
            if (parts.isEmpty()) return own ?: RichText("")
            return RichText((parts + listOfNotNull(own?.text)).joinToString(" "))
        }

        private fun image(element: Element): Block.Image? {
            // A tracking pixel or a spacer
            val width = element.attr("width").toIntOrNull()
            val height = element.attr("height").toIntOrNull()
            if ((width != null && width <= 2) || (height != null && height <= 2)) return null
            // An avatar, an emoticon, the thumbnail of a "read more" box: not a picture of the article
            if (width != null && height != null && width <= THUMBNAIL && height <= THUMBNAIL) return null
            val address = listOf("src", "data-src", "data-lazy-src", "data-original", "data-url").map { element.absUrl(it) }.firstOrNull { it.startsWith("http", ignoreCase = true) }
                ?: srcset(element, element.baseUri())
                ?: (element.parent()?.takeIf { it.normalName() == "picture" }?.let { source(it) })
                ?: return null
            // A vector drawing (a logo, a badge) is nothing the phone's decoder draws: it would stay a placeholder
            if (address.substringBefore('?').substringBefore('#').endsWith(".svg", ignoreCase = true)) return null
            return Block.Image(address, alt = element.attr("alt").trim().takeIf { it.isNotEmpty() })
        }

        private fun source(picture: Element): String? = picture.select("source").firstNotNullOfOrNull { srcset(it, picture.baseUri()) }

        /** The element's own srcset, or the one a lazy loader keeps aside while a placeholder stands in. */
        private fun srcset(element: Element, base: String): String? = srcset(element.attr("srcset"), base) ?: srcset(element.attr("data-srcset"), base)

        /**
         * The widest candidate of a srcset. Addresses may hold commas themselves (image services write
         * their options that way), so candidates are read as HTML does: an address runs to the next
         * white space, and only a comma at its end or after its descriptor closes the candidate.
         */
        private fun srcset(value: String, base: String): String? {
            val candidates = ArrayList<Pair<String, Float>>()
            var position = 0
            while (position < value.length) {
                while (position < value.length && (value[position].isWhitespace() || value[position] == ',')) position++
                val start = position
                while (position < value.length && !value[position].isWhitespace()) position++
                if (position == start) break
                val address = value.substring(start, position)
                if (address.endsWith(',')) {
                    candidates += address.trimEnd(',') to 1f
                    continue
                }
                val end = value.indexOf(',', position).let { if (it < 0) value.length else it }
                candidates += address to (value.substring(position, end).trim().dropLast(1).toFloatOrNull() ?: 1f)
                position = end
            }
            val best = candidates.filter { it.first.isNotEmpty() }.maxByOrNull { it.second }?.first ?: return null
            return try {
                java.net.URI(base).resolve(best).toString()
            } catch (_: Exception) {
                best
            }.takeIf { it.startsWith("http", ignoreCase = true) }
        }

        private fun frameKind(address: String): Block.Kind {
            val lower = address.lowercase()
            return when {
                listOf("youtube.", "youtu.be", "youtube-nocookie.", "vimeo.", "dailymotion.", "twitch.", "cda.pl", "player.").any { it in lower } -> Block.Kind.VIDEO
                listOf("soundcloud.", "spotify.", "podcast", "anchor.fm", "audio").any { it in lower } -> Block.Kind.AUDIO
                listOf("twitter.", "x.com", "instagram.", "facebook.", "tiktok.", "mastodon", "bsky.").any { it in lower } -> Block.Kind.SOCIAL
                else -> Block.Kind.FRAME
            }
        }
    }

    /** The same image twice in a row once (pages repeat the lead image); no rule first or last. */
    private fun tidy(blocks: List<Block>): List<Block> {
        val result = mutableListOf<Block>()
        for (block in blocks) {
            val last = result.lastOrNull()
            if (block is Block.Image && last is Block.Image && last.url == block.url) continue
            if (block == Block.Rule && (last == null || last == Block.Rule)) continue
            result += block
        }
        while (result.lastOrNull() == Block.Rule) result.removeAt(result.lastIndex)
        return result
    }
}

/** Both sides at most this, as the page declares them: a thumbnail. */
private const val THUMBNAIL = 120
