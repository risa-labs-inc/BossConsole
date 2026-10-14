package ai.rever.boss.components.overlays

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A tooltip card that overlaps its own anchor ends the hover that is showing it, so it blinks on
 * and off under a resting pointer. These pin that the card never lands on the anchor, including
 * against the window edges where a bottom-bar item actually lives.
 */
class TooltipPositionTest {
    private val window = IntSize(1000, 800)

    private fun overlaps(
        pos: IntOffset,
        popup: IntSize,
        anchor: IntRect,
    ) = IntRect(pos.x, pos.y, pos.x + popup.width, pos.y + popup.height).overlaps(anchor)

    @Test
    fun `top placement sits above a bottom-bar item without covering it`() {
        val anchor = IntRect(900, 776, 990, 800)
        val popup = IntSize(300, 24)
        val pos = tooltipPosition(TooltipPlacement.TOP, anchor, window, popup)
        assertFalse(overlaps(pos, popup, anchor))
        assertEquals(776 - 24 - 6, pos.y)
        // Clamped inside the window rather than centred off its right edge.
        assertEquals(1000 - 300, pos.x)
    }

    @Test
    fun `top placement flips below when there is no room above`() {
        val anchor = IntRect(10, 0, 60, 20)
        val popup = IntSize(100, 24)
        val pos = tooltipPosition(TooltipPlacement.TOP, anchor, window, popup)
        assertFalse(overlaps(pos, popup, anchor))
        assertEquals(26, pos.y)
    }

    @Test
    fun `end placement sits beside the anchor and flips at the window edge`() {
        val popup = IntSize(120, 24)
        val rail = IntRect(0, 100, 40, 140)
        assertEquals(IntOffset(46, 108), tooltipPosition(TooltipPlacement.END, rail, window, popup))
        val edge = IntRect(960, 100, 1000, 140)
        val pos = tooltipPosition(TooltipPlacement.END, edge, window, popup)
        assertFalse(overlaps(pos, popup, edge))
    }

    @Test
    fun `a flipped card is still clamped inside a short window`() {
        val short = IntSize(400, 40)
        val anchor = IntRect(10, 0, 60, 20)
        val popup = IntSize(100, 24)
        val pos = tooltipPosition(TooltipPlacement.TOP, anchor, short, popup)
        assertEquals(16, pos.y) // 40 - 24, not anchor.bottom + gap = 26
    }
}
