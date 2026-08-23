package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.clampPinnedCount
import ai.rever.boss.components.window_panel.components.main_window_panels.pinnedCountAfterMove
import ai.rever.boss.components.window_panel.components.main_window_panels.pinnedCountAfterRemove
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The arithmetic behind "pinned tabs are always the first N".
 *
 * Worth testing rather than eyeballing because the invariant is what every index in the tab system
 * rests on: get the count wrong by one and the sidebar draws its separator in the wrong place, the
 * wrong tab reports as pinned, and `closeTabsToRight` closes from the wrong side. None of that
 * throws - it just quietly does the wrong thing, which is exactly the shape of bug a unit test is
 * for.
 *
 * A model of 5 tabs with 2 pinned is used throughout: indices 0,1 pinned and 2,3,4 open.
 */
class TabPinningTest {
    private val pinned = 2

    private fun move(
        from: Int,
        to: Int,
    ) = pinnedCountAfterMove(pinned, from, to)

    // --- moving a PINNED tab ---

    @Test
    fun `reordering within the pinned block keeps the count`() {
        assertEquals(2, move(from = 0, to = 1))
        assertEquals(2, move(from = 1, to = 0))
    }

    @Test
    fun `dragging a pinned tab below the separator unpins it`() {
        // Lands at index 2, the first open slot.
        assertEquals(1, move(from = 0, to = 2))
    }

    @Test
    fun `dragging a pinned tab to the very bottom unpins it`() {
        assertEquals(1, move(from = 1, to = 4))
    }

    @Test
    fun `a pinned tab landing on the last pinned slot stays pinned`() {
        // The boundary case the formula's derivation turns on: after removing the tab the block
        // is one shorter, so landing at index 1 is still inside it.
        assertEquals(2, move(from = 0, to = 1))
    }

    // --- moving an UNPINNED tab ---

    @Test
    fun `dragging an open tab above the separator pins it`() {
        assertEquals(3, move(from = 3, to = 0))
        assertEquals(3, move(from = 4, to = 1))
    }

    @Test
    fun `dropping exactly at the boundary leaves the tab open`() {
        // Index 2 is the first OPEN slot, so a drop there is below the line, not on it. This is
        // the case the reorder indicator is drawn under the separator to signal.
        assertEquals(2, move(from = 4, to = 2))
    }

    @Test
    fun `reordering within the open block keeps the count`() {
        assertEquals(2, move(from = 2, to = 4))
        assertEquals(2, move(from = 4, to = 2))
    }

    // --- closing ---

    @Test
    fun `closing a pinned tab shrinks the block`() {
        assertEquals(1, pinnedCountAfterRemove(pinned, removedIndex = 0))
        assertEquals(1, pinnedCountAfterRemove(pinned, removedIndex = 1))
    }

    @Test
    fun `closing an open tab leaves the block alone`() {
        assertEquals(2, pinnedCountAfterRemove(pinned, removedIndex = 2))
        assertEquals(2, pinnedCountAfterRemove(pinned, removedIndex = 4))
    }

    @Test
    fun `closing every tab drains the count to zero`() {
        // What closeOtherTabs and clearAllTabs do, one removeTab at a time, in reverse order.
        var count = 2
        for (i in 4 downTo 0) {
            count = pinnedCountAfterRemove(count, i)
        }
        assertEquals(0, count)
    }

    @Test
    fun `closing tabs to the right of a pinned tab keeps that tab pinned`() {
        // closeTabsToRight(0) on the 5-tab model: removes 4,3,2,1 and must leave tab 0 pinned.
        var count = 2
        for (i in 4 downTo 1) {
            count = pinnedCountAfterRemove(count, i)
        }
        assertEquals(1, count)
    }

    // --- restore ---

    @Test
    fun `a restored count is clamped to the tabs that came back`() {
        // A tab whose type no longer resolves, or a never-persisted panel-host tab, means fewer
        // tabs restore than were saved. Better the last pinned tab comes back open than a
        // separator drawn past the end of the list.
        assertEquals(2, clampPinnedCount(pinnedCount = 4, tabCount = 2))
        assertEquals(0, clampPinnedCount(pinnedCount = 3, tabCount = 0))
    }

    @Test
    fun `a sane restored count is left alone`() {
        assertEquals(2, clampPinnedCount(pinnedCount = 2, tabCount = 5))
        assertEquals(0, clampPinnedCount(pinnedCount = 0, tabCount = 5))
    }

    @Test
    fun `a negative count cannot come back`() {
        // Nothing writes one, but this is decoded from a file on disk.
        assertEquals(0, clampPinnedCount(pinnedCount = -1, tabCount = 5))
    }
}
