package ai.rever.boss.components.sidebar

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the pure row-budget allocator behind the sidebar's adaptive
 * icon limits. The Compose-side consumption (BoxWithConstraints → per-slot
 * caps → overflow button) is exercised manually; what matters here is
 * that the arithmetic keeps every plugin reachable and no slot starved.
 */
class SidebarIconLimitsTest {

    @Test
    fun `everything fits when budget covers all items`() {
        assertEquals(listOf(3, 2, 4), allocateIconRows(listOf(3, 2, 4), 20))
        assertEquals(listOf(3, 2, 4), allocateIconRows(listOf(3, 2, 4), 9))
    }

    @Test
    fun `tight budget is dealt round-robin so no slot starves`() {
        // 6 rows across (10, 1, 5): passes give each needy slot one row
        // at a time — the crowded first slot can't consume the budget.
        assertEquals(listOf(3, 1, 2), allocateIconRows(listOf(10, 1, 5), 6))
    }

    @Test
    fun `every non-empty slot keeps one row even on zero or negative budget`() {
        // One row per non-empty slot hosts the More button, keeping all
        // plugins reachable however small the window gets.
        assertEquals(listOf(1, 0, 1), allocateIconRows(listOf(4, 0, 2), 0))
        assertEquals(listOf(1, 0, 1), allocateIconRows(listOf(4, 0, 2), -3))
    }

    @Test
    fun `empty slots are skipped entirely`() {
        assertEquals(listOf(0, 0), allocateIconRows(listOf(0, 0), 10))
        assertEquals(listOf(0, 5), allocateIconRows(listOf(0, 5), 5))
    }

    @Test
    fun `surplus beyond item counts is not allocated`() {
        // Budget 100 for 3 items total: allocations stop at the counts.
        assertEquals(listOf(2, 1), allocateIconRows(listOf(2, 1), 100))
    }

    @Test
    fun `caps equal counts when everything fits`() {
        assertEquals(listOf(3, 2, 4), iconCapsForRows(listOf(3, 2, 4), 20))
        // Exact boundary: count == allocated rows → all icons, no More row.
        assertEquals(listOf(2), iconCapsForRows(listOf(2), 2))
    }

    @Test
    fun `overflowing slot yields its last row to the More button`() {
        // 6 rows across (10, 1, 5) allocate as (3, 1, 2); slots 0 and 2
        // overflow, so each renders one fewer icon than allocated rows
        // (that row becomes the More button). Total rendered rows —
        // 2+More, 1, 1+More — still equal the 6 allocated.
        assertEquals(listOf(2, 1, 1), iconCapsForRows(listOf(10, 1, 5), 6))
    }

    @Test
    fun `guaranteed single row renders More only`() {
        // Zero budget: every non-empty slot keeps one guaranteed row; a
        // slot that can't fit all its items spends it on More (cap 0),
        // while a single-item slot just shows its icon.
        assertEquals(listOf(0, 0, 1), iconCapsForRows(listOf(4, 2, 1), 0))
    }

    @Test
    fun `caps skip empty slots`() {
        assertEquals(listOf(0, 0), iconCapsForRows(listOf(0, 3), 1))
        assertEquals(listOf(0, 3), iconCapsForRows(listOf(0, 3), 5))
    }
}
