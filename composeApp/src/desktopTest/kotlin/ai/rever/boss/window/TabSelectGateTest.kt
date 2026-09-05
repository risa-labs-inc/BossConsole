package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AWTKeyboardInterceptor.dispatchIfTabExistsAt], the gate on Cmd+1..Cmd+9.
 *
 * `selectTabByPosition` ignores an out-of-range position, so dispatching is harmless - but
 * CLAIMING the chord is not, which is the whole argument behind [TabStepGateTest] and
 * [PanelNavigationGateTest]. A two-tab window must leave Cmd+3 through Cmd+8 to the terminal or
 * editor that has focus rather than consume them for nothing.
 */
class TabSelectGateTest {
    @Test
    fun `a position past the end leaves the chord to the focused component`() {
        val windowId = "select-short"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 2)

        var fired = false
        // Cmd+3 is index 2, one past the last tab of a two-tab panel.
        val handled = AWTKeyboardInterceptor.dispatchIfTabExistsAt(windowId, 2) { fired = true }

        assertFalse(handled)
        assertFalse(fired)
    }

    @Test
    fun `a position that exists selects and claims the chord`() {
        val windowId = "select-long"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 3)

        var firedFor: String? = null
        val handled = AWTKeyboardInterceptor.dispatchIfTabExistsAt(windowId, 2) { firedFor = it }

        assertTrue(handled)
        assertEquals(windowId, firedFor)
    }

    @Test
    fun `Cmd+9 needs only one tab, since it means the LAST tab`() {
        // TAB_SELECT_LAST asks for index 0 rather than 8: Cmd+9 is "the last tab", so it acts
        // on any non-empty panel and only an empty one should let it through.
        val windowId = "select-last"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 1)
        assertTrue(AWTKeyboardInterceptor.dispatchIfTabExistsAt(windowId, 0) { })

        MenuActionsHandler.updateActivePanelTabCount(windowId, 0)
        assertFalse(AWTKeyboardInterceptor.dispatchIfTabExistsAt(windowId, 0) { })
    }

    @Test
    fun `an unknown window claims nothing`() {
        assertFalse(AWTKeyboardInterceptor.dispatchIfTabExistsAt("select-never-registered", 0) { })
    }

    @Test
    fun `closing a window drops the count the gate reads`() {
        val windowId = "select-cleanup"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 5)
        assertEquals(5, MenuActionsHandler.activePanelTabCount(windowId))

        MenuActionsHandler.cleanupWindow(windowId)

        assertEquals(0, MenuActionsHandler.activePanelTabCount(windowId))
        assertFalse(AWTKeyboardInterceptor.dispatchIfTabExistsAt(windowId, 0) { })
    }
}
