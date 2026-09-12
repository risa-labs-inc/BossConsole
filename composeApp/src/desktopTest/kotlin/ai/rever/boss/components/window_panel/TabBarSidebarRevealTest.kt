package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.SIDEBAR_REVEAL_CLOSE_DELAY_MS
import ai.rever.boss.components.window_panel.components.main_window_panels.SIDEBAR_REVEAL_OPEN_DELAY_MS
import ai.rever.boss.components.window_panel.components.main_window_panels.besideLeadingRail
import ai.rever.boss.components.window_panel.components.main_window_panels.hoverRevealTarget
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Truth table for the vertical tab bar's hover-reveal drawer.
 *
 * Pure by design so the three-way vote can be asserted directly. Getting it wrong is not a
 * visible crash: it is a drawer that retracts out from under an interaction, which is exactly
 * the failure BossTerm's version of this went through several rounds of.
 */
class TabBarSidebarRevealTest {
    private fun reveal(
        enabled: Boolean = true,
        railShown: Boolean = true,
        pointerOnRail: Boolean = false,
        pointerOnDrawer: Boolean = false,
        drawerBusy: Boolean = false,
    ) = hoverRevealTarget(enabled, railShown, pointerOnRail, pointerOnDrawer, drawerBusy)

    @Test
    fun `pointer resting on the rail reveals`() {
        assertTrue(reveal(pointerOnRail = true))
    }

    @Test
    fun `pointer on the drawer keeps it revealed`() {
        // The drawer sits beside the rail, but they remain separate nodes and under HARDWARE
        // separate windows. Without this vote the drawer closes during that handoff.
        assertTrue(reveal(pointerOnRail = false, pointerOnDrawer = true))
    }

    @Test
    fun `pointer nowhere near retracts`() {
        assertFalse(reveal())
    }

    @Test
    fun `a busy drawer stays open with the pointer gone`() {
        // An open context menu is its own popup, so reaching for it takes the pointer off the
        // drawer. Retracting would dispose the composition that owns the menu.
        assertTrue(reveal(pointerOnRail = false, pointerOnDrawer = false, drawerBusy = true))
    }

    @Test
    fun `the setting being off overrides every other vote`() {
        assertFalse(reveal(enabled = false, pointerOnRail = true))
        assertFalse(reveal(enabled = false, pointerOnDrawer = true))
        assertFalse(reveal(enabled = false, drawerBusy = true))
    }

    @Test
    fun `a full bar never reveals a drawer over itself`() {
        // Not collapsed means the real bar is already in the layout. Busy must not conjure a
        // second copy of it on top.
        assertFalse(reveal(railShown = false, pointerOnRail = true))
        assertFalse(reveal(railShown = false, drawerBusy = true))
    }

    @Test
    fun `the close delay exceeds the open delay`() {
        // Load-bearing rather than cosmetic: the close grace has to cover the rail-to-drawer
        // handoff, which itself takes the open delay. Equal or shorter and the drawer flickers.
        assertTrue(SIDEBAR_REVEAL_CLOSE_DELAY_MS > SIDEBAR_REVEAL_OPEN_DELAY_MS)
    }

    @Test
    fun `the drawer region begins after the retained rail`() {
        val panel = IntRect(left = 40, top = 12, right = 520, bottom = 612)

        assertEquals(
            IntRect(left = 76, top = 12, right = 520, bottom = 612),
            panel.besideLeadingRail(36.dp),
        )
    }

    @Test
    fun `a panel narrower than the rail coerces to a zero-width region`() {
        // left + railWidth passes right, so the drawer gets nothing beside the rail; the
        // zero-width answer is what resolveRegion's documented inset fallback then acts on.
        // Pin the coercion direction so that inheritance stays a deliberate choice.
        val panel = IntRect(left = 40, top = 12, right = 50, bottom = 612)
        val region = panel.besideLeadingRail(36.dp)

        assertEquals(50, region.left)
        assertEquals(region.left, region.right, "the drawer region is zero-width, not negative")
    }
}
