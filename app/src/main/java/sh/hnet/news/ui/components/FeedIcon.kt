package sh.hnet.news.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.data.Feed

/**
 * A feed's icon in the rows' tonal box (SPEC §6.2–6.3): the stored icon of the feed's site, or the
 * feed symbol when there is none. [feed] null draws the stand-in (a feed not subscribed yet).
 */
@Composable
fun FeedIconBox(feed: Feed?, size: Dp = 40.dp) {
    val icon = rememberFeedIcon(feed)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(size * 0.6f).clip(RoundedCornerShape(4.dp)))
        } else {
            Icon(painterResource(R.drawable.ic_rss_feed), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** The stored icon, read off the main thread; null until read and when there is none. */
@Composable
fun rememberFeedIcon(feed: Feed?): ImageBitmap? {
    val app = LocalContext.current.applicationContext as NewsApplication
    val icon by produceState<ImageBitmap?>(null, feed?.id, feed?.hasIcon) {
        value = if (feed == null || !feed.hasIcon) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(app.icons.file(feed.id).path)?.asImageBitmap()
        }
    }
    return icon
}
