package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.presets.KeymapPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host-binding vs plugin-default precedence rule in [AWTKeyboardInterceptor].
 *
 * [ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl] documents that plugin
 * defaults apply only where no host binding matched, and that host bindings always win. That held
 * only for host actions the interceptor can dispatch itself. Some host bindings exist purely so a
 * chord is listed and rebindable while something else serves it, and for those `dispatchAction`
 * returns false. Falling through to the plugin-default pass then inverts the rule: a plugin
 * registering the same chord as a GLOBAL default shadows the host binding and consumes the event
 * (a plugin's `onAction` returns Unit, so `PluginShortcutRegistryImpl.dispatch` reports success
 * for anything registered), so the real handler never sees the key.
 *
 * Which bindings actually reach that branch is narrower than it first looks, and the tests below
 * pin it: only GLOBAL ones do.
 */
class HostBindingPrecedenceTest {
    @Test
    fun `the GLOBAL actions with no dispatch case are not claimed by the interceptor`() {
        // These two are what the early return really protects: bound GLOBAL, so context
        // filtering lets them through to dispatchAction, and served by neither the interceptor
        // nor anything it can reach.
        listOf(KeymapActions.QUICK_SWITCHER_OPEN, KeymapActions.TEST_EXTERNAL_LINK).forEach { actionId ->
            val binding = assertNotNull(KeymapPresets.getBOSSDefault().getBinding(actionId), actionId)
            assertEquals(ShortcutContext.GLOBAL, binding.context, actionId)
            assertFalse(
                AWTKeyboardInterceptor.dispatchAction(actionId, "window-1"),
                "$actionId must be reported unhandled so the event keeps propagating",
            )
        }
    }

    @Test
    fun `an EDITOR-context binding cannot reach dispatchAction in the first place`() {
        // detectCurrentContext answers only BROWSER, TERMINAL or GLOBAL - updateWindowContext
        // has no callers, so the AWT focus walk is the only source and it recognises just those
        // two component families. isContextEligible therefore drops every EDITOR binding inside
        // findMatchingBinding, before dispatch.
        //
        // Worth pinning because it moves where a fix lives: EDITOR_GO_TO_LINE (Cmd+L) is kept
        // safe from a plugin GLOBAL default by the fluck browser not registering one, not by
        // the undispatched-host-binding early return.
        val goToLine = assertNotNull(KeymapPresets.getBOSSDefault().getBinding(KeymapActions.EDITOR_GO_TO_LINE))
        assertEquals(ShortcutContext.EDITOR, goToLine.context)

        // And it still has no dispatch case, which is what makes it look like the example.
        assertFalse(AWTKeyboardInterceptor.dispatchAction(KeymapActions.EDITOR_GO_TO_LINE, "window-1"))
    }

    @Test
    fun `host actions the interceptor does own are still claimed`() {
        // The counterpart: a real host action must keep returning true, or the same change would
        // stop the interceptor consuming chords it genuinely serves.
        assertTrue(AWTKeyboardInterceptor.dispatchAction(KeymapActions.TAB_NEW, "window-1"))
        assertTrue(AWTKeyboardInterceptor.dispatchAction(KeymapActions.BROWSER_DEVTOOLS, "window-1"))
        assertTrue(AWTKeyboardInterceptor.dispatchAction(KeymapActions.TAB_CLOSE, "window-1"))
    }

    @Test
    fun `an unknown action is unhandled`() {
        assertFalse(AWTKeyboardInterceptor.dispatchAction("nonsense.action", "window-1"))
    }
}
