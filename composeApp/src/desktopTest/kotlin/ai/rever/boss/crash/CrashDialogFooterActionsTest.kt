package ai.rever.boss.crash

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [showsInlineFooterActions] as a pure function, pinned without a display — the shape
 * `HomeHeader.showsInlineSearch` and `HomeLayoutTest` already established for a reflow decision
 * that would otherwise be a bare `if` buried inside a composable.
 *
 * What this protects is #104: at the crash window's 450dp minimum, the three footer buttons
 * don't fit on one row, and a Row over-subscribed like that squeezes the shortfall into its last
 * child instead of overflowing — shredding "Report Issue" / "Submitting..." across several lines
 * rather than the row simply running wide.
 */
class CrashDialogFooterActionsTest {
    @Test
    fun `the crash window's minimum content width does not fit the buttons inline`() {
        // ~386-402dp is the measured available content width at the 450dp frame minimum (#104).
        assertFalse(showsInlineFooterActions(386.dp))
        assertFalse(showsInlineFooterActions(402.dp))
    }

    @Test
    fun `a width one pixel short of the breakpoint stacks`() {
        assertFalse(showsInlineFooterActions(FooterActionsInlineMinWidth - 1.dp))
    }

    @Test
    fun `the breakpoint itself and anything wider goes inline`() {
        assertTrue(showsInlineFooterActions(FooterActionsInlineMinWidth))
        assertTrue(showsInlineFooterActions(700.dp))
    }

    @Test
    fun `the preferred crash window's content width fits the buttons inline`() {
        // CrashHandler.CONTENT_PREFERRED_WIDTH is 550dp; minus the ~48dp of outer content
        // padding (#104's own measurement), that's the available width the footer actually
        // sees — where it has shipped fine. This reflow must not change that default case.
        assertTrue(showsInlineFooterActions(CrashHandler.CONTENT_PREFERRED_WIDTH.dp - 48.dp))
    }
}
