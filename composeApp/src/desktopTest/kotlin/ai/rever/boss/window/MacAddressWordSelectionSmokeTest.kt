package ai.rever.boss.window

import ai.rever.boss.sharing.AppInputEvent
import ai.rever.boss.sharing.onEdt
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MacAddressWordSelectionSmokeTest {
    @ParameterizedTest
    @ValueSource(strings = ["Left", "Right"])
    fun `remote option shift arrow selects the same word as AppKit`(direction: String) {
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(800, 200)
                    setContent { }
                    isVisible = true
                }
            }
        val controller = onEdt { MacSidebarToolbar(window.windowHandle, {}, {}) }
        try {
            val address =
                NativeTitleBarAction(
                    "browser_url",
                    "Address",
                    textInput = NativeTitleBarTextInput("word-fixture", "hello world", {}, {}),
                    onClick = {},
                )
            controller.update("", listOf(address), false, -1, emptyMap())
            awaitEditor(controller)
            onAppKit {
                val editing = controller.addressField.editing
                val editor = assertNotNull(MacToolbarRuntime.pointer(editing.view, "currentEditor"))
                val origin = if (direction == "Left") "End" else "Beginning"
                MacToolbarRuntime.send(editor, "moveTo${origin}OfLine:", null)
                MacToolbarRuntime.send(editor, "moveWord${direction}AndModifySelection:", null)
                val native = selection(editor)
                assertEquals(5L, native.second, "Native fixture must select an entire word")
                MacToolbarRuntime.send(editor, "moveTo${origin}OfLine:", null)
                assertTrue(
                    editNativeAddress(
                        editing,
                        AppInputEvent.Key("down", "Arrow$direction", "Arrow$direction", true, false, false, true),
                    ),
                )
                assertEquals(native, selection(editor), "Remote word selection must retain both modifiers")
            }
        } finally {
            controller.close()
            onAppKit { Unit }
            onEdt { window.dispose() }
        }
    }

    private fun selection(editor: Pointer): Pair<Long, Long> =
        Memory(16).use {
            val boxed = MacToolbarRuntime.pointer(editor, "valueForKey:", MacToolbarRuntime.string("selectedRange"))
            MacToolbarRuntime.send(boxed, "getValue:size:", it, 16L)
            it.getLong(0) to it.getLong(8)
        }

    private fun awaitEditor(controller: MacSidebarToolbar) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            controller.focusAddress()
            if (onAppKit { MacToolbarRuntime.pointer(controller.addressField.editing.view, "currentEditor") != null }) {
                return
            }
            Thread.sleep(25)
        }
        error("AppKit did not attach the synthetic address editor")
    }

    private fun <T> onAppKit(action: () -> T): T {
        val result = CompletableFuture<T>()
        MacToolbarRuntime.dispatch { runCatching(action).fold(result::complete, result::completeExceptionally) }
        return result.get(5, TimeUnit.SECONDS)
    }
}
