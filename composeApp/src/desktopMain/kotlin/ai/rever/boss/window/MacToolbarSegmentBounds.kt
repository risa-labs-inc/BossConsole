package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import ai.rever.boss.window.MacToolbarRuntime.supports
import androidx.compose.ui.unit.IntRect
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlin.math.roundToInt

/** Accessibility exposes the actual segment rectangles, including menu buttons and automatic widths. */
@Suppress("ReturnCount") // Reject incomplete/ambiguous geometry before mapping any segment.
internal fun nativeToolbarSegmentBounds(view: Pointer, window: Pointer, count: Int): List<IntRect>? {
    if (count < 1 || number(view, "segmentCount").toInt() != count) return null
    val leaves = mutableListOf<Pointer>()
    collectToolbarSegments(view, window, leaves, 0)
    if (leaves.size != count) return null
    val rects = leaves.map { nativeToolbarAccessibilityBounds(it, window) ?: return null }
    val control = nativeAddressBounds(view, window) ?: return null
    val inside =
        rects.all {
            it.width > 0 && it.height > 0 && control.inflate(2).contains(it.topLeft) &&
                control.inflate(2).contains(
                    it.bottomRight -
                        androidx.compose.ui.unit
                            .IntOffset(1, 1),
                )
        }
    val distinct = rects.indices.all { index -> rects.drop(index + 1).none { rects[index].overlaps(it) } }
    return rects.takeIf { inside && distinct }
}

private fun collectToolbarSegments(
    view: Pointer,
    window: Pointer,
    leaves: MutableList<Pointer>,
    depth: Int,
) {
    if (depth > 4 || leaves.size > 16) return
    val name = if (supports(view, "accessibilityRole")) pointer(view, "accessibilityRole") else null
    val role = pointer(name, "UTF8String")?.getString(0)
    val owned =
        supports(view, "accessibilityWindow") &&
            ownsNativeToolbarWindow(window, pointer(view, "accessibilityWindow"))
    if (owned && role in setOf("AXButton", "AXMenuButton", "AXRadioButton", "AXCheckBox")) {
        leaves.add(view)
    } else if (supports(view, "accessibilityChildren")) {
        val children = pointer(view, "accessibilityChildren")
        for (index in 0 until number(children, "count").coerceAtMost(16)) {
            pointer(children, "objectAtIndex:", index)?.let { collectToolbarSegments(it, window, leaves, depth + 1) }
        }
    }
}

private fun nativeToolbarAccessibilityBounds(
    element: Pointer,
    window: Pointer,
): IntRect? {
    if (!supports(element, "accessibilityFrame")) return null
    return Memory(32).use { bytes ->
        send(pointer(element, "valueForKey:", string("accessibilityFrame")), "getValue:size:", bytes, 32L)
        val x = bytes.getDouble(0)
        val y = bytes.getDouble(8)
        val width = bytes.getDouble(16)
        val height = bytes.getDouble(24)
        send(pointer(window, "valueForKey:", string("frame")), "getValue:size:", bytes, 32L)
        val left = x - bytes.getDouble(0)
        val top = bytes.getDouble(8) + bytes.getDouble(24) - y - height
        IntRect(left.roundToInt(), top.roundToInt(), (left + width).roundToInt(), (top + height).roundToInt())
    }
}
