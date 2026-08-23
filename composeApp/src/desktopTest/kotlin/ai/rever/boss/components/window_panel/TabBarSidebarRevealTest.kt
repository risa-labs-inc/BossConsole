package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.SIDEBAR_REVEAL_CLOSE_DELAY_MS
import ai.rever.boss.components.window_panel.components.main_window_panels.SIDEBAR_REVEAL_OPEN_DELAY_MS
import ai.rever.boss.components.window_panel.components.main_window_panels.hoverRevealTarget
import kotlin.test.Test
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
        // The handoff: the drawer slides OVER the rail, so hover moves between two different
        // nodes - and under HARDWARE between two different windows. Without this vote the drawer
        // closes the instant it finishes opening.
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
}
