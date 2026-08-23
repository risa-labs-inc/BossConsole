package ai.rever.boss.layout

import ai.rever.boss.components.window_panel.components.main_window_panels.NEW_TAB_BUTTON_SIZE
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.TabBarVerticalWidthRange
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what the window chrome costs a browser tab.
 *
 * The point of these numbers being in a test is that issue #239 is about a budget nobody was
 * tracking: bar heights were literals in seven different files, so a bar could be added or grown
 * without anyone noticing the page had lost another 30dp. Growing the chrome now means changing an
 * assertion here and saying so.
 *
 * The reference window is a 13" MacBook Air at its default scaled resolution: 1470 x 956 pt, of
 * which ~931 pt is window height once the macOS menu bar is subtracted.
 */
class ChromeMetricsTest {
    private val airHeight = 931.dp
    private val airWidth = 1470.dp

    /** macOS defaults: title bar on, all four bars on, focus mode off. */
    private val macDefaults = WindowAppearanceSettings(showTitleBar = true)

    /** Windows/Linux defaults, which differ only in the title bar. */
    private val nonMacDefaults = WindowAppearanceSettings(showTitleBar = false)

    private val focusOff = FocusModeSettings(enabled = false)

    private val comfortable = ChromeDimens.Comfortable

    /** Border ring plus content inset, off both axes, in every configuration. */
    private val ring = comfortable.panelBorderThickness * 2

    /** Everything a preference can switch off, switched off. */
    private val leanest =
        WindowAppearanceSettings(
            showTitleBar = false,
            showTopBar = false,
            showBottomBar = false,
            showLeftStrip = false,
            showRightStrip = false,
        )

    @Test
    fun `shipped macOS defaults cost 146dp and leave the page 84 percent`() {
        val budget = ChromeMetrics.mainPanelBudget(macDefaults, focusOff, comfortable)

        // 27 title (26+1) + 41 top (40+1) + 43 tab (42+1) + 31 bottom (30+1) + 4 ring
        assertEquals(146.dp, budget.vertical)
        // 41 per strip (40+1 divider) + 4 ring
        assertEquals(86.dp, budget.horizontal)
        assertEquals(0.843f, budget.verticalFractionOf(airHeight), absoluteTolerance = 0.001f)
        assertEquals(0.941f, budget.horizontalFractionOf(airWidth), absoluteTolerance = 0.001f)
    }

    @Test
    fun `Windows and Linux defaults save the title row`() {
        val budget = ChromeMetrics.mainPanelBudget(nonMacDefaults, focusOff, comfortable)

        assertEquals(119.dp, budget.vertical)
        assertEquals(86.dp, budget.horizontal)
    }

    @Test
    fun `the panel border ring is charged in every configuration`() {
        // BossMainPanel draws it whether or not the panel is active, and no preference switches it
        // off, so it is the one part of the budget that is always present. Omitting it understated
        // every figure here by 4dp on each axis until the review of #240 caught it.
        val leanestPossible =
            ChromeMetrics.mainPanelBudget(leanest, focusOff, comfortable)

        assertTrue(leanestPossible.vertical >= ring)
        assertEquals(ring, leanestPossible.horizontal)
    }

    @Test
    fun `compact density is worth 20dp of height over comfortable`() {
        val comfortable = ChromeMetrics.mainPanelBudget(macDefaults, focusOff, ChromeDimens.Comfortable)
        val compact = ChromeMetrics.mainPanelBudget(macDefaults, focusOff, ChromeDimens.Compact)

        assertEquals(20.dp, comfortable.vertical - compact.vertical)
        // 4 off each strip, 8 across both.
        assertEquals(8.dp, comfortable.horizontal - compact.horizontal)
    }

    @Test
    fun `focus mode clearing every edge leaves only the tab bar`() {
        val focusOn =
            FocusModeSettings(
                enabled = true,
                hideTopBar = true,
                hideLeftSidebar = true,
                hideRightSidebar = true,
                hideBottomBar = true,
            )

        val budget = ChromeMetrics.mainPanelBudget(macDefaults, focusOn, comfortable)

        // Title row survives: it answers to the appearance preference, not to focus mode.
        assertEquals(27.dp + 43.dp + ring, budget.vertical)
        assertEquals(ring, budget.horizontal)
    }

    @Test
    fun `focus mode enabled but hiding nothing changes nothing`() {
        val idle =
            FocusModeSettings(
                enabled = true,
                hideTopBar = false,
                hideLeftSidebar = false,
                hideRightSidebar = false,
                hideBottomBar = false,
            )

        assertEquals(
            ChromeMetrics.mainPanelBudget(macDefaults, focusOff, comfortable),
            ChromeMetrics.mainPanelBudget(macDefaults, idle, comfortable),
        )
    }

    @Test
    fun `a hidden bar costs nothing even with focus mode off`() {
        val budget = ChromeMetrics.mainPanelBudget(leanest, focusOff, comfortable)

        // The tab bar has no switch, so this plus the ring is the floor the architecture can reach.
        assertEquals(43.dp + ring, budget.vertical)
        assertEquals(ring, budget.horizontal)
        assertTrue(budget.verticalFractionOf(airHeight) > 0.94f)
    }

    @Test
    fun `the tab bar is never free`() {
        // Whatever else is switched off, a tabbed browser keeps its tab row.
        ChromeDensity.entries.forEach { density ->
            val dimens = ChromeDimens.of(density)
            val budget = ChromeMetrics.mainPanelBudget(leanest, focusOff, dimens)
            assertEquals(
                dimens.tabBarHeight + dimens.dividerThickness + dimens.panelBorderThickness * 2,
                budget.vertical,
                "density $density",
            )
        }
    }

    // --- vertical tab bar ---

    /** Everything switchable off, and the tab bar moved to the leading edge. */
    private val leanestVertical = leanest.copy(tabBarPosition = TabBarPosition.LEFT)

    @Test
    fun `a left tab bar is charged to width instead of height`() {
        val budget = ChromeMetrics.mainPanelBudget(leanestVertical, focusOff, comfortable)

        // Nothing but the ring left vertically: the tab row moved off that axis entirely.
        assertEquals(ring, budget.vertical)
        assertEquals(
            ring + leanestVertical.tabBarVerticalWidth.dp + comfortable.dividerThickness,
            budget.horizontal,
        )
    }

    @Test
    fun `moving the tab bar left trades height for width, it does not add both`() {
        // The regression this guards: charging the bar vertically regardless of position, which
        // would leave the page paying for a row that is not there AND for the column that is.
        val top = ChromeMetrics.mainPanelBudget(leanest, focusOff, comfortable)
        val left = ChromeMetrics.mainPanelBudget(leanestVertical, focusOff, comfortable)

        assertTrue(left.vertical < top.vertical, "a left bar must cost less height, not the same")
        assertTrue(left.horizontal > top.horizontal, "a left bar must cost width")
    }

    @Test
    fun `a collapsed left bar costs only a strip`() {
        // The rail is deliberately the same width as the window's own icon strips, so a collapsed
        // tab bar costs exactly what adding one more strip would.
        val collapsed = leanestVertical.copy(tabBarCollapsed = true)
        val budget = ChromeMetrics.mainPanelBudget(collapsed, focusOff, comfortable)

        assertEquals(ring + comfortable.stripWidth + comfortable.dividerThickness, budget.horizontal)
    }

    @Test
    fun `an out-of-range width cannot be charged`() {
        // The setting is decoded from a file, and a budget is not the place to discover that.
        val absurd = leanestVertical.copy(tabBarVerticalWidth = 5000f)
        val budget = ChromeMetrics.mainPanelBudget(absurd, focusOff, comfortable)

        assertEquals(
            ring + TabBarVerticalWidthRange.endInclusive.dp + comfortable.dividerThickness,
            budget.horizontal,
        )
    }

    @Test
    fun `the top position is unchanged by any of this`() {
        // The default must cost exactly what it did before the bar could move.
        val budget = ChromeMetrics.mainPanelBudget(macDefaults, focusOff, comfortable)
        assertEquals(146.dp, budget.vertical)
        assertEquals(86.dp, budget.horizontal)
    }

    @Test
    fun `a degenerate window size reports zero rather than dividing by zero`() {
        val budget = ChromeMetrics.mainPanelBudget(macDefaults, focusOff, comfortable)

        assertEquals(0f, budget.verticalFractionOf(0.dp))
        assertEquals(0f, budget.horizontalFractionOf((-10).dp))
    }

    @Test
    fun `chrome taller than the window clamps to zero rather than going negative`() {
        val budget = ChromeMetrics.mainPanelBudget(macDefaults, focusOff, comfortable)

        assertEquals(0f, budget.verticalFractionOf(100.dp))
    }

    @Test
    fun `comfortable reproduces the literals it replaced`() {
        // The 146dp aggregate above catches a regression but reports a total; this names the field
        // that moved, and is the most direct statement of PR A's no-op claim.
        assertEquals(
            ChromeDimens(
                titleBarHeight = 26.dp,
                topBarHeight = 40.dp,
                tabBarHeight = 42.dp,
                bottomBarHeight = 30.dp,
                stripWidth = 40.dp,
                panelTopBarHeight = 28.dp,
            ),
            ChromeDimens.Comfortable,
        )
    }

    @Test
    fun `the tab bar floor really clears the new-tab button`() {
        // MIN_TAB_BAR's KDoc derives itself from NEW_TAB_BUTTON_SIZE in prose. Bump that constant
        // and the prose is quietly wrong with a green suite, so pin the relationship instead - the
        // same discipline SidebarBottomActionsLayoutTest applies to the rail metrics.
        assertTrue(
            ChromeDimens.MIN_TAB_BAR >= NEW_TAB_BUTTON_SIZE + 4.dp,
            "MIN_TAB_BAR ${ChromeDimens.MIN_TAB_BAR} leaves the ${NEW_TAB_BUTTON_SIZE} new-tab " +
                "button less than 2dp a side",
        )
    }

    @Test
    fun `every compact metric respects the floor its content imposes`() {
        val compact = ChromeDimens.Compact

        assertTrue(compact.titleBarHeight >= ChromeDimens.MIN_TITLE_BAR)
        assertTrue(compact.tabBarHeight >= ChromeDimens.MIN_TAB_BAR)
        assertTrue(compact.stripWidth >= ChromeDimens.MIN_STRIP_WIDTH)
    }

    @Test
    fun `density ordering is monotonic so compact is never the roomiest`() {
        val compact = ChromeDimens.Compact
        val comfortable = ChromeDimens.Comfortable
        val spacious = ChromeDimens.Spacious

        assertTrue(compact.topBarHeight < comfortable.topBarHeight)
        assertTrue(comfortable.topBarHeight < spacious.topBarHeight)
        assertTrue(compact.tabBarHeight < comfortable.tabBarHeight)
        assertTrue(comfortable.tabBarHeight < spacious.tabBarHeight)
        assertTrue(compact.bottomBarHeight < comfortable.bottomBarHeight)
        assertTrue(comfortable.bottomBarHeight < spacious.bottomBarHeight)
        assertTrue(compact.stripWidth < comfortable.stripWidth)
        assertTrue(comfortable.stripWidth < spacious.stripWidth)
    }
}
