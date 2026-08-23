package ai.rever.boss.components.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the axis-aware insertion index behind tab reorder.
 *
 * The whole point of the shared function is that a left bar and a top bar differ ONLY in which
 * coordinate is read, so most cases below are asserted on both axes from the same geometry,
 * transposed. A case that passes on one axis and not the other is exactly the bug this replaced.
 */
class ReorderIndexTest {
    /** Three 100x40 tabs laid out left to right, indices 0..2. */
    private val horizontalTabs =
        listOf(
            TabBoundInfo(Rect(left = 0f, top = 0f, right = 100f, bottom = 40f), actualIndex = 0),
            TabBoundInfo(Rect(left = 100f, top = 0f, right = 200f, bottom = 40f), actualIndex = 1),
            TabBoundInfo(Rect(left = 200f, top = 0f, right = 300f, bottom = 40f), actualIndex = 2),
        )

    /** The same three tabs transposed: 40x100, stacked top to bottom. */
    private val verticalTabs =
        listOf(
            TabBoundInfo(Rect(left = 0f, top = 0f, right = 40f, bottom = 100f), actualIndex = 0),
            TabBoundInfo(Rect(left = 0f, top = 100f, right = 40f, bottom = 200f), actualIndex = 1),
            TabBoundInfo(Rect(left = 0f, top = 200f, right = 40f, bottom = 300f), actualIndex = 2),
        )

    private fun horizontal(x: Float) = reorderIndexFor(horizontalTabs, Offset(x, 20f), vertical = false)

    private fun vertical(y: Float) = reorderIndexFor(verticalTabs, Offset(20f, y), vertical = true)

    @Test
    fun `before the first tab inserts at zero`() {
        assertEquals(0, horizontal(10f))
        assertEquals(0, vertical(10f))
    }

    @Test
    fun `past a tab's midpoint moves to the next slot`() {
        // 49 is still in the first tab's leading half, 51 is past its centre.
        assertEquals(0, horizontal(49f))
        assertEquals(1, horizontal(51f))
        assertEquals(0, vertical(49f))
        assertEquals(1, vertical(51f))
    }

    @Test
    fun `between two tabs inserts between them`() {
        assertEquals(2, horizontal(160f))
        assertEquals(2, vertical(160f))
    }

    @Test
    fun `past the last tab inserts one beyond the end`() {
        assertEquals(3, horizontal(290f))
        assertEquals(3, vertical(290f))
    }

    @Test
    fun `an empty bar inserts at zero`() {
        assertEquals(0, reorderIndexFor(emptyList(), Offset(500f, 500f), vertical = false))
        assertEquals(0, reorderIndexFor(emptyList(), Offset(500f, 500f), vertical = true))
    }

    @Test
    fun `the cross axis is ignored`() {
        // Far outside the bar across its short axis. The caller (checkEdgeScroll, and the tab-bar
        // bounds test in updateDropTarget) decides whether the pointer is over the bar at all;
        // this only answers where along it.
        assertEquals(1, reorderIndexFor(horizontalTabs, Offset(120f, 9000f), vertical = false))
        assertEquals(1, reorderIndexFor(verticalTabs, Offset(9000f, 120f), vertical = true))
    }

    @Test
    fun `virtualised bar returns the actual index, not the position in the list`() {
        // A scrolled lazy list only registers what it composed, so the FIRST entry here is the
        // list's fifth tab. Returning a position in `tabs` would reorder to slot 0 and move the
        // wrong tab - the exact case TabBoundInfo.actualIndex exists for.
        val scrolled =
            listOf(
                TabBoundInfo(Rect(0f, 0f, 40f, 100f), actualIndex = 5),
                TabBoundInfo(Rect(0f, 100f, 40f, 200f), actualIndex = 6),
            )
        assertEquals(5, reorderIndexFor(scrolled, Offset(20f, 10f), vertical = true))
        assertEquals(6, reorderIndexFor(scrolled, Offset(20f, 110f), vertical = true))
        assertEquals(7, reorderIndexFor(scrolled, Offset(20f, 190f), vertical = true))
    }

    @Test
    fun `registration order does not matter`() {
        // Lazy lists reuse slots, so the map this comes from is in no meaningful order. Position
        // on screen is the ordering, which is why the function sorts rather than trusting input.
        assertEquals(1, reorderIndexFor(verticalTabs.reversed(), Offset(20f, 60f), vertical = true))
        assertEquals(1, reorderIndexFor(horizontalTabs.reversed(), Offset(60f, 20f), vertical = false))
    }
}
