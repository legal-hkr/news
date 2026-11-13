package sh.hnet.news.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An article's body as the reader draws it (SPEC §5.3): a short list of block kinds, made once from
 * the feed's markup or the article's page and stored as JSON. No markup survives: what the app
 * doesn't show natively becomes [Unsupported], which opens in the browser.
 */
@Serializable
sealed interface Block {
    /** [level] 1 to 3, whatever the source's own levels were. */
    @Serializable @SerialName("h") data class Heading(val level: Int, val text: RichText) : Block
    @Serializable @SerialName("p") data class Paragraph(val text: RichText) : Block
    /** [width] and [height] in pixels once the image is stored (space is reserved before it draws), 0 when unknown. */
    @Serializable @SerialName("img") data class Image(val url: String, val width: Int = 0, val height: Int = 0, val caption: RichText? = null, val alt: String? = null) : Block
    @Serializable @SerialName("quote") data class Quote(val blocks: List<Block>) : Block
    @Serializable @SerialName("list") data class ItemList(val ordered: Boolean, val start: Int = 1, val items: List<List<Block>>) : Block
    @Serializable @SerialName("code") data class Code(val text: String) : Block
    @Serializable @SerialName("table") data class Table(val rows: List<List<Cell>>) : Block
    @Serializable @SerialName("hr") data object Rule : Block
    @Serializable @SerialName("x") data class Unsupported(val kind: Kind, val url: String?) : Block

    enum class Kind { VIDEO, AUDIO, SOCIAL, FRAME }
}

@Serializable
data class Cell(val text: RichText, val header: Boolean = false)

/** Text with its inline styles as ranges. */
@Serializable
data class RichText(val text: String, val spans: List<Span> = emptyList())

/** [url] for a link. */
@Serializable
data class Span(val start: Int, val end: Int, val style: Style, val url: String? = null)

enum class Style { BOLD, ITALIC, CODE, SUPERSCRIPT, SUBSCRIPT, STRIKE, LINK }

object Blocks {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }

    fun encode(blocks: List<Block>): String = json.encodeToString(blocks)

    /** What can't be read (written by a later version) is no content rather than a crash. */
    fun decode(text: String?): List<Block> = if (text == null) emptyList() else try {
        json.decodeFromString<List<Block>>(text)
    } catch (_: Exception) {
        emptyList()
    }

    /** All text, blocks apart by a space: for the preview, the word count and search. */
    fun text(blocks: List<Block>): String = StringBuilder().also { builder -> blocks.forEach { add(builder, it) } }.toString().trim()

    private fun add(to: StringBuilder, block: Block) {
        when (block) {
            is Block.Heading -> to.append(block.text.text).append(' ')
            is Block.Paragraph -> to.append(block.text.text).append(' ')
            is Block.Image -> block.caption?.let { to.append(it.text).append(' ') }
            is Block.Quote -> block.blocks.forEach { add(to, it) }
            is Block.ItemList -> block.items.forEach { item -> item.forEach { add(to, it) } }
            is Block.Code -> to.append(block.text).append(' ')
            is Block.Table -> block.rows.forEach { row -> row.forEach { to.append(it.text.text).append(' ') } }
            Block.Rule, is Block.Unsupported -> Unit
        }
    }

    /** Every image, nested ones too, in reading order. */
    fun images(blocks: List<Block>): List<Block.Image> = blocks.flatMap { block ->
        when (block) {
            is Block.Image -> listOf(block)
            is Block.Quote -> images(block.blocks)
            is Block.ItemList -> block.items.flatMap { images(it) }
            else -> emptyList()
        }
    }

    /** The same blocks with each image passed through [change]. */
    fun mapImages(blocks: List<Block>, change: (Block.Image) -> Block.Image): List<Block> = blocks.map { block ->
        when (block) {
            is Block.Image -> change(block)
            is Block.Quote -> block.copy(blocks = mapImages(block.blocks, change))
            is Block.ItemList -> block.copy(items = block.items.map { mapImages(it, change) })
            else -> block
        }
    }

    /** The same blocks without the images of [urls]. */
    fun withoutImages(blocks: List<Block>, urls: Set<String>): List<Block> = if (urls.isEmpty()) blocks else blocks.mapNotNull { block ->
        when (block) {
            is Block.Image -> block.takeIf { it.url !in urls }
            is Block.Quote -> block.copy(blocks = withoutImages(block.blocks, urls))
            is Block.ItemList -> block.copy(items = block.items.map { withoutImages(it, urls) })
            else -> block
        }
    }

    fun words(blocks: List<Block>): Int = text(blocks).split(Regex("""\s+""")).count { it.isNotEmpty() }
}
