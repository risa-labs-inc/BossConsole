package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AWTKeyboardInterceptor.dispatchIfMultiPanel].
 *
 * The default panel-navigation bindings are bare Cmd+Arrow, which macOS also reserves for caret
 * movement. Claiming the chord in a window with nothing to navigate to would take
 * "caret to line start" away from every text field and web page and give back nothing.
 */
class PanelNavigationGateTest {
    @Test
    fun `a single-panel window leaves the chord to the focused component`() {
        val windowId = "gate-single"
        MenuActionsHandler.updatePanelCount(windowId, 1)

        var fired = false
        val handled = AWTKeyboardInterceptor.dispatchIfMultiPanel(windowId) { fired = true }

        assertFalse(handled, "the event must propagate so Cmd+Left still moves the caret")
        assertFalse(fired)
    }

    @Test
    fun `an unknown window is treated as single-panel`() {
        var fired = false
        val handled = AWTKeyboardInterceptor.dispatchIfMultiPanel("gate-never-registered") { fired = true }

        assertFalse(handled)
        assertFalse(fired)
    }

    @Test
    fun `a split window navigates and claims the chord`() {
        val windowId = "gate-split"
        MenuActionsHandler.updatePanelCount(windowId, 2)

        var firedFor: String? = null
        val handled = AWTKeyboardInterceptor.dispatchIfMultiPanel(windowId) { firedFor = it }

        assertTrue(handled)
        assertEquals(windowId, firedFor)
    }
}
