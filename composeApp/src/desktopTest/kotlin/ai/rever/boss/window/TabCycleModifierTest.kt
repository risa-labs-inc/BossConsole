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
 *
 * Since #553 the two primary spellings are the SAME key off macOS, so the cases below that turned
 * on them differing are split by platform rather than asserted unconditionally. The platform pair
 * is covered directly by the tests at the bottom, which take `isMacOS` as a parameter and
 * therefore run both branches on either machine. That split is the point: an inline platform read
 * is what let the off-macOS answer go unexercised until CI, on this very function.
 */
class TabCycleModifierTest {
    // A "Cmd" chord is Meta on macOS and Control elsewhere, unchanged.
    private val cmdKeyCode = if (SystemUtils.isMacOS) KeyEvent.VK_META else KeyEvent.VK_CONTROL

    // A "Ctrl" chord is the Control key on BOTH platforms since #553. It used to be Meta off
    // macOS, which armed a key the user was not holding, so the release never matched and the
    // switcher overlay stayed wedged open with Tab swallowed.
    private val ctrlKeyCode = KeyEvent.VK_CONTROL

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

        assertEquals(cmdKeyCode, AWTKeyboardInterceptor.cyclingModifierKeyCode(alternate))

        if (SystemUtils.isMacOS) {
            assertNotEquals(
                AWTKeyboardInterceptor.cyclingModifierKeyCode(primary),
                AWTKeyboardInterceptor.cyclingModifierKeyCode(alternate),
                "on macOS the two chords are different keys and must arm different ones",
            )
        } else {
            // Off macOS both spellings ARE the Control key, so such an alternate is the same
            // chord written twice rather than a second one. Arming Control for either is correct:
            // it is the only primary key that can be held, since a Super press matches nothing.
            assertEquals(
                AWTKeyboardInterceptor.cyclingModifierKeyCode(primary),
                AWTKeyboardInterceptor.cyclingModifierKeyCode(alternate),
            )
        }
    }

    @Test
    fun `on macOS the spelling chooses the key`() {
        assertEquals(KeyEvent.VK_META, AWTKeyboardInterceptor.cyclingModifierKeyCodeFor(hasCmd = true, isMacOS = true))
        assertEquals(
            KeyEvent.VK_CONTROL,
            AWTKeyboardInterceptor.cyclingModifierKeyCodeFor(hasCmd = false, isMacOS = true),
        )
    }

    @Test
    fun `off macOS both spellings arm Control, the only primary key that can be held`() {
        // The #553 collapse. Arming VK_META for an explicit "Ctrl" chord armed a key that is not
        // down, so the release never matched and the switcher overlay stayed wedged open.
        assertEquals(
            KeyEvent.VK_CONTROL,
            AWTKeyboardInterceptor.cyclingModifierKeyCodeFor(hasCmd = true, isMacOS = false),
        )
        assertEquals(
            KeyEvent.VK_CONTROL,
            AWTKeyboardInterceptor.cyclingModifierKeyCodeFor(hasCmd = false, isMacOS = false),
        )
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
