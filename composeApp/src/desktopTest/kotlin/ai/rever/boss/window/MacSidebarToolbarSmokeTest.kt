package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Owns unfocusable test windows; never changes an existing app window. */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
class MacSidebarToolbarSmokeTest {
    @Test
    fun `windows keep independent toolbar items through updates and close`() {
        val windows = mutableListOf<ComposeWindow>()
        val controllers = mutableListOf<MacSidebarToolbar>()
        try {
            SwingUtilities.invokeAndWait {
                repeat(2) { index ->
                    val window =
                        ComposeWindow().apply {
                            title = "BOSS toolbar test $index"
                            focusableWindowState = false
                            setSize(640, 180)
                            setContent { }
                            isVisible = true
                        }
                    windows.add(window)
                    controllers.add(MacSidebarToolbar(window.windowHandle, {}, {}))
                }
            }
            val first = controllers[0]
            val second = controllers[1]
            val firstHandle = windows[0].windowHandle
            val secondHandle = windows[1].windowHandle
            update(first, "terminal_title")
            assertItems(firstHandle, "terminal_title")
            // With the old shared identifier AppKit forwards browser_back to the first
            // delegate, which cannot provide that item, and aborts the entire test JVM.
            update(second, "browser_back")
            assertItems(firstHandle, "terminal_title")
            assertItems(secondHandle, "browser_back")
            assertNotEquals(toolbarIdentifier(firstHandle), toolbarIdentifier(secondHandle))

            update(first, "browser_reload")
            assertItems(firstHandle, "browser_reload")
            assertItems(secondHandle, "browser_back")
            update(second, "terminal_title")
            assertItems(firstHandle, "browser_reload")
            assertItems(secondHandle, "terminal_title")

            first.close()
            onAppKit { assertEquals(null, pointer(Pointer(firstHandle), "toolbar")) }
            SwingUtilities.invokeAndWait { windows[0].dispose() }
            update(second, "browser_forward")
            assertItems(secondHandle, "browser_forward")
        } finally {
            controllers.forEach { it.close() }
            onAppKit { Unit }
            SwingUtilities.invokeAndWait { windows.forEach { it.dispose() } }
        }
    }

    private fun update(
        controller: MacSidebarToolbar,
        extra: String,
    ) {
        val actions =
            listOf(
                NativeTitleBarAction("sidebar", "Sidebar", symbol = "sidebar.left", onClick = {}),
                NativeTitleBarAction(extra, extra, symbol = "arrow.left", onClick = {}),
            )
        controller.update("Test", actions, dark = false, background = -1, icons = emptyMap())
        onAppKit { Unit }
    }

    private fun assertItems(
        handle: Long,
        extra: String,
    ) {
        val actual =
            onAppKit {
                val items = pointer(pointer(Pointer(handle), "toolbar"), "items")
                (0 until number(items, "count")).map { index ->
                    val item = pointer(items, "objectAtIndex:", index)
                    text(pointer(item, "itemIdentifier"))
                }
            }
        val expected =
            if (extra.startsWith("browser_")) {
                listOf("sidebar", "NSToolbarFlexibleSpaceItem", extra)
            } else {
                listOf("sidebar", extra, "NSToolbarFlexibleSpaceItem")
            }
        assertEquals(expected, actual)
    }

    private fun toolbarIdentifier(handle: Long): String? =
        onAppKit {
            val toolbar = pointer(Pointer(handle), "toolbar")
            text(pointer(toolbar, "identifier"))
        }

    private fun text(value: Pointer?): String? = pointer(value, "UTF8String")?.getString(0)

    private fun <T> onAppKit(action: () -> T): T {
        val completed = CompletableFuture<T>()
        MacToolbarRuntime.dispatch {
            runCatching(action).fold(completed::complete, completed::completeExceptionally)
        }
        return completed.get(10, TimeUnit.SECONDS)
    }
}
