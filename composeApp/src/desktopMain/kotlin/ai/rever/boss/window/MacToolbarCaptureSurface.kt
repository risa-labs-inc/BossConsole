package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import androidx.compose.ui.unit.IntRect
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlin.math.roundToInt

/** Borrowed identity and geometry, published together from AppKit; never sent to a viewer. */
internal data class MacToolbarCaptureSurface(
    val handle: Long,
    val windowNumber: Long,
    val bounds: IntRect,
)

internal fun nativeToolbarCaptureSurfaces(
    window: Pointer,
    items: Collection<Pointer>,
): List<MacToolbarCaptureSurface> {
    val views =
        items.mapNotNull { pointer(it, "view") } +
            (0L..2L).mapNotNull { pointer(window, "standardWindowButton:", it) }
    return views
        .filter { ownsNativeToolbarView(window, it) }
        .mapNotNull { pointer(it, "window") }
        .filter { it != window && number(it, "isVisible") != 0L }
        .distinct()
        .mapNotNull { child ->
            val root = nativeToolbarWindowFrame(window)
            val frame = nativeToolbarWindowFrame(child)
            val left = (frame[0] - root[0]).roundToInt()
            val top = (root[1] + root[3] - frame[1] - frame[3]).roundToInt()
            val width = frame[2].roundToInt()
            val height = frame[3].roundToInt()
            val windowNumber = number(child, "windowNumber")
            // Only the narrow chrome strip directly above/over the document, not arbitrary child windows.
            val header =
                left >= 0 && width > 0 && left + width <= root[2].roundToInt() &&
                    height in 1..256 && top in -256..0 && top + height in 0..256
            if (header && windowNumber > 0) {
                MacToolbarCaptureSurface(
                    Pointer.nativeValue(child),
                    windowNumber,
                    IntRect(left, top, left + width, top + height),
                )
            } else {
                null
            }
        }
}

private fun nativeToolbarWindowFrame(window: Pointer): DoubleArray =
    Memory(32).use { rect ->
        send(pointer(window, "valueForKey:", string("frame")), "getValue:size:", rect, 32L)
        DoubleArray(4) { rect.getDouble(it * 8L) }
    }
