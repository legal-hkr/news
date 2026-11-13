package sh.hnet.news.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Press and hold on something whose taps belong to its own content (a text with links). It watches
 * on the way down, as [dragAfterLongPress] does, so the content's own handling takes nothing from
 * it; when [onHold] takes the hold (returns true), the rest of the touch is consumed and the
 * release is not a tap.
 */
fun Modifier.onHold(key: Any?, onHold: (Offset) -> Boolean): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val ended = withTimeoutOrNull<Boolean>(viewConfiguration.longPressTimeoutMillis) {
            var over = false
            while (!over) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                over = change == null || !change.pressed || (change.position - down.position).getDistance() > slop
            }
            true
        }
        if (ended != null || !onHold(down.position)) return@awaitEachGesture
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
        }
    }
}
