package ai.rever.boss.window

import ai.rever.boss.plugin.browser.BrowserAddressBarState
import ai.rever.boss.sharing.AppInputEvent
import ai.rever.boss.window.MacToolbarRuntime.pointer
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
class MacAddressLayoutSmokeTest {
    @Test
    fun `address grows with its window and scrolls to the caret in a long URL`() {
        lateinit var window: ComposeWindow
        lateinit var controller: MacSidebarToolbar
        SwingUtilities.invokeAndWait {
            window =
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(1000, 240)
                    setContent { }
                    isVisible = true
                }
            controller = MacSidebarToolbar(window.windowHandle, {}, {})
        }
        try {
            val url = "https://example.com/" + "long-path/".repeat(80)
            controller.update("", browserActions(url, 0), false, -1, emptyMap())
            val firstWidth = awaitWidth(controller, window, 550)
            SwingUtilities.invokeAndWait { window.setSize(1440, 240) }
            val wider = awaitWidth(controller, window, firstWidth + 300)
            assertTrue(wider < 1440)
            controller.focusAddress()
            onAppKit { Unit }
            controller.update("", browserActions(url, 1), false, -1, emptyMap())
            onAppKit {
                val editing = controller.addressField.editing
                val editor = assertNotNull(pointer(editing.view, "currentEditor"))
                assertCaretVisible(controller, window, editor, url.length)
                // Local native movement must scroll back to the start and then the end.
                MacToolbarRuntime.send(editor, "moveToBeginningOfLine:", null)
                assertCaretVisible(controller, window, editor, 0)
                MacToolbarRuntime.send(editor, "moveToEndOfLine:", null)
                assertCaretVisible(controller, window, editor, url.length)
                // Shared-window keys use the same native editor, including horizontal scrolling.
                remoteKey(editing, "Home", "Home")
                assertCaretVisible(controller, window, editor, 0)
                remoteKey(editing, "End", "End")
                remoteKey(editing, "KeyX", "x")
                assertCaretVisible(controller, window, editor, url.length + 1)
            }
        } finally {
            controller.close()
            onAppKit { Unit }
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    private fun remoteKey(
        editing: MacAddressEditing,
        code: String,
        key: String,
    ) {
        assertTrue(editNativeAddress(editing, AppInputEvent.Key("down", code, key, false, false, false, false)))
    }

    private fun assertCaretVisible(
        controller: MacSidebarToolbar,
        window: ComposeWindow,
        editor: Pointer,
        position: Int,
    ) {
        Memory(16).use { range ->
            val boxed = pointer(editor, "valueForKey:", MacToolbarRuntime.string("selectedRange"))
            MacToolbarRuntime.send(boxed, "getValue:size:", range, 16L)
            assertEquals(position.toLong(), range.getLong(0))
            assertEquals(0L, range.getLong(8))
        }
        val field = assertNotNull(controller.addressField.editing.view)
        val bounds = assertNotNull(nativeAddressBounds(field, Pointer(window.windowHandle)))
        Memory(32).use { rect ->
            val boxed = pointer(Pointer(window.windowHandle), "valueForKey:", MacToolbarRuntime.string("frame"))
            MacToolbarRuntime.send(boxed, "getValue:size:", rect, 32L)
            val caret = caretScreenRect(editor, position)
            val x = caret[0] - rect.getDouble(0)
            assertTrue(x in bounds.left.toDouble()..bounds.right.toDouble(), "Caret $x outside $bounds")
            val centerY = rect.getDouble(8) + rect.getDouble(24) - caret[1] - caret[3] / 2
            val fieldCenterY = (bounds.top + bounds.bottom) / 2.0
            assertTrue(kotlin.math.abs(centerY - fieldCenterY) <= 1.5, "Caret must be vertically centered")
        }
    }

    private fun caretScreenRect(
        editor: Pointer,
        position: Int,
    ): DoubleArray {
        val method = MacToolbarRuntime.selector("firstRectForCharacterRange:actualRange:")
        val signature = pointer(editor, "methodSignatureForSelector:", method)
        val invocation = pointer(MacToolbarRuntime.clazz("NSInvocation"), "invocationWithMethodSignature:", signature)
        return Memory(32).use { rect ->
            Memory(16).use { range ->
                range.setLong(0, position.toLong())
                range.setLong(8, 0L)
                Memory(8).use { actual ->
                    actual.setPointer(0, range)
                    MacToolbarRuntime.send(invocation, "setTarget:", editor)
                    MacToolbarRuntime.send(invocation, "setSelector:", method)
                    MacToolbarRuntime.send(invocation, "setArgument:atIndex:", range, 2L)
                    MacToolbarRuntime.send(invocation, "setArgument:atIndex:", actual, 3L)
                    MacToolbarRuntime.send(invocation, "invoke")
                    MacToolbarRuntime.send(invocation, "getReturnValue:", rect)
                }
            }
            DoubleArray(4) { rect.getDouble(it * 8L) }
        }
    }

    private fun browserActions(
        url: String,
        revision: Int,
    ): List<NativeTitleBarAction> =
        listOf(
            NativeTitleBarAction("browser_back", "Back", "chevron.left") {},
            NativeTitleBarAction("browser_forward", "Forward", "chevron.right") {},
            NativeTitleBarAction("browser_reload", "Reload", "arrow.clockwise") {},
            address(url, revision),
            NativeTitleBarAction("browser_bookmark", "Bookmark", "star") {},
            NativeTitleBarAction("new", "New tab", "plus") {},
        )

    private fun address(
        url: String,
        revision: Int,
    ): NativeTitleBarAction =
        NativeTitleBarAction(
            "browser_url",
            "Address",
            textInput =
                NativeTitleBarTextInput(
                    "browser",
                    url,
                    {},
                    {},
                    address =
                        BrowserAddressBarState(
                            url,
                            url.length,
                            url.length,
                            null,
                            false,
                            false,
                            revision,
                            { _, _, _ -> },
                            {},
                            {},
                            {},
                            {},
                        ),
                ),
        ) {}

    private fun awaitWidth(
        controller: MacSidebarToolbar,
        window: ComposeWindow,
        minimum: Int,
    ): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val width =
                onAppKit {
                    controller.measure()
                    controller.addressField.editing.view
                        ?.let { nativeAddressBounds(it, Pointer(window.windowHandle))?.width } ?: 0
                }
            if (width > minimum) return width
            Thread.sleep(25)
        }
        error("Address field did not grow past $minimum points")
    }

    private fun <T> onAppKit(action: () -> T): T {
        val done = CompletableFuture<T>()
        MacToolbarRuntime.dispatch { runCatching(action).fold(done::complete, done::completeExceptionally) }
        return done.get(10, TimeUnit.SECONDS)
    }
}
