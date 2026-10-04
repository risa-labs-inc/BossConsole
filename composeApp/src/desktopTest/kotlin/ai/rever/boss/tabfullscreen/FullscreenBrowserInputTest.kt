package ai.rever.boss.tabfullscreen

import ai.rever.boss.plugin.browser.FluckEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FullscreenBrowserInputTest {
    private class Surface(
        var focused: Boolean = true,
        var showing: Boolean = true,
    )

    @Test
    fun `fullscreen accepts typing with an inactive host and keeps shortcuts with that host`() {
        val browser = Any()
        val input = FullscreenBrowserInput<Any, Surface> { it.focused && it.showing }
        input.attach(browser, "host", Surface())
        val route =
            FluckEngine.resolveBrowserKeyEventRoute(
                "host",
                input.focusFor(browser, "host") ?: false,
                "another-window",
            )
        assertTrue(route.acceptsInput)
        assertEquals("host", route.shortcutWindowId)
        assertEquals(false, input.focusFor(browser, "another-window"))
        assertEquals(false, input.focusFor(browser, null))
        assertNull(input.focusFor(Any(), "host"))
    }

    @Test
    fun `unfocused or hidden fullscreen cannot fall back to a focused host`() {
        val browser = Any()
        val surface = Surface()
        val input = FullscreenBrowserInput<Any, Surface> { it.focused && it.showing }
        input.attach(browser, "host", surface)
        surface.focused = false
        assertFalse(input.focusFor(browser, "host") ?: true)
        surface.focused = true
        surface.showing = false
        assertFalse(input.focusFor(browser, "host") ?: true)
    }

    @Test
    fun `overlay replacement retires the previous frame and exit restores ordinary routing`() {
        val browser = Any()
        val input = FullscreenBrowserInput<Any, Surface> { it.focused && it.showing }
        input.attach(browser, "host", Surface())
        val replacement = Surface(focused = false)
        input.attach(browser, "host", replacement)
        assertEquals(false, input.focusFor(browser, "host"))
        replacement.focused = true
        assertEquals(true, input.focusFor(browser, "host"))
        input.clear()
        assertNull(input.focusFor(browser, "host"))
    }
}
