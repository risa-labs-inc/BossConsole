package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The held-chord bookkeeping behind [AWTKeyboardInterceptor]: a chord runs once on its press
 * (BossConsole#1568), OS auto-repeat presses of the held key must not run it again
 * (BossConsole#490), and the held state is dropped on release, modifier release and
 * cancellation. [ShortcutKeySemanticsTest] drives the same rules end to end.
 *
 * These drive [AWTKeyboardInterceptor.handleKeyPressed] / [AWTKeyboardInterceptor.handleKeyReleased]
 * directly with synthetic AWT events - the same way [HostBindingPrecedenceTest] drives
 * [AWTKeyboardInterceptor.dispatchAction] directly - rather than going through [install]'s real
 * `KeyEventDispatcher` (which needs a registered AWT `Window`) or real chord matching (which
 * reads the on-disk keymap via `KeymapSettingsManager`, and so differs between a dev machine
 * and CI - the same reason [TabStepGateTest] and friends avoid it).
 * [AWTKeyboardInterceptor.pendingShortcuts] is set directly to arm a known chord instead.
 */
class ShortcutKeyInvocationTest {
    @Test
    fun `native print cancels only its own pending release`() {
        val binding =
            AWTKeyboardInterceptor.BindingMatch(
                KeyBinding(actionId = KeymapActions.BROWSER_PRINT, key = "P", modifiers = listOf("Cmd")),
                KeyStroke("P", listOf("Cmd")),
            )
        val pending =
            AWTKeyboardInterceptor.PendingShortcut(
                keyCode = KeyEvent.VK_P,
                windowId = "native-print",
                hostBinding = binding,
            )
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_P] = pending
        AWTKeyboardInterceptor.claimedKeys.add(KeyEvent.VK_P)
        AWTKeyboardInterceptor.cancelPendingNativePrint("another-window")
        assertEquals(pending, AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_P])
        AWTKeyboardInterceptor.cancelPendingNativePrint("native-print")
        assertNull(AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_P])
        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_P)))
        assertFalse(AWTKeyboardInterceptor.claimedKeys.contains(KeyEvent.VK_P))
    }

    @Test
    fun `native print does not cancel another action bound to P`() {
        val pending = pendingFor("native-print", keyCode = KeyEvent.VK_P)
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_P] = pending
        AWTKeyboardInterceptor.cancelPendingNativePrint("native-print")
        assertEquals(pending, AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_P])
    }

    private val source = Canvas()

    private fun keyEvent(
        id: Int,
        keyCode: Int,
        modifiers: Int = 0,
    ): KeyEvent = KeyEvent(source, id, System.currentTimeMillis(), modifiers, keyCode, keyCode.toChar())

    private fun tabNewBinding() =
        AWTKeyboardInterceptor.BindingMatch(
            KeyBinding(actionId = KeymapActions.TAB_NEW, key = "N", modifiers = listOf("Cmd")),
            KeyStroke("N", listOf("Cmd")),
        )

    private fun pendingFor(
        windowId: String,
        keyCode: Int = KeyEvent.VK_N,
        metaDown: Boolean = true,
    ) = AWTKeyboardInterceptor.PendingShortcut(
        keyCode = keyCode,
        windowId = windowId,
        hostBinding = tabNewBinding(),
        metaDown = metaDown,
    )

    @AfterTest
    fun clearPending() {
        AWTKeyboardInterceptor.cancelPendingShortcut()
    }

    @Test
    fun `a held chord's release is consumed once and clears it`() {
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N] = pendingFor("keyup-once")

        val release = keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)
        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(release), "the first release belongs to the chord")
        assertNull(AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N], "the release must clear the held chord")
        assertFalse(
            AWTKeyboardInterceptor.handleKeyReleased(release),
            "a second release of the same key is not ours",
        )
    }

    @Test
    fun `a release of a different key leaves the held chord alone`() {
        val pending = pendingFor("keyup-mismatch")
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N] = pending

        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_W)))
        assertEquals(
            pending,
            AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N],
            "an unrelated release must leave the held chord alone",
        )
    }

    @Test
    fun `releasing a modifier drops the held record but is not itself consumed`() {
        // A modifier can never itself be the held keyCode - handleKeyPressed rejects a
        // modifier-only press (see the test below) - so its release is not consumed. Dropping
        // the record is what keeps a still-held key's bare repeats swallowed rather than
        // re-matched (see ShortcutKeySemanticsTest).
        val pending = pendingFor("keyup-modifier")
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N] = pending

        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_CONTROL)))
        assertNull(AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N])
    }

    @Test
    fun `with no chord armed, a key-up does nothing`() {
        assertTrue(AWTKeyboardInterceptor.pendingShortcuts.isEmpty())
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
    }

    @Test
    fun `a repeat key-down for the held key is claimed without re-matching or firing`() {
        val windowId = "keyup-repeat"
        val pending = pendingFor(windowId)
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N] = pending

        // OS auto-repeat delivers KEY_PRESSED again and again while a key is held; each one
        // must stay claimed (so it doesn't leak to the focused component) without firing.
        val repeatPress = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_N, InputEvent.META_DOWN_MASK)
        repeat(5) {
            val claimed = AWTKeyboardInterceptor.handleKeyPressed(repeatPress, windowId)
            assertTrue(claimed, "a repeat press must stay claimed")
        }
        assertEquals(
            pending,
            AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N],
            "repeats must not re-match or replace the held chord",
        )

        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
        assertNull(AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N])
    }

    @Test
    fun `a key-down with no modifier held is never claimed`() {
        val press = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_N, 0)
        assertFalse(AWTKeyboardInterceptor.handleKeyPressed(press, "keyup-nomod"))
        assertNull(AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N])
    }

    @Test
    fun `a bare modifier key-down is never claimed`() {
        val press = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK)
        assertFalse(AWTKeyboardInterceptor.handleKeyPressed(press, "keyup-modonly"))
        assertNull(AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_CONTROL])
    }

    @Test
    fun `a stale held chord with different modifiers does not swallow a later bare keystroke`() {
        // Simulates a lost KEY_RELEASED: the chord is still held with Cmd down, but the user
        // has since let go of everything and is now typing 'n' on its own. Regression for the
        // review's finding #2 on BossConsole#490 - the repeat-claim check used to key on keyCode
        // alone, so this bare press matched the stale entry and was silently swallowed.
        //
        // Only the repeat-claim check itself is exercised here (via handleKeyPressed), not the
        // full re-match-and-arm path below it - that reads the real on-disk keymap via
        // findMatchingBinding, which this suite deliberately avoids depending on (see the class
        // KDoc). A bare, unmodified press has no binding to match regardless of what the keymap
        // says, so handleKeyPressed correctly returns false here either way.
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N] = pendingFor("keyup-stale", metaDown = true)

        val barePress = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_N, 0)
        assertFalse(
            AWTKeyboardInterceptor.handleKeyPressed(barePress, "keyup-stale"),
            "a bare press must fall through to the modifier gate, not match the stale entry",
        )

        // A stale action must be removed, not merely skipped during the new press.
        assertNull(AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N])
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N] = pendingFor("keyup-fresh", metaDown = true)
        assertEquals("keyup-fresh", AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N]?.windowId)
    }

    @Test
    fun `two chords on different keys are held independently - one does not evict the other`() {
        // Regression for the review's finding #3 on BossConsole#490: rolling Cmd+N into Cmd+T
        // without fully releasing N used to overwrite a single slot, so N's release leaked to the
        // focused component with no matching key-down to explain it. Held by hand rather than
        // through handleKeyPressed's real-keymap matching, per this suite's usual pattern.
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N] = pendingFor("keyup-roll", metaDown = true)
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_T] =
            AWTKeyboardInterceptor.PendingShortcut(
                keyCode = KeyEvent.VK_T,
                windowId = "keyup-roll",
                hostBinding =
                    AWTKeyboardInterceptor.BindingMatch(
                        KeyBinding(actionId = KeymapActions.TAB_CLOSE, key = "T", modifiers = listOf("Cmd")),
                        KeyStroke("T", listOf("Cmd")),
                    ),
                metaDown = true,
            )

        assertEquals(2, AWTKeyboardInterceptor.pendingShortcuts.size, "both chords must stay held")

        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_T)))
        assertTrue(
            AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)),
            "the first chord must still own its release, not have been silently dropped",
        )
        assertTrue(AWTKeyboardInterceptor.pendingShortcuts.isEmpty())
    }

    @Test
    fun `cancelling drops a held chord so its eventual release is not claimed`() {
        AWTKeyboardInterceptor.pendingShortcuts[KeyEvent.VK_N] = pendingFor("keyup-cancel")

        AWTKeyboardInterceptor.cancelPendingShortcut()

        assertTrue(AWTKeyboardInterceptor.pendingShortcuts.isEmpty())
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
    }
}
