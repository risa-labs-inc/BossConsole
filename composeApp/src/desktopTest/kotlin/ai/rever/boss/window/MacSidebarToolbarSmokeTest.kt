package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import androidx.compose.material.icons.filled.Settings
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
        val clicked = CompletableFuture<String>()
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
                    controllers.add(MacSidebarToolbar(window.windowHandle, {}, { clicked.complete(it) }))
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

            // Space controls must replace their popup view when a plugin owns primary clicks.
            listOf(false, true, false).forEach { opensPlugin ->
                val action = NativeTitleBarAction(
                    "space", "Planet Berul",
                    contextMenu = listOf(NativeTitleBarAction("create-space", "Create New Space") {}),
                    menu = if (opensPlugin) null else listOf(NativeTitleBarAction("new-space", "Create New Space") {}),
                    onClick = {},
                )
                first.update("Test", listOf(action), dark = false, background = -1, icons = emptyMap())
                onAppKit {
                    val items = pointer(pointer(Pointer(firstHandle), "toolbar"), "items")
                    val item = (0 until number(items, "count")).map { pointer(items, "objectAtIndex:", it) }
                        .first { text(pointer(it, "itemIdentifier")) == "space" }
                    val view = pointer(item, "view")
                    assertEquals(
                        if (opensPlugin) MacToolbarContextMenu.buttonClass else MacToolbarContextMenu.popupClass,
                        pointer(view, "class"),
                    )
                    if (opensPlugin) {
                        assertEquals(number(item, "tag"), number(view, "tag"))
                        kotlin.test.assertNotNull(pointer(view, "target"))
                        kotlin.test.assertNotNull(pointer(view, "action"))
                        MacToolbarRuntime.send(view, "performClick:", null)
                    }
                }
                if (opensPlugin) assertEquals("space", clicked.get(5, TimeUnit.SECONDS))
            }

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

    @Test
    fun `sharing keeps the native toolbar installed for remote input`() {
        val windows = mutableListOf<ComposeWindow>()
        val sharing = androidx.compose.runtime.mutableStateOf(false)
        try {
            SwingUtilities.invokeAndWait {
                repeat(2) { index ->
                    val window =
                        ComposeWindow().apply {
                            focusableWindowState = false
                            setSize(640, 180)
                        }
                    val sidebar = NativeTitleBarAction("sidebar", "Sidebar", symbol = "sidebar.left", onClick = {})
                    window.setContent {
                        androidx.compose.runtime.CompositionLocalProvider(
                            ai.rever.boss.plugin.browser.LocalAwtWindow provides window,
                        ) {
                            NativeSidebarTitleBar(
                                "Synthetic toolbar",
                                listOf(sidebar),
                                index == 0 && sharing.value,
                            )
                        }
                    }
                    window.isVisible = true
                    windows.add(window)
                }
            }
            awaitToolbar(windows[0].windowHandle, true)
            awaitToolbar(windows[1].windowHandle, true)
            val firstId = toolbarIdentifier(windows[0].windowHandle)
            val secondId = toolbarIdentifier(windows[1].windowHandle)
            SwingUtilities.invokeAndWait { sharing.value = true }
            onAppKit { Unit }
            assertEquals(firstId, toolbarIdentifier(windows[0].windowHandle))
            assertEquals(secondId, toolbarIdentifier(windows[1].windowHandle))
            SwingUtilities.invokeAndWait { sharing.value = false }
            awaitToolbar(windows[0].windowHandle, true)
            assertEquals(firstId, toolbarIdentifier(windows[0].windowHandle))
            assertEquals(secondId, toolbarIdentifier(windows[1].windowHandle))
        } finally {
            SwingUtilities.invokeAndWait { windows.forEach { it.dispose() } }
            onAppKit { Unit }
        }
    }

    @Test
    fun `sharing and MCP menus tolerate the call button appearing and disappearing`() {
        lateinit var window: ComposeWindow
        lateinit var controller: MacSidebarToolbar
        SwingUtilities.invokeAndWait {
            window =
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(640, 180)
                    setContent { }
                    isVisible = true
                }
            controller = MacSidebarToolbar(window.windowHandle, {}, {})
        }
        try {
            val sharing =
                NativeTitleBarAction(
                    "terminal_sharing",
                    "Sharing",
                    symbol = "square.and.arrow.up",
                    menu = listOf(NativeTitleBarAction("share", "Share window") {}),
                ) {}
            val mcp =
                NativeTitleBarAction(
                    "terminal_mcp",
                    "MCP",
                    icon = androidx.compose.material.icons.Icons.Default.Settings,
                    menu = listOf(NativeTitleBarAction("activity", "Activity log") {}),
                ) {}
            val call = NativeTitleBarAction("terminal_call", "Call", "phone", active = true) {}
            for (actions in listOf(listOf(sharing, mcp), listOf(sharing, call, mcp), listOf(sharing, mcp))) {
                controller.update("Test", actions, dark = false, background = -1, icons = emptyMap())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (System.nanoTime() < deadline &&
                    onAppKit { controller.groupedMenuView("terminal_mcp") == null }
                ) {
                    Thread.sleep(25)
                }
                onAppKit {
                    val items = pointer(pointer(Pointer(window.windowHandle), "toolbar"), "items")
                    val byId =
                        (0 until number(items, "count"))
                            .map { pointer(items, "objectAtIndex:", it) }
                            .associateBy { text(pointer(it, "itemIdentifier")) }
                    val subitems = pointer(byId.getValue("terminal_controls"), "subitems")
                    kotlin.test.assertNotNull(controller.groupedMenuView("terminal_mcp"))
                    assertEquals(actions.size.toLong(), number(subitems, "count"))
                    for (index in actions.indices) {
                        val item = pointer(subitems, "objectAtIndex:", index.toLong())
                        kotlin.test.assertFalse(MacToolbarRuntime.supports(item, "setShowsIndicator:"))
                        kotlin.test.assertNotNull(pointer(item, "action"))
                    }
                }
            }
        } finally {
            controller.close()
            onAppKit { Unit }
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    private fun awaitToolbar(
        handle: Long,
        present: Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (onAppKit { (pointer(Pointer(handle), "toolbar") != null) == present }) return
            Thread.sleep(25)
        }
        kotlin.test.fail("Native toolbar presence must become $present")
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
