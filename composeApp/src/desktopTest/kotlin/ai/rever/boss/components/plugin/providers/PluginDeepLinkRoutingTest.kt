package ai.rever.boss.components.plugin.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginDeepLinkRoutingTest {
    @Test
    fun installRequestStaysInReceivingHost() {
        val received = mutableListOf<String>()
        val url = "boss://plugin?id=toolbox&action=install&plugin=ai-gateway"
        assertTrue(routePluginDeepLink(url) { received.add(it) })
        assertEquals(listOf(url), received)
    }

    @Test
    fun ordinaryWebPagesRemainBrowserRequests() {
        assertFalse(routePluginDeepLink("https://example.com/boss://plugin") { error("Must not dispatch") })
        assertTrue(routePluginDeepLink("BOSS://plugin?id=toolbox") {})
    }
}
