package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.utils.SystemUtils
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [AWTKeyboardInterceptor.cyclingModifierKeyCode] against the keystroke that actually matched.
 *
 * An MRU tab cycle is sustained by a held modifier and commits when that modifier is released.
 * The interceptor has to arm on the modifier the USER is holding, which since alternates became
 * live is not necessarily the binding's primary: `findMatchingBinding` walks `allKeystrokes`, and
 * an alternate can be spelled with the other primary modifier. Arming the wrong one is not a lost
 * shortcut, it is a wedge - the release never matches, `triggerCommitTabCycle` never fires, and
 * the switcher overlay stays on screen with Tab swallowed until some unrelated release happens to
 * match.
 *
 * No preset gives TAB_NEXT an alternate today. `keymap-settings.json` is hand-editable,
 * `withAlternateKeystroke` is public, and `migrateSettings` propagates any alternate a future
 * preset adds, so this pins the behaviour rather than the current data.
 */
class TabCycleModifierTest {
    private val cmdKeyCode = if (SystemUtils.isMacOS) KeyEvent.VK_META else KeyEvent.VK_CONTROL
    private val ctrlKeyCode = if (SystemUtils.isMacOS) KeyEvent.VK_CONTROL else KeyEvent.VK_META

    @Test
    fun `a Ctrl chord arms the Ctrl key`() {
        assertEquals(ctrlKeyCode, AWTKeyboardInterceptor.cyclingModifierKeyCode(KeyStroke("Tab", listOf("Ctrl"))))
    }

    @Test
    fun `a Cmd chord arms the Cmd key`() {
        assertEquals(cmdKeyCode, AWTKeyboardInterceptor.cyclingModifierKeyCode(KeyStroke("Tab", listOf("Cmd"))))
    }

    @Test
    fun `an alternate with the other primary modifier arms that modifier, not the binding's`() {
        // Ctrl+Tab primary, Cmd+Tab alternate: matching the alternate must arm Cmd. Reading the
        // binding instead of the matched keystroke gives Ctrl here, which never releases.
        val primary = KeyStroke("Tab", listOf("Ctrl"))
        val alternate = KeyStroke("Tab", listOf("Cmd"))

        assertNotEquals(
            AWTKeyboardInterceptor.cyclingModifierKeyCode(primary),
            AWTKeyboardInterceptor.cyclingModifierKeyCode(alternate),
            "the two chords must not arm the same physical key, or this test proves nothing",
        )
        assertEquals(cmdKeyCode, AWTKeyboardInterceptor.cyclingModifierKeyCode(alternate))
    }

    @Test
    fun `modifier spelling does not change the answer`() {
        // Same fold the matchers apply: Meta is Cmd, Control is Ctrl.
        assertEquals(
            AWTKeyboardInterceptor.cyclingModifierKeyCode(KeyStroke("Tab", listOf("Cmd"))),
            AWTKeyboardInterceptor.cyclingModifierKeyCode(KeyStroke("Tab", listOf("Meta"))),
        )
        assertEquals(
            AWTKeyboardInterceptor.cyclingModifierKeyCode(KeyStroke("Tab", listOf("Ctrl"))),
            AWTKeyboardInterceptor.cyclingModifierKeyCode(KeyStroke("Tab", listOf("Control"))),
        )
    }

    @Test
    fun `a Shift-decorated chord still arms on its primary modifier`() {
        // Ctrl+Shift+Tab is the "previous tab" half of the same cycle.
        assertEquals(
            ctrlKeyCode,
            AWTKeyboardInterceptor.cyclingModifierKeyCode(KeyStroke("Tab", listOf("Ctrl", "Shift"))),
        )
    }
}
