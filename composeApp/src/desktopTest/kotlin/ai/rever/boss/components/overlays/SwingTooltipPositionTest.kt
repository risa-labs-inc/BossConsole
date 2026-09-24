package ai.rever.boss.components.overlays

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The native tooltip must never open under the pointer: covering it ends the hover, which hides
 * the tooltip, which restores the hover, and the tooltip blinks. That is what a bottom-bar item
 * next to the screen's bottom edge did while the position was clamped rather than flipped.
 */
class SwingTooltipPositionTest {
    private val screen = Rectangle(0, 25, 1512, 957) // menu bar inset on top
    private val size = Dimension(420, 24)

    private fun coversCursor(
        pos: Point,
        cursor: Point,
    ) = Rectangle(pos, size).contains(cursor)

    @Test
    fun `below-right of the cursor when it fits`() {
        assertEquals(Point(112, 318), tooltipScreenPosition(Point(100, 300), size, screen))
    }

    @Test
    fun `a pointer in the bottom bar flips the tooltip above instead of onto it`() {
        for (y in 960..981) {
            val cursor = Point(1300, y)
            val pos = tooltipScreenPosition(cursor, size, screen)
            assertFalse(coversCursor(pos, cursor), "tooltip covers the pointer at y=$y: $pos")
            assertEquals(y - 8 - 24, pos.y)
        }
    }

    @Test
    fun `a pointer at the right edge flips the tooltip to the left`() {
        val cursor = Point(1500, 400)
        val pos = tooltipScreenPosition(cursor, size, screen)
        assertFalse(coversCursor(pos, cursor))
        assertEquals(1500 - 8 - 420, pos.x)
    }
}
