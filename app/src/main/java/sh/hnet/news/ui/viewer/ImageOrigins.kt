package sh.hnet.news.ui.viewer

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize

/**
 * Where an image is shown in an article, so the viewer can fly it out of there on open and back
 * into it on close (as ComfyChair's media origins).
 *
 * @param bounds Full bounds in root coordinates, also the parts scrolled out of view
 * @param visible Whether any of it is on screen; a hidden origin gets a fade instead of a flight
 */
class ImageOrigin(val bounds: Rect, val visible: Boolean, val cornerPx: Float)

/** The origins on screen by the image's address, and the one hidden while its image is in the viewer. */
class ImageOrigins {
    // Read only when the viewer opens or closes, so a plain map: no recomposition while scrolling
    private val origins = HashMap<String, ImageOrigin>()

    /** The origin whose image is lifted into the viewer: it shows as an empty place meanwhile. */
    var hiddenKey by mutableStateOf<String?>(null)

    operator fun get(key: String): ImageOrigin? = origins[key]

    internal fun put(key: String, origin: ImageOrigin) {
        origins[key] = origin
    }

    internal fun remove(key: String) {
        origins.remove(key)
    }
}

val LocalImageOrigins = staticCompositionLocalOf { ImageOrigins() }

/**
 * Marks this element as the origin of the image with [key] for the viewer's flight, and hides it
 * while the image is in the viewer. The element must show the image filling it.
 *
 * @param corner Corner radius of the element
 */
fun Modifier.imageOrigin(key: String?, corner: Dp): Modifier = if (key == null) this else composed {
    val origins = LocalImageOrigins.current
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    DisposableEffect(origins, key) { onDispose { origins.remove(key) } }
    onGloballyPositioned { coordinates ->
        val bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        origins.put(key, ImageOrigin(bounds, !coordinates.boundsInRoot().isEmpty, cornerPx))
    }.graphicsLayer { alpha = if (origins.hiddenKey == key) 0f else 1f }
}

/** This rect's largest centred sub-rect of [aspect] (width / height). */
internal fun Rect.fitted(aspect: Float): Rect {
    if (aspect <= 0f || width <= 0f || height <= 0f) return this
    val size = if (width / height > aspect) Size(height * aspect, height) else Size(width, width / aspect)
    return Rect(Offset(center.x - size.width / 2f, center.y - size.height / 2f), size)
}
