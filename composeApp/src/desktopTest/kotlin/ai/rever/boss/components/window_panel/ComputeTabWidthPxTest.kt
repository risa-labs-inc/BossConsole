package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.computeTabWidthPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the Safari-style shrink-to-fit width computation behind
 * BossLeftTabBar. All values are in pixels; at density 1.0 they map 1:1 to
 * the Dp constants in BossTabBar.kt (min 36, max 240, divider 9).
 */
class ComputeTabWidthPxTest {
    private val divider = 9
    private val min = 36
    private val max = 240

    private fun compute(
        rowWidthPx: Int,
        tabCount: Int,
    ) = computeTabWidthPx(rowWidthPx, tabCount, divider, min, max)

    @Test
    fun `single tab gets max width`() {
        assertEquals(240, compute(rowWidthPx = 1000, tabCount = 1))
    }

    @Test
    fun `tabs divide available width after dividers`() {
        // (1000 - 9*9) / 10 = 91.9 → floors to 91
        assertEquals(91, compute(rowWidthPx = 1000, tabCount = 10))
    }

    @Test
    fun `below-floor result clamps to min and row scrolls`() {
        // (1000 - 29*9) / 30 = 24.6 → 24 → clamped to 36
        assertEquals(36, compute(rowWidthPx = 1000, tabCount = 30))
    }

    @Test
    fun `dividers exceeding row width clamps to min not max`() {
        // Regression: 30 tabs in a 200px row → dividers alone are 261px, the
        // division goes negative. The old -1 sentinel conflated this with
        // "not measured yet" and fell back to MAX (240) — the exact opposite
        // of shrink-to-fit. Over-cramped must clamp to the floor.
        assertEquals(36, compute(rowWidthPx = 200, tabCount = 30))
    }

    @Test
    fun `unmeasured row falls back to max for first paint`() {
        assertEquals(240, compute(rowWidthPx = 0, tabCount = 10))
    }

    @Test
    fun `zero tabs falls back to max`() {
        assertEquals(240, compute(rowWidthPx = 1000, tabCount = 0))
    }

    @Test
    fun `exact fit divides evenly with no slack`() {
        // 5 tabs at 100px + 4 dividers at 9px = 536px row
        assertEquals(100, compute(rowWidthPx = 536, tabCount = 5))
    }

    @Test
    fun `total content never exceeds row width in shrink range`() {
        // The floor-division invariant that keeps the scrollbar from
        // flickering: tabs + dividers ≤ row, for any count still above the
        // clamp floor.
        val row = 1234
        for (tabCount in 1..20) {
            val width = compute(row, tabCount)
            if (width in (min + 1) until max) {
                val total = width * tabCount + divider * (tabCount - 1)
                assertTrue(
                    total <= row,
                    "tabCount=$tabCount: total ${total}px exceeds row ${row}px",
                )
            }
        }
    }
}
