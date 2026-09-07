package ai.rever.boss.components.model

import ai.rever.boss.components.window_panel.SplitOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The insertion-line rule, which is the one part of the drag a screenshot cannot check.
 *
 * A line drawn on the wrong edge and a line drawn twice both look plausible in a single frame,
 * and the failure the user meets is a tab landing one slot from where the line was. So the rule
 * is asserted as a property over a whole list - **every slot is drawn exactly once** - rather
 * than case by case: a doubled boundary and a lost final slot each satisfy every individual case
 * while breaking the property.
 */
class InsertionEdgeTest {
    /**
     * Which rows draw a line, and on which edge, for a drop naming [insertionIndex] in a list of
     * [tabCount] tabs. The bar renders one row per index, so this is what the whole pane draws.
     */
    private fun drawnBy(
        insertionIndex: Int?,
        tabCount: Int,
    ): List<Pair<Int, InsertionEdge>> =
        (0 until tabCount).mapNotNull { index ->
            insertionEdgeFor(insertionIndex, index, tabCount)?.let { index to it }
        }

    @Test
    fun `every slot of a pane is drawn exactly once`() {
        val tabCount = 4
        // Slots run 0..tabCount: one before each tab, plus one past the last.
        for (slot in 0..tabCount) {
            assertEquals(
                1,
                drawnBy(slot, tabCount).size,
                "slot $slot of a $tabCount-tab pane must be drawn by exactly one row",
            )
        }
    }

    @Test
    fun `a slot is drawn by the row beneath it`() {
        assertEquals(listOf(0 to InsertionEdge.LEADING), drawnBy(0, 4))
        assertEquals(listOf(2 to InsertionEdge.LEADING), drawnBy(2, 4))
    }

    @Test
    fun `the last slot rides the final row's trailing edge`() {
        // The one slot with no row beneath it. Row 3 is the last of four.
        assertEquals(listOf(3 to InsertionEdge.TRAILING), drawnBy(4, 4))
    }

    @Test
    fun `a boundary two rows touch is not drawn twice`() {
        // Row 1's trailing edge and row 2's leading edge are the same line on screen. Only the
        // row beneath owns it, so nothing here draws on a trailing edge but the last row.
        assertNull(insertionEdgeFor(insertionIndex = 2, index = 1, tabCount = 4))
        assertEquals(InsertionEdge.LEADING, insertionEdgeFor(insertionIndex = 2, index = 2, tabCount = 4))
    }

    @Test
    fun `no drop in this pane draws nothing`() {
        assertEquals(emptyList(), drawnBy(null, 4))
    }

    @Test
    fun `a lone tab still has both of its slots`() {
        assertEquals(listOf(0 to InsertionEdge.LEADING), drawnBy(0, 1))
        assertEquals(listOf(0 to InsertionEdge.TRAILING), drawnBy(1, 1))
    }

    @Test
    fun `an index past the end of the list draws nothing`() {
        // A list that shrank while the drag was in flight. Drawing the line on whichever row is
        // now last would put it somewhere the drop will not land - reorderWithinPanel clamps, so
        // the tab lands at the end, and no line is a better answer than a wrong one.
        assertEquals(emptyList(), drawnBy(6, 4))
    }

    @Test
    fun `a reorder within this pane and a move in from another read the same`() {
        val reorder = TabDropTarget.Reorder("left", targetIndex = 2)
        val incoming = TabDropTarget.ExistingPanel("left", targetIndex = 2)

        assertEquals(2, paneInsertionIndexFor(reorder, "left"))
        assertEquals(2, paneInsertionIndexFor(incoming, "left"))
    }

    @Test
    fun `a target naming another pane is not this pane's business`() {
        assertNull(paneInsertionIndexFor(TabDropTarget.ExistingPanel("right", targetIndex = 2), "left"))
        assertNull(paneInsertionIndexFor(TabDropTarget.Reorder("right", targetIndex = 2), "left"))
    }

    @Test
    fun `a pane target with no position draws no line`() {
        // The centre of a panel's content area, and a sidebar panel dragged onto one: both name
        // the pane and nothing in it, so the fill says where it goes and there is no line to draw.
        assertNull(paneInsertionIndexFor(TabDropTarget.ExistingPanel("left"), "left"))
    }

    @Test
    fun `a split zone, the Favorites shelf and no drag at all draw no line`() {
        assertNull(
            paneInsertionIndexFor(
                TabDropTarget.SplitPanel("left", SplitOrientation.VERTICAL),
                "left",
            ),
        )
        assertNull(paneInsertionIndexFor(TabDropTarget.Favorites, "left"))
        assertNull(paneInsertionIndexFor(null, "left"))
    }

    @Test
    fun `a bar with no panel id cannot claim a target`() {
        // A tab bar can be built without one (see rememberTabBarState's currentPanelId), and it
        // has no way to tell whether a target naming some pane is its own.
        assertNull(paneInsertionIndexFor(TabDropTarget.Reorder("left", targetIndex = 0), null))
    }
}
