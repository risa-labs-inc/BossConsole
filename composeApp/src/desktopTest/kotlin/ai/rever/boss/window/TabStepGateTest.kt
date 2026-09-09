package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AWTKeyboardInterceptor.dispatchIfCanStepTabs] and the state behind it.
 *
 * `stepPositional` already returns early below two tabs, so dispatching would be harmless - but
 * CLAIMING the chord is not. Cmd+Opt+Arrow under BOSS Default, and Cmd+Shift+Bracket under the
 * VS Code and IntelliJ presets (where the Cmd+Alt+Arrow primary collides with panel navigation
 * and is dropped), would be consumed in a single-tab editor and do nothing.
 */
class TabStepGateTest {
    @Test
    fun `a single-tab panel leaves the chord to the focused component`() {
        val windowId = "step-single"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 1)

        var fired = false
        val handled = AWTKeyboardInterceptor.dispatchIfCanStepTabs(windowId) { fired = true }

        assertFalse(handled, "Cmd+Shift+] must still reach an editor as indent")
        assertFalse(fired)
        assertFalse(MenuActionsHandler.canStepTabs(windowId))
    }

    @Test
    fun `an empty panel leaves the chord alone`() {
        val windowId = "step-empty"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 0)

        assertFalse(AWTKeyboardInterceptor.dispatchIfCanStepTabs(windowId) { })
    }

    @Test
    fun `an unknown window is treated as having nothing to step`() {
        assertFalse(AWTKeyboardInterceptor.dispatchIfCanStepTabs("step-never-registered") { })
    }

    @Test
    fun `two or more tabs steps and claims the chord`() {
        val windowId = "step-many"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 2)

        var firedFor: String? = null
        val handled = AWTKeyboardInterceptor.dispatchIfCanStepTabs(windowId) { firedFor = it }

        assertTrue(handled)
        assertEquals(windowId, firedFor)
        assertTrue(MenuActionsHandler.canStepTabs(windowId))
    }

    @Test
    fun `closing a window drops its tab count`() {
        val windowId = "step-cleanup"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 3)
        assertTrue(MenuActionsHandler.canStepTabs(windowId))

        MenuActionsHandler.cleanupWindow(windowId)

        assertFalse(MenuActionsHandler.canStepTabs(windowId), "stale state would keep the chord claimed")
    }
}
