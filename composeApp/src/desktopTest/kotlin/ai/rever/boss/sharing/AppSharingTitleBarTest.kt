package ai.rever.boss.sharing

import ai.rever.boss.app.mergeNativeSharingActions
import ai.rever.boss.window.NativeTitleBarAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSharingTitleBarTest {
    @Test
    fun `window sharing extends the existing terminal button without losing actions or consent`() {
        var terminalOpened = false
        val terminal =
            NativeTitleBarAction("terminal_sharing", "Share", "square.and.arrow.up") { terminalOpened = true }
        val other = NativeTitleBarAction("terminal_call", "Call") {}
        val window =
            NativeTitleBarAction(
                "sharing",
                "Sharing",
                active = true,
                menu = appWindowSharingActions("first", AppSharingState()),
            ) {}
        val actions = mergeNativeSharingActions(listOf(terminal, other, window))
        assertEquals(listOf("terminal_sharing", "terminal_call"), actions.map { it.id })
        val combined = actions.first()
        assertEquals(terminal.symbol, combined.symbol)
        assertTrue(combined.active)
        val menu = combined.menu!!.associateBy { it.id }
        menu.getValue("terminal_sharing").onClick()
        assertTrue(terminalOpened)
        assertTrue(menu.getValue("share_app_window").localOnly)
        assertTrue(menu.getValue("share_selected_app_windows").localOnly)
        assertFalse(menu.containsKey("stop_app_sharing"))
        assertEquals(listOf(window), mergeNativeSharingActions(listOf(window)))
        assertEquals(listOf(terminal), mergeNativeSharingActions(listOf(terminal)))
    }

    @Test
    fun `sharing menu follows its owning window and preserves local capture consent`() {
        val state =
            AppSharingState(
                activeWindowIds = setOf("first"),
                selectedWindowIds = setOf("first", "second"),
            )
        val first = appWindowSharingActions("first", state).associateBy { it.id }
        val second = appWindowSharingActions("second", state).associateBy { it.id }
        assertTrue(first.getValue("share_app_window").active)
        assertFalse(first.getValue("share_app_window").enabled)
        assertFalse(second.getValue("share_app_window").active)
        assertTrue(second.getValue("share_app_window").enabled)
        for (menu in listOf(first, second)) {
            assertTrue(menu.getValue("share_app_window").localOnly)
            assertTrue(menu.getValue("share_selected_app_windows").localOnly)
            assertTrue(menu.getValue("share_selected_app_windows").enabled)
            assertTrue(menu.getValue("stop_app_sharing").enabled)
            assertFalse(menu.getValue("stop_app_sharing").localOnly)
            assertTrue(menu.getValue("app_sharing_settings").enabled)
        }
    }

    @Test
    fun `empty selection and in-progress sharing cannot start duplicate captures`() {
        val idle = appWindowSharingActions("first", AppSharingState()).associateBy { it.id }
        assertTrue(idle.getValue("share_app_window").enabled)
        assertFalse(idle.getValue("share_selected_app_windows").enabled)
        assertFalse(idle.containsKey("stop_app_sharing"))
        val busy =
            appWindowSharingActions("first", AppSharingState(busy = true, selectedWindowIds = setOf("first")))
                .associateBy { it.id }
        assertFalse(busy.getValue("share_app_window").enabled)
        assertFalse(busy.getValue("share_selected_app_windows").enabled)
        assertTrue(busy.getValue("stop_app_sharing").enabled)
        assertEquals("Cancel BossConsole Sharing", busy.getValue("stop_app_sharing").label)
    }
}
