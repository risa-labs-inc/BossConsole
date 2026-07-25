package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.TabStripMetrics
import ai.rever.boss.components.window_panel.components.main_window_panels.computeTabWidthPx
import ai.rever.boss.components.window_panel.components.main_window_panels.tabStripMetrics
import androidx.compose.ui.unit.Density
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

    private val metrics = TabStripMetrics(dividerPx = divider, minTabPx = min, maxTabPx = max)

    private fun compute(
        stripWidthPx: Int,
        tabCount: Int,
        trailingPx: Int = 0,
    ) = computeTabWidthPx(stripWidthPx, tabCount, trailingPx, metrics)

    @Test
    fun `single tab gets max width`() {
        assertEquals(240, compute(stripWidthPx = 1000, tabCount = 1))
    }

    @Test
    fun `tabs divide available width after dividers`() {
        // (1000 - 9*9) / 10 = 91.9 → floors to 91
        assertEquals(91, compute(stripWidthPx = 1000, tabCount = 10))
    }

    @Test
    fun `below-floor result clamps to min and row scrolls`() {
        // (1000 - 29*9) / 30 = 24.6 → 24 → clamped to 36
        assertEquals(36, compute(stripWidthPx = 1000, tabCount = 30))
    }

    @Test
    fun `dividers exceeding row width clamps to min not max`() {
        // Regression: 30 tabs in a 200px row → dividers alone are 261px, the
        // division goes negative. The old -1 sentinel conflated this with
        // "not measured yet" and fell back to MAX (240) — the exact opposite
        // of shrink-to-fit. Over-cramped must clamp to the floor.
        assertEquals(36, compute(stripWidthPx = 200, tabCount = 30))
    }

    @Test
    fun `unmeasured row falls back to max for first paint`() {
        assertEquals(240, compute(stripWidthPx = 0, tabCount = 10))
    }

    @Test
    fun `zero tabs falls back to max`() {
        assertEquals(240, compute(stripWidthPx = 1000, tabCount = 0))
    }

    @Test
    fun `exact fit divides evenly with no slack`() {
        // 5 tabs at 100px + 4 dividers at 9px = 536px row
        assertEquals(100, compute(stripWidthPx = 536, tabCount = 5))
    }

    @Test
    fun `divider reserve matches the three separately-rounded rendered pieces`() {
        // The divider renders as three independently rounded pieces —
        // INTER_TAB_DIVIDER_PADDING twice plus VDivider's 1.dp line — so the
        // reserve has to round them the same way. Summing in Dp space and
        // converting once (`9.dp.toPx().toInt()`) truncates where the pieces
        // round up, under-reserving 1px per divider: ~19px of unbudgeted
        // overflow at 20 tabs, i.e. the scrollbar flicker the integer-pixel
        // design exists to prevent. Only visible at fractional densities,
        // which is Windows 150%/175% scaling, not the 1x/2x Macs we build on.
        assertEquals(14, Density(1.5f).tabStripMetrics().dividerPx, "density 1.5: rendered 6+6+2")
        assertEquals(16, Density(1.75f).tabStripMetrics().dividerPx, "density 1.75: rendered 7+7+2")
        assertEquals(9, Density(1f).tabStripMetrics().dividerPx, "density 1.0: rendered 4+4+1")
        assertEquals(18, Density(2f).tabStripMetrics().dividerPx, "density 2.0: rendered 8+8+2")
    }

    @Test
    fun `trailing slot is reserved before the tabs divide the row`() {
        // The "+" button shares the strip with the tabs, so its 48px slot
        // comes off the top: (1000 - 48 - 9*9) / 10 = 87.1 → 87.
        assertEquals(87, compute(stripWidthPx = 1000, tabCount = 10, trailingPx = 48))
    }

    @Test
    fun `tabs plus trailing slot never exceed the row`() {
        // The scrollbar-flicker invariant again, this time with the button
        // in the budget: it must still fit beside a full strip of tabs.
        val row = 1234
        val trailing = 48
        for (tabCount in 1..20) {
            val width = compute(row, tabCount, trailing)
            if (width in (min + 1) until max) {
                val total = width * tabCount + divider * (tabCount - 1) + trailing
                assertTrue(
                    total <= row,
                    "tabCount=$tabCount: total ${total}px exceeds row ${row}px",
                )
            }
        }
    }

    @Test
    fun `trailing slot wider than the row clamps to min not max`() {
        // Degenerate window (narrower than the button slot itself): the
        // division goes negative, which must read as over-cramped → floor,
        // never as "not measured yet" → max.
        assertEquals(36, compute(stripWidthPx = 40, tabCount = 3, trailingPx = 48))
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
