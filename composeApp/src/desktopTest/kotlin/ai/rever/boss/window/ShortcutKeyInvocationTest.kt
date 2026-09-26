package ai.rever.boss.window

import ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.plugin.api.ShortcutActionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The held-chord bookkeeping behind [AWTKeyboardInterceptor]: a chord runs once on its press
 * (BossConsole#1568), OS auto-repeat presses of the held key must not run it again
 * (BossConsole#490), modifier release preserves physical ownership, and primary release or
 * cancellation drops it. [ShortcutKeySemanticsTest] drives the same rules end to end.
 *
 * These drive [AWTKeyboardInterceptor.handleKeyPressed] / [AWTKeyboardInterceptor.handleKeyReleased]
 * directly with synthetic AWT events - the same way [HostBindingPrecedenceTest] drives
 * [AWTKeyboardInterceptor.dispatchAction] directly - rather than going through [install]'s real
 * `KeyEventDispatcher` (which needs a registered AWT `Window`) or real chord matching (which
 * reads the on-disk keymap via `KeymapSettingsManager`, and so differs between a dev machine
 * and CI - the same reason [TabStepGateTest] and friends avoid it).
 * [AWTKeyboardInterceptor.heldShortcuts] is seeded directly to own a known chord instead.
 */
class ShortcutKeyInvocationTest {
    private lateinit var testScope: CoroutineScope
    private lateinit var newTabCollector: Job
    private val newTabEvents = AtomicInteger(0)

    @BeforeTest
    fun resetPending() {
        AWTKeyboardInterceptor.cancelPendingShortcut()
        newTabEvents.set(0)
        testScope = CoroutineScope(Dispatchers.Unconfined)
        newTabCollector =
            testScope.launch {
                MenuActionsHandler.newTabEvents.collect { newTabEvents.incrementAndGet() }
            }
    }

    @Test
    fun `native print cancels only its own pending release`() {
        val binding =
            AWTKeyboardInterceptor.BindingMatch(
                KeyBinding(actionId = KeymapActions.BROWSER_PRINT, key = "P", modifiers = listOf("Cmd")),
                KeyStroke("P", listOf("Cmd")),
            )
        val pending =
            HeldShortcut(
                keyCode = KeyEvent.VK_P,
                windowId = "native-print",
                hostBinding = binding,
            )
        AWTKeyboardInterceptor.heldShortcuts.claim(pending)
        AWTKeyboardInterceptor.cancelPendingNativePrint("another-window")
        assertEquals(pending, AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_P])
        AWTKeyboardInterceptor.cancelPendingNativePrint("native-print")
        assertFalse(AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_P]?.releaseActionArmed ?: true)
        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_P)))
        assertNull(AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_P])
    }

    @Test
    fun `native print does not cancel another action bound to P`() {
        val pending = pendingFor("native-print", keyCode = KeyEvent.VK_P)
        AWTKeyboardInterceptor.heldShortcuts.claim(pending)
        AWTKeyboardInterceptor.cancelPendingNativePrint("native-print")
        assertEquals(pending, AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_P])
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
    ) = HeldShortcut(
        keyCode = keyCode,
        windowId = windowId,
        hostBinding = tabNewBinding(),
        modifiers = AwtModifierSnapshot(metaDown = metaDown),
    )

    @AfterTest
    fun clearPending() {
        AWTKeyboardInterceptor.cancelPendingShortcut()
        newTabCollector.cancel()
        testScope.cancel()
    }

    @Test
    fun `a held chord's release is consumed once and clears it`() {
        AWTKeyboardInterceptor.heldShortcuts.claim(pendingFor("keyup-once"))

        val release = keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)
        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(release), "the first release belongs to the chord")
        assertNull(AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N], "the release must clear the held chord")
        assertEquals(0, newTabEvents.get(), "a normal host action must not be delivered again on release")
        assertFalse(
            AWTKeyboardInterceptor.handleKeyReleased(release),
            "a second release of the same key is not ours",
        )
    }

    @Test
    fun `a held plugin shortcut dispatches nothing on release`() {
        var calls = 0
        val provider =
            object : ShortcutActionProvider {
                override val providerId = "held-plugin-release"

                override fun shortcuts() = emptyList<ai.rever.boss.plugin.api.PluginShortcutSpec>()

                override fun onAction(
                    actionId: String,
                    windowId: String?,
                ) {
                    calls++
                }
            }
        PluginShortcutRegistryImpl.register(provider)
        try {
            AWTKeyboardInterceptor.heldShortcuts.claim(
                HeldShortcut(
                    keyCode = KeyEvent.VK_K,
                    windowId = "plugin-release",
                    pluginActionId = "plugin.release.action",
                ),
            )

            assertTrue(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_K)))
            assertEquals(0, calls, "plugin actions run on press only")
            assertNull(AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_K])
        } finally {
            PluginShortcutRegistryImpl.unregister(provider.providerId)
        }
    }

    @Test
    fun `a release of a different key leaves the held chord alone`() {
        val pending = pendingFor("keyup-mismatch")
        AWTKeyboardInterceptor.heldShortcuts.claim(pending)

        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_W)))
        assertEquals(
            pending,
            AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N],
            "an unrelated release must leave the held chord alone",
        )
    }

    @Test
    fun `releasing a chord modifier retains its ownership record and is not itself consumed`() {
        // A modifier can never itself be the held keyCode - handleKeyPressed rejects a
        // modifier-only press (see the test below) - so its release is not consumed. The
        // record stays to preserve ownership until the primary release.
        val pending = pendingFor("keyup-modifier")
        AWTKeyboardInterceptor.heldShortcuts.claim(pending)

        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_META)))
        assertEquals(
            pending.copy(modifiers = AwtModifierSnapshot()),
            AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N],
        )
    }

    @Test
    fun `with no chord armed, a key-up does nothing`() {
        assertTrue(AWTKeyboardInterceptor.heldShortcuts.isEmpty())
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
    }

    @Test
    fun `a repeat key-down for the held key is claimed without re-matching or firing`() {
        val windowId = "keyup-repeat"
        val pending = pendingFor(windowId)
        AWTKeyboardInterceptor.heldShortcuts.claim(pending)

        // OS auto-repeat delivers KEY_PRESSED again and again while a key is held; each one
        // must stay claimed (so it doesn't leak to the focused component) without firing.
        val repeatPress = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_N, InputEvent.META_DOWN_MASK)
        repeat(5) {
            val claimed = AWTKeyboardInterceptor.handleKeyPressed(repeatPress, windowId)
            assertTrue(claimed, "a repeat press must stay claimed")
        }
        assertEquals(
            pending,
            AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N],
            "repeats must not re-match or replace the held chord",
        )

        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
        assertNull(AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N])
    }

    @Test
    fun `a key-down with no modifier held is never claimed`() {
        val press = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_N, 0)
        assertFalse(AWTKeyboardInterceptor.handleKeyPressed(press, "keyup-nomod"))
        assertNull(AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N])
    }

    @Test
    fun `a bare modifier key-down is never claimed`() {
        val press = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK)
        assertFalse(AWTKeyboardInterceptor.handleKeyPressed(press, "keyup-modonly"))
        assertNull(AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_CONTROL])
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
        AWTKeyboardInterceptor.heldShortcuts.claim(pendingFor("keyup-stale", metaDown = true))

        val barePress = keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_N, 0)
        assertFalse(
            AWTKeyboardInterceptor.handleKeyPressed(barePress, "keyup-stale"),
            "a bare press must fall through to the modifier gate, not match the stale entry",
        )

        // A stale action must be removed, not merely skipped during the new press.
        assertNull(AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N])
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
        AWTKeyboardInterceptor.heldShortcuts.claim(pendingFor("keyup-fresh", metaDown = true))
        assertEquals("keyup-fresh", AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N]?.windowId)
    }

    @Test
    fun `two chords on different keys are held independently - one does not evict the other`() {
        // Regression for the review's finding #3 on BossConsole#490: rolling Cmd+N into Cmd+T
        // without fully releasing N used to overwrite a single slot, so N's release leaked to the
        // focused component with no matching key-down to explain it. Held by hand rather than
        // through handleKeyPressed's real-keymap matching, per this suite's usual pattern.
        AWTKeyboardInterceptor.heldShortcuts.claim(pendingFor("keyup-roll", metaDown = true))
        AWTKeyboardInterceptor.heldShortcuts.claim(
            HeldShortcut(
                keyCode = KeyEvent.VK_T,
                windowId = "keyup-roll",
                hostBinding =
                    AWTKeyboardInterceptor.BindingMatch(
                        KeyBinding(actionId = KeymapActions.TAB_CLOSE, key = "T", modifiers = listOf("Cmd")),
                        KeyStroke("T", listOf("Cmd")),
                    ),
                modifiers = AwtModifierSnapshot(metaDown = true),
            ),
        )

        assertEquals("keyup-roll", AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_N]?.windowId)
        assertEquals("keyup-roll", AWTKeyboardInterceptor.heldShortcuts[KeyEvent.VK_T]?.windowId)

        assertTrue(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_T)))
        assertTrue(
            AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)),
            "the first chord must still own its release, not have been silently dropped",
        )
        assertTrue(AWTKeyboardInterceptor.heldShortcuts.isEmpty())
    }

    @Test
    fun `cancelling drops a held chord so its eventual release is not claimed`() {
        AWTKeyboardInterceptor.heldShortcuts.claim(pendingFor("keyup-cancel"))

        AWTKeyboardInterceptor.cancelPendingShortcut()

        assertTrue(AWTKeyboardInterceptor.heldShortcuts.isEmpty())
        assertFalse(AWTKeyboardInterceptor.handleKeyReleased(keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_N)))
    }
}
