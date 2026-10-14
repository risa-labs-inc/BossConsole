package ai.rever.boss.window

import ai.rever.boss.sharing.onEdt
import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real AppKit key events in an unfocusable fixture; never send keys to the user's application. */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MacAddressKeyboardShortcutSmokeTest {
    @Test
    @Suppress("LongMethod") // One native lifetime verifies focus, copy and rejection in another control/window.
    fun `native command A C and L act on the owned URL editor only`() {
        val windows =
            List(2) {
                onEdt {
                    ComposeWindow().apply {
                        focusableWindowState = false
                        setSize(800, 200)
                        setContent { }
                        isVisible = true
                    }
                }
            }
        val controllers = windows.map { onEdt { MacSidebarToolbar(it.windowHandle, {}, {}) } }
        try {
            controllers.forEachIndexed { index, controller ->
                controller.update("", listOf(address("https://example.com/$index")), false, -1, emptyMap())
            }
            awaitEditor(controllers[0])
            onAppKit {
                val first = controllers[0].addressField
                val second = controllers[1].addressField
                first.focus()
                val firstEditor = assertNotNull(pointer(first.editing.view, "currentEditor"))
                send(firstEditor, "setSelectedRange:", MacTextRange(4, 0))
                assertFalse(handleMacAddressKeyEvent(event(controllers[1], "a", 0)))
                assertEquals(4L to 0L, selection(firstEditor))
                key(controllers[0], "a", 0)
                assertEquals(0L to 21L, selection(firstEditor))
                assertEquals(
                    "https://example.com/1",
                    pointer(pointer(second.editing.view, "stringValue"), "UTF8String")?.getString(0),
                )

                NativeClipboardSnapshot().use { clipboard ->
                    key(controllers[0], "c", 8)
                    clipboard.markOurChange()
                    assertEquals("https://example.com/0", clipboard.text())
                }

                // Cmd+C/A must not borrow the old URL editor after the page becomes first responder.
                assertTrue(first.editing.releaseForPage())
                assertFalse(handleMacAddressKeyEvent(event(controllers[0], "a", 0)))
                assertFalse(handleMacAddressKeyEvent(event(controllers[0], "c", 8)))
                key(controllers[0], "l", 37)
                assertTrue(first.editing.active)
                assertEquals(0L to 21L, selection(assertNotNull(pointer(first.editing.view, "currentEditor"))))
                assertFalse(handleMacAddressKeyEvent(event(controllers[0], "a", 0, (1L shl 20) or (1L shl 19))))

                // Closing the other window must leave this window's monitor installed.
            }
            controllers[1].close()
            onAppKit {
                val first = controllers[0].addressField
                val editor = assertNotNull(pointer(first.editing.view, "currentEditor"))
                send(editor, "setSelectedRange:", MacTextRange(3, 0))
                key(controllers[0], "a", 0)
                assertEquals(0L to 21L, selection(editor))
            }
            controllers[0].close()
            onAppKit { assertFalse(controllers[0].addressField.performShortcut("focusAddress")) }
        } finally {
            controllers.forEach { it.close() }
            onAppKit { Unit }
            onEdt { windows.forEach { it.dispose() } }
        }
    }

    private fun address(url: String) =
        NativeTitleBarAction(
            "browser_url",
            "Address",
            textInput = NativeTitleBarTextInput(url, url, {}, {}),
            onClick = {},
        )

    private fun key(
        controller: MacSidebarToolbar,
        key: String,
        code: Short,
    ) {
        val event = event(controller, key, code)
        // Go through NSApplication, including the installed local monitor, rather than invoking an editing helper.
        send(pointer(clazz("NSApplication"), "sharedApplication"), "sendEvent:", event)
    }

    private fun event(
        controller: MacSidebarToolbar,
        key: String,
        code: Short,
        flags: Long = 1L shl 20,
    ): Pointer {
        val window = pointer(controller.addressField.editing.view, "window")
        return assertNotNull(
            pointer(
                clazz("NSEvent"),
                "keyEventWithType:location:modifierFlags:timestamp:windowNumber:context:" +
                    "characters:charactersIgnoringModifiers:isARepeat:keyCode:",
                10L,
                ToolbarIconSize(0.0, 0.0),
                flags,
                0.0,
                number(window, "windowNumber"),
                null,
                string(key),
                string(key),
                0.toByte(),
                code,
            ),
        )
    }

    private fun selection(editor: Pointer): Pair<Long, Long> =
        Memory(16).use { bytes ->
            send(pointer(editor, "valueForKey:", string("selectedRange")), "getValue:size:", bytes, 16L)
            bytes.getLong(0) to bytes.getLong(8)
        }

    private fun awaitEditor(controller: MacSidebarToolbar) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            controller.focusAddress()
            if (onAppKit { pointer(controller.addressField.editing.view, "currentEditor") != null }) return
            Thread.sleep(25)
        }
        error("AppKit did not attach the synthetic address editor")
    }

    private fun <T> onAppKit(action: () -> T): T {
        val result = CompletableFuture<T>()
        MacToolbarRuntime.dispatch { runCatching(action).fold(result::complete, result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }
}

/** Retain every pasteboard item's data, and restore it unless the user copied something during the test. */
private class NativeClipboardSnapshot : AutoCloseable {
    private val pasteboard = pointer(clazz("NSPasteboard"), "generalPasteboard")
    private var ourChange = number(pasteboard, "changeCount")
    private val items =
        pointer(pasteboard, "pasteboardItems").let { list ->
            (0 until number(list, "count")).map { index ->
                val item = pointer(list, "objectAtIndex:", index)
                val types = pointer(item, "types")
                (0 until number(types, "count")).mapNotNull { typeIndex ->
                    val type = pointer(types, "objectAtIndex:", typeIndex)
                    val name = pointer(type, "UTF8String")?.getString(0)
                    val data = pointer(item, "dataForType:", type)
                    if (name == null || data == null) null else (name to data.also { send(it, "retain") })
                }
            }
        }

    fun markOurChange() {
        ourChange = number(pasteboard, "changeCount")
    }

    fun text(): String? =
        pointer(
            pointer(pasteboard, "stringForType:", string("public.utf8-plain-text")),
            "UTF8String",
        )?.getString(0)

    override fun close() {
        try {
            if (number(pasteboard, "changeCount") == ourChange) restore()
        } finally {
            items.flatten().forEach { send(it.second, "release") }
        }
    }

    private fun restore() {
        val restored = pointer(clazz("NSMutableArray"), "new")
        try {
            items.forEach { saved ->
                val item = pointer(clazz("NSPasteboardItem"), "new")
                saved.forEach { (type, data) -> send(item, "setData:forType:", data, string(type)) }
                send(restored, "addObject:", item)
                send(item, "release")
            }
            send(pasteboard, "clearContents")
            if (items.isNotEmpty()) send(pasteboard, "writeObjects:", restored)
        } finally {
            send(restored, "release")
        }
    }
}
