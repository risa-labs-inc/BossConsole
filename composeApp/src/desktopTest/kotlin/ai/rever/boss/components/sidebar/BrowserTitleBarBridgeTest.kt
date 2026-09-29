package ai.rever.boss.components.sidebar

import ai.rever.boss.plugin.browser.BrowserTitleBarBridge
import ai.rever.boss.plugin.browser.BrowserTitleBarState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserTitleBarBridgeTest {
    private fun state() = BrowserTitleBarState("https://example.com", false, false, false, false, {}, {}, {}, {}, {})

    @Test
    fun `unhosting restores toolbar and focus falls back`() {
        val owner = Any()
        val state = state()
        try {
            BrowserTitleBarBridge.publish("test-browser", owner, state)
            assertFalse(BrowserTitleBarBridge.isHosted("test-browser"))
            var focused = false
            BrowserTitleBarBridge.host("test-browser") { focused = true }
            assertTrue(BrowserTitleBarBridge.focus("test-browser"))
            assertTrue(focused)
            BrowserTitleBarBridge.host("test-browser", null)
            assertFalse(BrowserTitleBarBridge.focus("test-browser"))
            assertSame(state, BrowserTitleBarBridge.state("test-browser"))
        } finally {
            BrowserTitleBarBridge.remove("test-browser", owner)
        }
        assertNull(BrowserTitleBarBridge.state("test-browser"))
    }

    @Test
    fun `disposing an old composition cannot unregister its replacement`() {
        val old = Any()
        val replacement = Any()
        val state = state()
        try {
            BrowserTitleBarBridge.publish("moved-browser", old, state())
            BrowserTitleBarBridge.publish("moved-browser", replacement, state)
            BrowserTitleBarBridge.remove("moved-browser", old)
            assertSame(state, BrowserTitleBarBridge.state("moved-browser"))
        } finally {
            BrowserTitleBarBridge.remove("moved-browser", replacement)
        }
    }

    @Test
    fun `old window cleanup preserves a browser moved to a new window`() {
        val oldWindow = Any()
        val newWindow = Any()
        var focused = false
        try {
            BrowserTitleBarBridge.host("moved-focus", oldWindow) {}
            BrowserTitleBarBridge.host("moved-focus", newWindow) { focused = true }
            BrowserTitleBarBridge.host("moved-focus", oldWindow, null)
            assertTrue(BrowserTitleBarBridge.focus("moved-focus"))
            assertTrue(focused)
        } finally {
            BrowserTitleBarBridge.host("moved-focus", newWindow, null)
        }
        assertFalse(BrowserTitleBarBridge.isHosted("moved-focus"))
    }

    @Test
    fun `browser disposal leaves host focus ownership intact`() {
        val browser = Any()
        val host = Any()
        try {
            BrowserTitleBarBridge.publish("recomposed-browser", browser, state())
            BrowserTitleBarBridge.host("recomposed-browser", host) {}
            BrowserTitleBarBridge.remove("recomposed-browser", browser)
            assertNull(BrowserTitleBarBridge.state("recomposed-browser"))
            assertTrue(BrowserTitleBarBridge.focus("recomposed-browser"))
        } finally {
            BrowserTitleBarBridge.remove("recomposed-browser", browser)
            BrowserTitleBarBridge.host("recomposed-browser", host, null)
        }
    }
}
