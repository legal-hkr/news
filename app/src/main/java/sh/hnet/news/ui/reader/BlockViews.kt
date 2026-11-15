@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.reader

import sh.hnet.news.ui.viewer.imageOrigin
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.content.Block
import sh.hnet.news.content.Cell
import sh.hnet.news.content.RichText
import sh.hnet.news.ui.components.MenuItem
import sh.hnet.news.ui.components.onHold

/** The column's side padding (SPEC §5.1). */
val READER_SIDE = 20.dp

/**
 * One block of an article (SPEC §5.3). Inside a list ([tight]) paragraphs sit closer. Code and
 * tables stay within the column, rounded on every corner, and scroll sideways inside it (the
 * owner's choice over the SPEC's run to the screen's edge).
 */
@Composable
fun BlockView(block: Block, color: Color = MaterialTheme.colorScheme.onSurface, tight: Boolean = false, level: Int = 0) {
    val reader = LocalReader.current
    when (block) {
        is Block.Paragraph -> RichTextView(block.text, reader.style.paragraph, color, Modifier.padding(bottom = if (tight) 4.dp else 16.dp))
        is Block.Heading -> {
            val (above, below) = when (block.level) {
                1 -> 24.dp to 12.dp
                2 -> 24.dp to 8.dp
                else -> 16.dp to 8.dp
            }
            RichTextView(block.text, reader.style.heading(block.level), color, Modifier.padding(top = above, bottom = below).semantics { heading() })
        }
        is Block.Image -> ImageView(block)
        is Block.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            // The last block's own space below would double the padding
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                block.blocks.forEach { BlockView(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        is Block.ItemList -> Column(Modifier.padding(bottom = if (tight) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    // Ordered lists keep their numbers; the second level of an unordered one uses a ring
                    val marker = if (block.ordered) "${block.start + index}." else if (level == 0) "•" else "◦"
                    Text(marker, style = reader.style.paragraph, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 24.dp).padding(end = 4.dp))
                    Column { item.forEach { BlockView(it, color, tight = true, level = level + 1) } }
                }
            }
        }
        is Block.Code -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(block.text, style = reader.style.code, softWrap = false, modifier = Modifier.sidewaysScroll().padding(horizontal = 16.dp, vertical = 12.dp))
        }
        // The surface hands its width down as the least one, so a narrow table fills it
        is Block.Table -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            TableView(block.rows, Modifier.sidewaysScroll())
        }
        Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
        is Block.Unsupported -> UnsupportedView(block)
    }
}

/**
 * Sideways scrolling of a wide block. What it has to scroll keeps the gesture to itself: a drag or
 * fling that reaches its end stops there instead of turning the reader's page to the next article.
 */
@Composable
private fun Modifier.sidewaysScroll(): Modifier {
    val state = rememberScrollState()
    val keep = remember(state) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (state.maxValue > 0) Offset(available.x, 0f) else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (state.maxValue > 0) Velocity(available.x, 0f) else Velocity.Zero
        }
    }
    return nestedScroll(keep).horizontalScroll(state)
}

/**
 * Text with its links: a tap opens the browser, press and hold shows the address with Copy and
 * Share (SPEC §5.7). Inline code sits on rounded areas drawn behind the text.
 */
@Composable
fun RichTextView(rich: RichText, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reader = LocalReader.current
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val text = remember(rich, reader.style, linkColor) { reader.style.annotated(rich, linkColor, reader.open) }
    val codes = remember(text) { text.getStringAnnotations(CODE_TAG, 0, text.length).map { it.start to it.end } }
    val links = rich.spans.any { it.url != null }
    if (codes.isEmpty() && !links) {
        Text(text, style = style, color = color, modifier = modifier)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val behind = if (codes.isEmpty()) Modifier else Modifier.drawBehind {
        layout?.let { measured ->
            codeAreas(measured, codes, CODE_AREA_PADDING.toPx(), CODE_AREA_INSET.toPx()).forEach {
                drawRoundRect(codeBackground, it.topLeft, it.size, CornerRadius(CODE_AREA_CORNER.toPx()))
            }
        }
    }
    if (!links) {
        Text(text, style = style, color = color, onTextLayout = { layout = it }, modifier = modifier.then(behind))
        return
    }
    var held by remember { mutableStateOf<String?>(null) }
    Box(modifier) {
        Text(
            text, style = style, color = color, onTextLayout = { layout = it },
            modifier = behind.onHold(text) { position ->
                val measured = layout ?: return@onHold false
                // Only on the link's own letters, not in the empty end of its line
                val line = measured.getLineForVerticalPosition(position.y)
                if (position.x > measured.getLineRight(line) || position.x < measured.getLineLeft(line)) return@onHold false
                val offset = measured.getOffsetForPosition(position)
                held = text.getLinkAnnotations(offset, offset).firstNotNullOfOrNull { (it.item as? LinkAnnotation.Clickable)?.tag }
                held != null
            },
        )
        LinkMenu(held) { held = null }
    }
}

private val CODE_AREA_PADDING = 3.dp
private val CODE_AREA_INSET = 2.dp
private val CODE_AREA_CORNER = 5.dp

/** The areas behind inline code, as in ComfyChair's notes: one for each line a span runs over, a little wider than its letters. */
private fun codeAreas(layout: TextLayoutResult, codes: List<Pair<Int, Int>>, padding: Float, inset: Float): List<Rect> {
    val length = layout.layoutInput.text.length
    return codes.flatMap { (first, last) ->
        val from = first.coerceIn(0, length)
        val to = last.coerceIn(from, length)
        if (from == to) return@flatMap emptyList()
        (layout.getLineForOffset(from)..layout.getLineForOffset(to - 1)).mapNotNull { line ->
            val start = maxOf(from, layout.getLineStart(line))
            val end = minOf(to, layout.getLineEnd(line, visibleEnd = true))
            if (end <= start) return@mapNotNull null
            val first = layout.getBoundingBox(start)
            val last = layout.getBoundingBox(end - 1)
            Rect(minOf(first.left, last.left) - padding, layout.getLineTop(line) + inset, maxOf(first.right, last.right) + padding, layout.getLineBottom(line) - inset)
        }
    }
}

@Composable
private fun LinkMenu(url: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Kept while the menu closes, so its lines don't empty on the way out
    var shown by remember { mutableStateOf("") }
    if (url != null) shown = url
    DropdownMenuPopup(expanded = url != null, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            DropdownMenuItem(
                text = { Text(shown, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(max = 240.dp)) },
                enabled = false,
                shape = MenuDefaults.itemShape(0, 3).shape,
                onClick = {},
            )
            MenuItem(stringResource(R.string.menu_copy_link), R.drawable.ic_content_copy, 1, 3) {
                onDismiss()
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, shown))
            }
            MenuItem(stringResource(R.string.button_share), R.drawable.ic_share, 2, 3) {
                onDismiss()
                shareText(context, null, shown)
            }
        }
    }
}

/** The system's share sheet with a link, and its title as the subject. */
fun shareText(context: Context, title: String?, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    title?.let { send.putExtra(Intent.EXTRA_SUBJECT, it).putExtra(Intent.EXTRA_TITLE, it) }
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * An image from the phone's store (SPEC §5.6), its space held from the stored size before it draws.
 * With a caption, the two share a card (the owner's choice of 15 Nov 2026; the SPEC had the caption
 * as plain text under the image).
 * It opens full screen by its button only: a thumb landing on it while scrolling opens nothing. One
 * that is not on the phone is a placeholder of the same size with "Fetch image".
 */
@Composable
private fun ImageView(block: Block.Image) {
    val reader = LocalReader.current
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val columnPx = minOf(LocalWindowInfo.current.containerSize.width, with(density) { 600.dp.roundToPx() })
    var attempt by remember { mutableIntStateOf(0) }
    var read by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, block.url, columnPx, attempt) {
        value = app.images.load(block.url, columnPx)?.asImageBitmap()
        read = true
    }
    val picture = bitmap
    val ratio = when {
        block.width > 0 && block.height > 0 -> block.width.toFloat() / block.height
        picture != null -> picture.width.toFloat() / picture.height
        else -> 16f / 9f
    }
    // A small picture (an icon, a badge) keeps its own size instead of being blown up to the column. It holds
    // that size before it draws too: shrinking once loaded would pull the text back under a scrolling thumb
    val natural: Dp? = block.width.takeIf { it in 1 until columnPx / 2 }?.let { with(density) { it.toDp() } }
    val noNetwork = stringResource(R.string.error_no_network)
    val notFetched = stringResource(R.string.msg_image_not_fetched)
    val caption = block.caption?.takeIf { it.text.isNotBlank() }
    // An image with a caption stands with it on one card, so the caption reads as the image's and not as the article's next line
    val card = if (caption != null) Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(4.dp) else Modifier
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).then(card)) {
        Box(
            (if (natural != null && !(read && picture == null)) Modifier.width(natural) else Modifier.fillMaxWidth())
                .aspectRatio(ratio.coerceIn(0.4f, 6f))
                // Where the viewer's flight starts and lands; only an image that opens in it
                .imageOrigin(block.url.takeIf { picture != null && natural == null }, 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (picture != null) {
                Image(picture, contentDescription = block.alt, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                if (natural == null) {
                    FilledTonalIconButton(onClick = { reader.showImage(block.url) }, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                        Icon(painterResource(R.drawable.ic_open_in_full), contentDescription = stringResource(R.string.content_description_image_full_screen))
                    }
                }
            } else if (read) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.msg_image_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    if (fetching) {
                        Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
                    } else {
                        TextButton(onClick = {
                            if (context.getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
                                reader.message(noNetwork)
                            } else {
                                fetching = true
                                scope.launch {
                                    val fetched = app.images.fetch(block.url) != null
                                    fetching = false
                                    if (fetched) attempt++ else reader.message(notFetched)
                                }
                            }
                        }) { Text(stringResource(R.string.button_fetch_image)) }
                    }
                }
            }
        }
        if (caption != null) {
            RichTextView(caption, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp))
        }
    }
}

/** What the app does not draw itself: a card naming the kind, which opens in the browser. */
@Composable
private fun UnsupportedView(block: Block.Unsupported) {
    val reader = LocalReader.current
    val (icon, label) = when (block.kind) {
        Block.Kind.VIDEO -> R.drawable.ic_movie to R.string.label_video
        Block.Kind.AUDIO -> R.drawable.ic_volume_up to R.string.label_audio
        Block.Kind.SOCIAL -> R.drawable.ic_forum to R.string.label_social_post
        Block.Kind.FRAME -> R.drawable.ic_web_asset to R.string.label_embedded
    }
    val url = block.url
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                if (url != null) Text(stringResource(R.string.msg_opens_in_browser), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (url != null) Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    if (url != null) {
        Surface(onClick = { reader.open(url) }, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier, content = content)
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier, content = content)
    }
}

/**
 * A table (SPEC §5.3): columns as wide as their widest cell, up to 240 dp, where a cell starts to
 * wrap, and wider when the table would not fill its place; rows as tall as their tallest cell; header cells on a higher tone; a line between rows.
 */
@Composable
private fun TableView(rows: List<List<Cell>>, modifier: Modifier = Modifier) {
    val reader = LocalReader.current
    val columns = rows.maxOfOrNull { it.size } ?: return
    val line = MaterialTheme.colorScheme.outlineVariant
    val headerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Layout(
        modifier = modifier,
        content = {
            rows.forEachIndexed { rowIndex, row ->
                repeat(columns) { column ->
                    val cell = row.getOrNull(column)
                    Box(
                        Modifier
                            .then(if (cell?.header == true) Modifier.background(headerColor) else Modifier)
                            .drawBehind {
                                if (rowIndex < rows.lastIndex) drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (cell != null) {
                            val style = if (cell.header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
                            RichTextView(cell.text, style.copy(fontSize = style.fontSize * (reader.style.base.value / 16f), lineHeight = style.lineHeight * (reader.style.base.value / 16f)), MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val widest = (240.dp + 32.dp).roundToPx()
        val widths = IntArray(columns) { column ->
            rows.indices.maxOf { row -> measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity) }.coerceAtMost(widest)
        }
        // A table narrower than its place is spread over it, each column by its share
        val sum = widths.sum()
        if (sum in 1 until constraints.minWidth) {
            val extra = constraints.minWidth - sum
            for (column in 0 until columns) widths[column] += extra * widths[column] / sum
            widths[columns - 1] += constraints.minWidth - widths.sum()
        }
        val heights = IntArray(rows.size) { row ->
            (0 until columns).maxOf { column -> measurables[row * columns + column].minIntrinsicHeight(widths[column]) }
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(widths[index % columns], heights[index / columns]))
        }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            rows.indices.forEach { row ->
                var x = 0
                repeat(columns) { column ->
                    placeables[row * columns + column].placeRelative(x, y)
                    x += widths[column]
                }
                y += heights[row]
            }
        }
    }
}
