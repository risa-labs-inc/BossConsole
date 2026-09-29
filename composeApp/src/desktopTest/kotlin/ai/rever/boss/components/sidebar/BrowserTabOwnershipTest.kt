package ai.rever.boss.components.sidebar

import ai.rever.boss.plugin.browser.BrowserTabOwnership
import ai.rever.boss.plugin.browser.BrowserTitleBarBridge
import ai.rever.boss.plugin.browser.BrowserTitleBarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BrowserTabOwnershipTest {
    @Test
    fun `home retains navigation without a composed webpage`() {
        val owner = Any()
        val home = BrowserTitleBarState("about:blank", false, true, false, false, {}, {}, {}, {}, {})
        try {
            BrowserTabOwnership.bind("home-tab", "home-handle")
            BrowserTitleBarBridge.publish("home-handle", owner, home)
            // No ActiveBrowserRegistry registration: Home renders a Compose dashboard.
            val handleId = BrowserTabOwnership.handleIds.value["home-tab"]
            assertEquals("home-handle", handleId)
            assertSame(home, BrowserTitleBarBridge.state(handleId!!))
            assertNull(BrowserTabOwnership.handleIds.value["editor-tab"])
        } finally {
            BrowserTabOwnership.unbind("home-handle")
            BrowserTitleBarBridge.remove("home-handle", owner)
        }
        assertNull(BrowserTabOwnership.handleIds.value["home-tab"])
    }

    @Test
    fun `old handle disposal preserves replacement and rebinding removes old owner`() {
        try {
            BrowserTabOwnership.bind("tab-a", "old-handle")
            BrowserTabOwnership.bind("tab-a", "replacement")
            BrowserTabOwnership.unbind("old-handle")
            assertEquals("replacement", BrowserTabOwnership.handleIds.value["tab-a"])
            BrowserTabOwnership.bind("tab-b", "replacement")
            assertNull(BrowserTabOwnership.handleIds.value["tab-a"])
            assertEquals("replacement", BrowserTabOwnership.handleIds.value["tab-b"])
        } finally {
            BrowserTabOwnership.unbind("old-handle")
            BrowserTabOwnership.unbind("replacement")
        }
    }
}
