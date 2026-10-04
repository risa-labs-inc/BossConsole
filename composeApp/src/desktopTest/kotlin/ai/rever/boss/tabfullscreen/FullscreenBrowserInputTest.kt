package ai.rever.boss.tabfullscreen

import ai.rever.boss.plugin.browser.FluckEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FullscreenBrowserInputTest {
    @Test
    fun `fullscreen receives keys with an inactive host and preserves shortcut ownership`() {
        val browser = Any()
        val input = FullscreenBrowserInput<Any>()
        input.attach(browser, "host", showing = true, focused = true)
        val route =
            FluckEngine.resolveBrowserKeyEventRoute(
                "host",
                false,
                "another-window",
                input.focusFor(browser, "host"),
            )
        assertTrue(route.acceptsInput)
        assertEquals("host", route.shortcutWindowId)
        assertEquals(false, input.focusFor(browser, "another-window"))
        assertEquals(false, input.focusFor(browser, null))
        assertNull(input.focusFor(Any(), "host"))
    }

    @Test
    fun `visible unfocused fullscreen rejects keys even when the original host is focused`() {
        val browser = Any()
        val input = FullscreenBrowserInput<Any>()
        val token = input.attach(browser, "host", showing = true, focused = true)
        input.update(token, showing = true, focused = false)
        assertFalse(
            FluckEngine
                .resolveBrowserKeyEventRoute(
                    "host",
                    true,
                    null,
                    input.focusFor(browser, "host"),
                ).acceptsInput,
        )
    }

    @Test
    fun `hidden or retired fullscreen restores the actual host focus check`() {
        val browser = Any()
        val input = FullscreenBrowserInput<Any>()
        val token = input.attach(browser, "host", showing = true, focused = true)
        input.update(token, showing = false, focused = false)
        for (hostFocused in listOf(true, false)) {
            assertEquals(
                hostFocused,
                FluckEngine
                    .resolveBrowserKeyEventRoute(
                        "host",
                        hostFocused,
                        null,
                        input.focusFor(browser, "host"),
                    ).acceptsInput,
            )
        }
        input.detach(token)
        assertNull(input.focusFor(browser, "host"))
    }

    @Test
    fun `late focus and close events from a replaced frame cannot retire the replacement`() {
        val browser = Any()
        val input = FullscreenBrowserInput<Any>()
        val old = input.attach(browser, "host", showing = true, focused = true)
        val replacement = input.attach(browser, "host", showing = true, focused = false)
        input.update(old, showing = true, focused = true)
        input.detach(old)
        assertEquals(false, input.focusFor(browser, "host"))
        input.update(replacement, showing = true, focused = true)
        assertEquals(true, input.focusFor(browser, "host"))
        input.clear()
        input.update(replacement, showing = true, focused = true)
        assertNull(input.focusFor(browser, "host"))
    }
}
