package sh.hnet.news.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.news.NewsApplication
import kotlin.math.roundToInt

/** The image flying between its place in the article and the viewer: a still, cropped to a moving rect, over a fading backdrop. */
private class ImageFlight(
    val image: ImageBitmap,
    val from: Rect,
    val to: Rect,
    val fromCornerPx: Float,
    val toCornerPx: Float,
    val fromBackdrop: Float,
    val toBackdrop: Float,
) {
    val progress = Animatable(0f)

    suspend fun run() {
        progress.animateTo(1f, tween(FLIGHT_MS, easing = StandardEasing))
    }
}

private const val FLIGHT_MS = 350

/** Longest wait, after landing, for the viewer to show the image before it takes over from the still. */
private const val LANDING_WAIT_MS = 500L

/**
 * The image viewer over the reader, as ComfyChair's viewer over its gallery: it flies the image out
 * of its place in the article ([imageOrigin]) on open and back into it on close – from wherever a
 * swipe down left it – and fades instead when that place is not on screen (the viewer was paged to
 * another image, the article was scrolled).
 */
@Composable
fun ImageViewerHost(urls: List<String>, start: Int, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val origins = LocalImageOrigins.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var flight by remember { mutableStateOf<ImageFlight?>(null) }
    // The viewer itself is visible (not while the still flies)
    var shown by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(urls[start.coerceIn(0, urls.lastIndex)]) }
    // The addresses whose picture the viewer has drawn
    val loaded = remember { mutableStateOf(emptySet<String>()) }
    // The width the article draws its images at, so the still comes from the same decoded picture
    val stillWidth = minOf(LocalWindowInfo.current.containerSize.width, with(density) { 600.dp.roundToPx() })
    val still by produceState<Bitmap?>(null, current, stillWidth) { value = app.images.load(current, stillWidth) }

    // The image's place stays empty while it is in the viewer or flying
    SideEffect { origins.hiddenKey = if (shown || flight != null) current else null }
    DisposableEffect(origins) { onDispose { origins.hiddenKey = null } }

    // Fly out of the article on open
    LaunchedEffect(Unit) {
        val key = current
        val origin = origins[key]
        val picture = app.images.load(key, stillWidth)
        val screen = snapshotFlow { size }.first { it != IntSize.Zero }
        if (origin != null && origin.visible && picture != null && picture.height > 0) {
            val out = ImageFlight(
                image = picture.asImageBitmap(),
                from = origin.bounds,
                to = Rect(Offset.Zero, screen.toSize()).fitted(picture.width.toFloat() / picture.height),
                fromCornerPx = origin.cornerPx, toCornerPx = 0f, fromBackdrop = 0f, toBackdrop = 1f,
            )
            flight = out
            out.run()
            // Closed meanwhile (Back during the flight): the flight back has taken over
            if (flight !== out) return@LaunchedEffect
            // Let the viewer show the image under the still before it goes, so nothing blinks
            withTimeoutOrNull(LANDING_WAIT_MS) { snapshotFlow { key in loaded.value }.first { it } }
            if (flight !== out) return@LaunchedEffect
            shown = true
            withFrameNanos { }
            if (flight === out) flight = null
        } else {
            shown = true
        }
    }

    // Fly back into the article, from where the swipe left the image
    val requestClose: (ViewerCloseFrom) -> Boolean = requestClose@{ from ->
        val origin = origins[current]
        val picture = still
        if (origin == null || !origin.visible || picture == null || picture.height <= 0 || size == IntSize.Zero) return@requestClose false
        val screen = Rect(Offset.Zero, size.toSize())
        val back = ImageFlight(
            image = picture.asImageBitmap(),
            from = screen.fitted(picture.width.toFloat() / picture.height).transformed(screen.center, from.scale, from.offsetY),
            to = origin.bounds,
            fromCornerPx = from.cornerPx, toCornerPx = origin.cornerPx, fromBackdrop = from.backdropAlpha, toBackdrop = 0f,
        )
        flight = back
        shown = false
        scope.launch {
            back.run()
            // The flight stays until the viewer is gone, then the article shows the image again in its place
            onClose()
        }
        true
    }

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (shown) 1f else 0f }) {
            ImageViewerScreen(
                urls, start, onClose, onMessage,
                onPage = { current = it },
                onLoaded = { loaded.value += it },
                onRequestClose = requestClose,
            )
        }
        flight?.let { ImageFlightLayer(it) }
    }
}

@Composable
private fun ImageFlightLayer(flight: ImageFlight) {
    val progress = flight.progress.value
    val rect = lerp(flight.from, flight.to, progress)
    val corner = with(LocalDensity.current) { lerp(flight.fromCornerPx, flight.toCornerPx, progress).toDp() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = lerp(flight.fromBackdrop, flight.toBackdrop, progress))))
    Image(
        bitmap = flight.image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
            }
            .clip(RoundedCornerShape(corner)),
    )
}

/** The rect after the swipe's transform of the viewer: scaled about [pivot], then moved down by [offsetY]. */
private fun Rect.transformed(pivot: Offset, scale: Float, offsetY: Float): Rect = Rect(
    left = pivot.x + (left - pivot.x) * scale,
    top = pivot.y + (top - pivot.y) * scale + offsetY,
    right = pivot.x + (right - pivot.x) * scale,
    bottom = pivot.y + (bottom - pivot.y) * scale + offsetY,
)
