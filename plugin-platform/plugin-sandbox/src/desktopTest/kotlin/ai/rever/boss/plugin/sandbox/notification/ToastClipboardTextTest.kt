package ai.rever.boss.plugin.sandbox.notification

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Pins [toastClipboardText]: a toast copies its title and message on separate lines, skipping a
 * blank half so there is no stray leading or trailing newline.
 */
class ToastClipboardTextTest {
    private fun toast(
        title: String,
        message: String,
    ) = ToastMessage(type = ToastType.ERROR, title = title, message = message)

    @Test
    fun `title and message are joined by a newline`() {
        assertEquals("Build failed\nExit code 1", toastClipboardText(toast("Build failed", "Exit code 1")))
    }

    @Test
    fun `a blank half is dropped without a stray newline`() {
        assertEquals("Only a title", toastClipboardText(toast("Only a title", "")))
        assertEquals("Only a message", toastClipboardText(toast("", "Only a message")))
    }

    @Test
    fun `two blank halves produce no clipboard payload`() {
        assertEquals("", toastClipboardText(toast("", "")))
    }
}
