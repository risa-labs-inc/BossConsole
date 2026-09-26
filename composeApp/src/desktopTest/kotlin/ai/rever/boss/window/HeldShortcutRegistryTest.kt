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
    ) = KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, keyCode, keyCode.toChar())

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
        assertTrue(registry.isEmpty())
    }

    @Test
    fun `another modifier combination makes a held record stale`() {
        val registry = HeldShortcutRegistry()
        registry.claim(held())

        assertFalse(registry.claimsRepeat(event(modifiers = InputEvent.CTRL_DOWN_MASK), "window-a"))
        assertTrue(registry.isEmpty())
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

        registry.disarmNativePrint("window-a")

        val print = assertNotNull(registry[KeyEvent.VK_P])
        assertTrue(print.firesOnRelease)
        assertFalse(print.releaseActionArmed)
    }

    @Test
    fun `native print from another window cannot disarm this window`() {
        val registry = HeldShortcutRegistry()
        val print = held(keyCode = KeyEvent.VK_P, actionId = KeymapActions.BROWSER_PRINT)
        registry.claim(print)

        registry.disarmNativePrint("window-b")

        assertEquals(print, registry[KeyEvent.VK_P])
    }

    @Test
    fun `native print cannot disarm a non-print P binding`() {
        val registry = HeldShortcutRegistry()
        val shortcut = held(keyCode = KeyEvent.VK_P)
        registry.claim(shortcut)

        registry.disarmNativePrint("window-a")

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

        assertTrue(registry.isEmpty())
    }

    @Test
    fun `native cancellation racing primary release has one atomic outcome`() {
        repeat(100) {
            val registry = HeldShortcutRegistry()
            registry.claim(held(keyCode = KeyEvent.VK_P, actionId = KeymapActions.BROWSER_PRINT))
            val start = CountDownLatch(1)
            var released: HeldShortcut? = null
            val cancel =
                thread(start = true) {
                    start.await()
                    registry.disarmNativePrint("window-a")
                }
            val release =
                thread(start = true) {
                    start.await()
                    released = registry.release(KeyEvent.VK_P)
                }
            start.countDown()
            cancel.join()
            release.join()

            assertNotNull(released)
            assertTrue(registry.isEmpty())
        }
    }
}
