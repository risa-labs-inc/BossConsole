package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeldShortcutRegistryTest {
    private val source = Canvas()

    private fun event(
        keyCode: Int = KeyEvent.VK_N,
        modifiers: Int = 0,
        whenTime: Long = 0L,
    ) = KeyEvent(source, KeyEvent.KEY_PRESSED, whenTime, modifiers, keyCode, keyCode.toChar())

    private fun held(
        keyCode: Int = KeyEvent.VK_N,
        windowId: String = "window-a",
        modifiers: AwtModifierSnapshot = AwtModifierSnapshot(metaDown = true),
        actionId: String = KeymapActions.TAB_NEW,
    ) = HeldShortcut(
        keyCode = keyCode,
        windowId = windowId,
        hostBinding =
            AWTKeyboardInterceptor.BindingMatch(
                KeyBinding(actionId = actionId, key = keyCode.toChar().toString(), modifiers = listOf("Cmd")),
                KeyStroke(keyCode.toChar().toString(), listOf("Cmd")),
            ),
        modifiers = modifiers,
    )

    @Test
    fun `claim captures the event modifiers instead of trusting its template`() {
        val registry = HeldShortcutRegistry()
        val claimed = registry.claim(held(), event(modifiers = InputEvent.CTRL_DOWN_MASK))

        assertEquals(AwtModifierSnapshot(controlDown = true), claimed.modifiers)
        assertEquals(claimed, registry[KeyEvent.VK_N])
    }

    @Test
    fun `same window and modifiers identify an OS repeat`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held())

        assertTrue(registry.claimsRepeat(event(modifiers = InputEvent.META_DOWN_MASK), "window-a"))
        assertNotNull(registry[KeyEvent.VK_N])
    }

    @Test
    fun `another window makes a held record stale`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held())

        assertFalse(registry.claimsRepeat(event(modifiers = InputEvent.META_DOWN_MASK), "window-b"))
        assertTrue(registry.hasNoHeldKeys)
    }

    @Test
    fun `another modifier combination makes a held record stale`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held())

        assertFalse(registry.claimsRepeat(event(modifiers = InputEvent.CTRL_DOWN_MASK), "window-a"))
        assertTrue(registry.hasNoHeldKeys)
    }

    @Test
    fun `own modifier release keeps ownership with the post-release identity`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held())

        registry.modifierReleased(event(KeyEvent.VK_META))

        assertEquals(AwtModifierSnapshot(), registry[KeyEvent.VK_N]?.modifiers)
        assertTrue(registry.claimsRepeat(event(), "window-a"))
    }

    @Test
    fun `unrelated modifier release leaves the chord identity unchanged`() {
        val registry = HeldShortcutRegistry()
        val shortcut = held()
        registry.claim(shortcut)

        registry.modifierReleased(event(KeyEvent.VK_SHIFT, InputEvent.META_DOWN_MASK))

        assertEquals(shortcut, registry[KeyEvent.VK_N])
    }

    @Test
    fun `lock-key release cannot alter a held chord`() {
        val registry = HeldShortcutRegistry()
        val shortcut = held()
        registry.claim(shortcut)

        registry.modifierReleased(event(KeyEvent.VK_CAPS_LOCK, InputEvent.META_DOWN_MASK))

        assertEquals(shortcut, registry[KeyEvent.VK_N])
    }

    @Test
    fun `primary release returns and clears exactly its record`() {
        val registry = HeldShortcutRegistry()
        val shortcut = held()
        registry.claim(shortcut)

        assertEquals(shortcut, registry.release(KeyEvent.VK_N))
        assertNull(registry.release(KeyEvent.VK_N))
    }

    @Test
    fun `unclaim is compare-and-remove`() {
        val registry = HeldShortcutRegistry()
        val original = held(windowId = "old")
        val replacement = held(windowId = "new")
        registry.claim(replacement)

        registry.unclaim(original)

        assertEquals(replacement, registry[KeyEvent.VK_N])
    }

    @Test
    fun `matching native print disarms the release action without dropping ownership`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held(keyCode = KeyEvent.VK_P, actionId = KeymapActions.BROWSER_PRINT))

        assertTrue(registry.claimNativePrint("window-a"))

        val print = assertNotNull(registry[KeyEvent.VK_P])
        assertTrue(print.firesOnRelease)
        assertFalse(print.releaseActionArmed)
    }

    @Test
    fun `native print from another window cannot disarm this window`() {
        val registry = HeldShortcutRegistry()
        val print = held(keyCode = KeyEvent.VK_P, actionId = KeymapActions.BROWSER_PRINT)
        registry.claim(print)

        assertFalse(registry.claimNativePrint("window-b"))

        assertEquals(print, registry[KeyEvent.VK_P])
    }

    @Test
    fun `native print cannot disarm a non-print P binding`() {
        val registry = HeldShortcutRegistry()
        val shortcut = held(keyCode = KeyEvent.VK_P)
        registry.claim(shortcut)

        assertFalse(registry.claimNativePrint("window-a"))

        assertEquals(shortcut, registry[KeyEvent.VK_P])
    }

    @Test
    fun `window removal leaves other windows and keys intact`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held(KeyEvent.VK_N, "window-a"))
        registry.claim(held(KeyEvent.VK_T, "window-b"))

        registry.removeWindow("window-a")

        assertNull(registry[KeyEvent.VK_N])
        assertNotNull(registry[KeyEvent.VK_T])
    }

    @Test
    fun `overlapping chords update independently on modifier release`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held(KeyEvent.VK_N, modifiers = AwtModifierSnapshot(metaDown = true)))
        registry.claim(held(KeyEvent.VK_T, modifiers = AwtModifierSnapshot(controlDown = true)))

        registry.modifierReleased(event(KeyEvent.VK_META))

        assertEquals(AwtModifierSnapshot(), registry[KeyEvent.VK_N]?.modifiers)
        assertEquals(AwtModifierSnapshot(controlDown = true), registry[KeyEvent.VK_T]?.modifiers)
    }

    @Test
    fun `clear releases every physical key claim`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held(KeyEvent.VK_N))
        registry.claim(held(KeyEvent.VK_T))

        registry.clear()

        assertTrue(registry.hasNoHeldKeys)
    }

    @Test
    fun `native callback racing primary release produces exactly one winner`() {
        repeat(100) {
            val registry = HeldShortcutRegistry()
            registry.claim(held(keyCode = KeyEvent.VK_P, actionId = KeymapActions.BROWSER_PRINT))
            val start = CountDownLatch(1)
            var released: HeldShortcut? = null
            var nativeWon = false
            val native =
                thread(start = true) {
                    start.await()
                    nativeWon = registry.claimNativePrint("window-a")
                }
            val release =
                thread(start = true) {
                    start.await()
                    released = registry.release(KeyEvent.VK_P)
                }
            start.countDown()
            native.join()
            release.join()

            assertNotNull(released)
            val awtWon = released?.releaseActionArmed == true
            assertEquals(1, listOf(nativeWon, awtWon).count { it })
            assertTrue(registry.hasNoHeldKeys)
        }
    }

    @Test
    fun `same-press native duplicate loses before native key-up`() {
        val registry = HeldShortcutRegistry()

        assertTrue(registry.claimNativePrint("window-a"))
        val claimed = registry.claim(held(keyCode = KeyEvent.VK_P, actionId = KeymapActions.BROWSER_PRINT))
        val released = assertNotNull(registry.release(KeyEvent.VK_P))

        assertFalse(claimed.releaseActionArmed)
        assertFalse(released.releaseActionArmed)
        assertFalse(registry.claimNativePrint("window-a"), "the same physical press cannot print twice")
    }

    @Test
    fun `native key-up lets a second native-only press print`() {
        val registry = HeldShortcutRegistry()

        assertTrue(registry.claimNativePrint("window-a"))
        registry.releaseNativePrint("window-a")
        assertTrue(registry.claimNativePrint("window-a"), "the next physical press must get a fresh winner")
        registry.releaseNativePrint("window-a")
    }

    @Test
    fun `native key-up keeps a lagging AWT press from printing again`() {
        val registry = HeldShortcutRegistry()

        val nativeWon = registry.claimNativePrint("window-a")
        registry.releaseNativePrint("window-a")
        val claimed = registry.claim(held(keyCode = KeyEvent.VK_P, actionId = KeymapActions.BROWSER_PRINT))
        val released = assertNotNull(registry.release(KeyEvent.VK_P))

        assertTrue(nativeWon)
        assertFalse(claimed.releaseActionArmed)
        assertFalse(released.releaseActionArmed)
        assertEquals(1, listOf(nativeWon, released.releaseActionArmed).count { it })
    }

    @Test
    fun `foreign native marker is replaced rather than suppressing another window`() {
        val registry = HeldShortcutRegistry()

        assertTrue(registry.claimNativePrint("window-a"))
        assertTrue(registry.claimNativePrint("window-b"))
        registry.releaseNativePrint("window-a")
        assertFalse(
            registry.claimNativePrint("window-b"),
            "window-b active winner must remain active after foreign window-a release",
        )
        registry.releaseNativePrint("window-b")
        assertTrue(registry.claimNativePrint("window-b"))
    }

    @Test
    fun `AWT winner before native callback refuses the late callback`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held(keyCode = KeyEvent.VK_P, actionId = KeymapActions.BROWSER_PRINT))

        val released = assertNotNull(registry.release(KeyEvent.VK_P))

        assertTrue(released.releaseActionArmed)
        assertFalse(registry.claimNativePrint("window-a"))
    }

    @Test
    fun `modifier release clears its own modifier bit even if event mask retains it`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held())

        registry.modifierReleased(event(KeyEvent.VK_META, InputEvent.META_DOWN_MASK))

        assertEquals(AwtModifierSnapshot(metaDown = false), registry[KeyEvent.VK_N]?.modifiers)
        assertTrue(registry.claimsRepeat(event(), "window-a"))
    }

    @Test
    fun `repeat within timeout is consumed but press after timeout is treated as fresh`() {
        val registry = HeldShortcutRegistry()
        val shortcut = held()
        registry.claim(shortcut, event(modifiers = InputEvent.META_DOWN_MASK, whenTime = 1000L))

        val repeatEvent = event(modifiers = InputEvent.META_DOWN_MASK, whenTime = 1040L)
        assertTrue(registry.claimsRepeat(repeatEvent, "window-a"), "repeat within timeout must be claimed")

        val latePressEvent = event(modifiers = InputEvent.META_DOWN_MASK, whenTime = 3040L)
        assertFalse(
            registry.claimsRepeat(latePressEvent, "window-a"),
            "press after timeout gap must not be treated as repeat",
        )
        assertTrue(registry.hasNoHeldKeys, "stale held record must be removed on timeout expiry")
    }
}
