package sh.hnet.news.ui.articles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.news.Look
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.data.ArticleRow
import sh.hnet.news.data.ArticleState
import sh.hnet.news.data.Feed
import sh.hnet.news.ui.components.rememberFeedIcon

private val THUMBNAIL = 64.dp
private const val UNREAD_DOT = "dot"

/**
 * An article's row (SPEC §4.2, §4.10). Two parts stacked: the text – title, then the preview lines –
 * with the thumbnail beside it when the look has one; underneath, across the full width, the meta
 * line: feed at the start, marks and time at the end, the same in every row. The three looks differ
 * only by the image, and a row whose image is not on the phone is drawn as Text.
 *
 * @param time The time as this list shows it (the time of day under a day header)
 * @param onClick Null for a row that is only shown (the sample in settings)
 */
@Composable
fun ArticleRowItem(row: ArticleRow, look: Look, previewLines: Int, time: String, shape: Shape, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val article = row.article
    val app = LocalContext.current.applicationContext as NewsApplication
    // Whether the image is on the phone decides the layout, so it is asked at once (a file check); the picture itself is read off the main thread
    val imageUrl = article.imageUrl?.takeIf { look != Look.TEXT }
    val stored = remember(imageUrl, article.state) { imageUrl != null && app.images.has(imageUrl) }
    val unread = stringResource(R.string.label_unread)
    val read = stringResource(R.string.label_read)
    val content = @Composable {
        Column {
            if (stored && look == Look.LARGE) {
                StoredImage(imageUrl, widthPx = with(LocalDensity.current) { 360.dp.roundToPx() }, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.heightIn(min = if (stored && look == Look.THUMBNAIL) THUMBNAIL else 0.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Title(article.title, article.read)
                        if (previewLines > 0 && !article.preview.isNullOrBlank()) {
                            Text(article.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = previewLines, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (stored && look == Look.THUMBNAIL) {
                        StoredImage(imageUrl, widthPx = with(LocalDensity.current) { THUMBNAIL.roundToPx() }, Modifier.size(THUMBNAIL).clip(MaterialTheme.shapes.medium))
                    }
                }
                Spacer(Modifier.size(6.dp))
                Meta(row, time)
            }
        }
    }
    val semantics = modifier.fillMaxWidth().semantics { stateDescription = if (article.read) read else unread }
    val color = MaterialTheme.colorScheme.surfaceContainer
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = color, modifier = semantics, content = content)
    else Surface(shape = shape, color = color, modifier = semantics, content = content)
}

/** Unread: medium weight with a dot before the first word; read: regular weight in the variant color. Three lines, then an ellipsis. */
@Composable
private fun Title(title: String, read: Boolean) {
    val style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = if (read) FontWeight.Normal else FontWeight.Medium)
    val dot = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            if (!read) appendInlineContent(UNREAD_DOT, " ")
            append(title)
        },
        inlineContent = if (read) emptyMap() else mapOf(
            UNREAD_DOT to InlineTextContent(Placeholder(16.sp, 8.sp, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
            },
        ),
        style = style,
        color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The feed's icon and name at the start; the completeness mark (§4.3) and the time at the end. */
@Composable
private fun Meta(row: ArticleRow, time: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodySmall
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallFeedIcon(row.article.feedId, row.feedHasIcon)
        Text(row.feedTitle, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val mark = when (row.article.state) {
            ArticleState.FETCHING -> R.drawable.ic_downloading to R.string.label_fetching
            ArticleState.INCOMPLETE, ArticleState.NOT_EXTRACTED -> R.drawable.ic_cloud_off to R.string.label_incomplete
            else -> null
        }
        if (mark != null) {
            Icon(painterResource(mark.first), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(stringResource(mark.second), style = style, color = color, maxLines = 1)
            Spacer(Modifier.width(2.dp))
        }
        Text(time, style = style, color = color, maxLines = 1)
    }
}

/** A feed's icon at 16 dp; without one, the feed symbol with no box (SPEC §6.3). */
@Composable
fun SmallFeedIcon(feedId: Long, hasIcon: Boolean) {
    // Only the id and the flag matter to the icon's file
    val icon = rememberFeedIcon(remember(feedId, hasIcon) { Feed(id = feedId, url = "", title = "", hasIcon = hasIcon) })
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
    } else {
        Icon(painterResource(R.drawable.ic_rss_feed), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** An image from the phone's store, cropped to fill; its place is held in a neutral tone while it is read. */
@Composable
private fun StoredImage(url: String?, widthPx: Int, modifier: Modifier) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val bitmap by produceState<ImageBitmap?>(null, url, widthPx) {
        value = url?.let { app.images.load(it, widthPx)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}
