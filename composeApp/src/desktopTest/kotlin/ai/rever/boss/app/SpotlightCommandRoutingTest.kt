package ai.rever.boss.app

import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.MenuActionsHandler.LAST_TAB_INDEX
import ai.rever.boss.window.MenuActionsHandler.TabSwitchAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for BossConsole#700: Spotlight selection callback ignored 26 action IDs.
 *
 * Verifies that selecting tab navigation, positional tab stepping, tab selection by index,
 * reopen closed tab, browser history back/forward, and devtools commands in Spotlight routes
 * to the corresponding [MenuActionsHandler] event flow.
 */
class SpotlightCommandRoutingTest {
    private companion object {
        const val WINDOW_ID = "window-test-700"
    }

    @Test
    fun `tab next command emits NEXT and COMMIT actions`() = runBlocking {
        MenuActionsHandler.triggerNextTab(WINDOW_ID)
        MenuActionsHandler.triggerCommitTabCycle(WINDOW_ID)

        val first = MenuActionsHandler.tabSwitchEvents.first()
        assertEquals(WINDOW_ID to TabSwitchAction.NEXT, first)
    }

    @Test
    fun `tab previous command emits PREVIOUS and COMMIT actions`() = runBlocking {
        MenuActionsHandler.triggerPreviousTab(WINDOW_ID)
        MenuActionsHandler.triggerCommitTabCycle(WINDOW_ID)

        val first = MenuActionsHandler.tabSwitchEvents.first()
        assertEquals(WINDOW_ID to TabSwitchAction.PREVIOUS, first)
    }

    @Test
    fun `tab reopen closed command emits reopen event`() = runBlocking {
        MenuActionsHandler.triggerReopenClosedTab(WINDOW_ID)

        val event = MenuActionsHandler.reopenClosedTabEvents.first()
        assertEquals(WINDOW_ID, event)
    }

    @Test
    fun `browser back command emits back event`() = runBlocking {
        MenuActionsHandler.triggerBrowserBack(WINDOW_ID)

        val event = MenuActionsHandler.browserBackEvents.first()
        assertEquals(WINDOW_ID, event)
    }

    @Test
    fun `browser forward command emits forward event`() = runBlocking {
        MenuActionsHandler.triggerBrowserForward(WINDOW_ID)

        val event = MenuActionsHandler.browserForwardEvents.first()
        assertEquals(WINDOW_ID, event)
    }

    @Test
    fun `browser devtools command emits devtools event`() = runBlocking {
        MenuActionsHandler.triggerBrowserDevTools(WINDOW_ID)

        val event = MenuActionsHandler.browserDevToolsEvents.first()
        assertEquals(WINDOW_ID, event)
    }

    @Test
    fun `tab select 1 command emits index 0`() = runBlocking {
        MenuActionsHandler.triggerSelectTabByIndex(WINDOW_ID, 0)

        val event = MenuActionsHandler.selectTabIndexEvents.first()
        assertEquals(WINDOW_ID to 0, event)
    }

    @Test
    fun `tab select last command emits LAST_TAB_INDEX`() = runBlocking {
        MenuActionsHandler.triggerSelectLastTab(WINDOW_ID)

        val event = MenuActionsHandler.selectTabIndexEvents.first()
        assertEquals(WINDOW_ID to LAST_TAB_INDEX, event)
    }
}
