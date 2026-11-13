package sh.hnet.news.ui.reader

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import sh.hnet.news.content.RichText
import sh.hnet.news.content.Style

/**
 * The reader's type ramp (SPEC §5.3): the one place where sizes derive from a setting. Everything
 * is a multiple of [base], 16 sp times the text size; [serif] swaps the family of the article's own
 * text (not of code); [language] is the feed's, for line breaking. Text is aligned to the start
 * and words are never hyphenated (the owner's choice over SPEC §5.4; justification was tried and
 * dropped).
 */
class ReaderStyle(percent: Int, val serif: Boolean, language: String?) {
    val base: TextUnit = (16f * percent / 100f).sp
    private val family: FontFamily? = if (serif) FontFamily.Serif else null
    private val locales: LocaleList? = language?.takeIf { it.isNotBlank() }?.let { LocaleList(Locale(it.replace('_', '-'))) }

    private fun text(scale: Float, lineScale: Float, weight: FontWeight) = TextStyle(
        fontSize = base * scale,
        lineHeight = base * lineScale,
        fontWeight = weight,
        fontFamily = family,
        localeList = locales,
        hyphens = Hyphens.None,
        lineBreak = LineBreak.Paragraph,
    )

    val paragraph: TextStyle = text(1f, 1.625f, FontWeight.Normal)
    val title: TextStyle = text(1.5f, 2f, FontWeight.Normal).copy(lineBreak = LineBreak.Heading)
    val code: TextStyle = TextStyle(fontSize = base * 0.875f, lineHeight = base * 1.375f, fontFamily = FontFamily.Monospace)

    /** [level] 1 to 3; none reaches the title's size. */
    fun heading(level: Int): TextStyle = when (level) {
        1 -> text(1.375f, 1.75f, FontWeight.Normal)
        2 -> text(1.125f, 1.5f, FontWeight.Medium)
        else -> text(1f, 1.5f, FontWeight.Medium)
    }.copy(lineBreak = LineBreak.Heading)

    /**
     * The text with its inline styles; a link is colored and underlined, and a tap on it calls
     * [onLink]. Inline code gets its family here and its rounded background from the view drawing
     * it, which finds the ranges under [CODE_TAG]. A narrow space that does not break stands on each
     * side of inline code: the background's padding covers it, so the ordinary space next to it
     * still separates the code from the words around.
     */
    fun annotated(rich: RichText, linkColor: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
        val length = rich.text.length
        val pads = rich.spans.filter { it.style == Style.CODE && it.start.coerceIn(0, length) < it.end.coerceIn(0, length) }
            .flatMap { listOf(it.start.coerceIn(0, length), it.end.coerceIn(0, length)) }.sorted()
        var from = 0
        pads.forEach { at ->
            append(rich.text, from, at)
            append(CODE_PAD)
            from = at
        }
        append(rich.text, from, length)
        // A span starts after the padding put at its start and ends before the one put at its end
        fun startOf(offset: Int) = offset + pads.count { it <= offset }
        fun endOf(offset: Int) = offset + pads.count { it < offset }
        rich.spans.forEach { span ->
            val first = span.start.coerceIn(0, length)
            val last = span.end.coerceIn(first, length)
            if (first == last) return@forEach
            val start = startOf(first)
            val end = endOf(last)
            when (span.style) {
                Style.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                Style.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                Style.CODE -> {
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = base * 0.875f), start, end)
                    addStringAnnotation(CODE_TAG, "", start, end)
                }
                Style.SUPERSCRIPT -> addStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = base * 0.75f), start, end)
                Style.SUBSCRIPT -> addStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = base * 0.75f), start, end)
                Style.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                Style.LINK -> span.url?.let { url ->
                    // Never by color alone: underlined as well
                    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    addLink(LinkAnnotation.Clickable(url, styles) { onLink(url) }, start, end)
                }
            }
        }
    }
}

/** Marks the ranges of inline code in the text [ReaderStyle.annotated] makes. */
const val CODE_TAG = "code"

/** Narrow no-break space. */
private const val CODE_PAD = "\u202F"

/** What the blocks of one article need from the page around them. */
class ReaderScope(
    val style: ReaderStyle,
    /** Opens an address in the browser. */
    val open: (String) -> Unit,
    /** Shows a message (a snackbar). */
    val message: (String) -> Unit,
    /** Opens the image of this address full screen. */
    val showImage: (String) -> Unit,
)

val LocalReader = staticCompositionLocalOf<ReaderScope> { error("No reader") }
