package ai.rever.boss.components.model

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for the axis-aware insertion index behind tab reorder, and for the CROSS-PANE drop that
 * shares it.
 *
 * The whole point of the shared function is that a left bar and a top bar differ ONLY in which
 * coordinate is read, so most cases below are asserted on both axes from the same geometry,
 * transposed. A case that passes on one axis and not the other is exactly the bug this replaced.
 *
 * The cross-pane cases go through [TabDraggableComponent] rather than the pure function, because
 * what is worth pinning there is not the arithmetic - it is that a drop into a pane which is not
 * the drag's source names a slot at all, that the slot is read off the TARGET pane's rectangles,
 * and that a pane with nothing measured answers null so the drop appends instead of landing at 0.
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

    // --- Cross-pane drops ---

    private data class StubTabInfo(
        override val id: String,
        override val typeId: TabTypeId = TabTypeId("reorder-test", "test.plugin"),
        override val title: String = "Tab",
    ) : TabInfo {
        override val icon get() = Icons.Outlined.Language
    }

    /**
     * One window-level vertical bar carved into two pane slices, stacked.
     *
     * Pane "a" owns y 0..300 with three 100dp-tall rows; pane "b" owns y 300..600 with two.
     * Registered exactly the way `RegisterGroupBounds` does it - a slice per pane, and tab
     * rectangles keyed `panelId:tabId` carrying each tab's model index.
     */
    private fun twoPaneBar(measureB: Boolean = true): TabDraggableComponent =
        TabDraggableComponent().apply {
            registerTabBarBounds("a", Rect(0f, 0f, 40f, 300f), vertical = true)
            registerTabBarBounds("b", Rect(0f, 300f, 40f, 600f), vertical = true)
            repeat(3) { i ->
                registerTabBounds("a:a$i", Rect(0f, i * 100f, 40f, (i + 1) * 100f), actualIndex = i)
            }
            if (measureB) {
                repeat(2) { i ->
                    registerTabBounds("b:b$i", Rect(0f, 300f + i * 100f, 40f, 400f + i * 100f), actualIndex = i)
                }
            }
        }

    private fun TabDraggableComponent.dragFromAtoB(y: Float): TabDropTarget? {
        // Start on pane a's first row, then move to (20, y). The delta is what the component
        // adds to the start position, and updateDrag is throttled - the first call always lands
        // because the last-update stamp starts at zero.
        startDragging(StubTabInfo("a0"), panelId = "a", index = 0, startPosition = Offset(20f, 50f))
        updateDrag(Offset(0f, y - 50f))
        return dropTarget
    }

    @Test
    fun `a drop into another pane names the slot above the row it is over`() {
        // 340 is in pane b's first row, above its midpoint (350).
        val target = twoPaneBar().dragFromAtoB(340f)

        assertIs<TabDropTarget.ExistingPanel>(target)
        assertEquals("b", target.panelId)
        assertEquals(0, target.targetIndex)
    }

    @Test
    fun `past a row's midpoint the slot is below it`() {
        val target = twoPaneBar().dragFromAtoB(360f)

        assertIs<TabDropTarget.ExistingPanel>(target)
        assertEquals(1, target.targetIndex)
    }

    @Test
    fun `the empty space below the last row is the end of the list`() {
        // Pane b's slice runs to 600 but its rows stop at 500 - the remainder under the last row
        // is a place a tab can be dropped, and it means "after everything".
        val target = twoPaneBar().dragFromAtoB(560f)

        assertIs<TabDropTarget.ExistingPanel>(target)
        assertEquals(2, target.targetIndex, "one past the last tab, the same insertion index a reorder uses")
    }

    @Test
    fun `the index comes from the target pane's rows, not the source's`() {
        // Read against pane a's rectangles, y = 340 would be past every one of them and answer 3.
        // The bug this pins is computing the slot from the bar the drag STARTED in.
        val target = twoPaneBar().dragFromAtoB(340f)

        assertIs<TabDropTarget.ExistingPanel>(target)
        assertEquals(0, target.targetIndex)
    }

    @Test
    fun `a pane with nothing measured carries no position`() {
        // Registered as a drop target but with no tab rectangles - a bar mid-layout, or a pane
        // drawing no bar at all. Null appends; 0 would land the tab at the head of a list the
        // user never pointed at.
        val target = twoPaneBar(measureB = false).dragFromAtoB(340f)

        assertIs<TabDropTarget.ExistingPanel>(target)
        assertEquals("b", target.panelId)
        assertNull(target.targetIndex)
    }

    @Test
    fun `the drop carries the slot through to the result`() {
        val component = twoPaneBar()
        component.dragFromAtoB(360f)

        val result = component.endDrag()

        assertIs<TabDropResult.MoveToPanel>(result)
        assertEquals("b", result.targetPanelId)
        // Unadjusted: a reorder's index is nudged down when the source sat earlier in the SAME
        // list, and doing that here would land the tab one slot short of the line it was shown.
        assertEquals(1, result.targetIndex)
    }

    @Test
    fun `the tab's own pane is still a reorder`() {
        val component = twoPaneBar()
        component.startDragging(StubTabInfo("a0"), panelId = "a", index = 0, startPosition = Offset(20f, 50f))
        // y = 210, in pane a's third row and above its midpoint: the slot before that tab.
        component.updateDrag(Offset(0f, 160f))

        val target = component.dropTarget
        assertIs<TabDropTarget.Reorder>(target)
        assertEquals(2, target.targetIndex)
    }
}
