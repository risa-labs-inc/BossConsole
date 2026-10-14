package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [activeHandleIds], the per-window dispatch target that lets UI observe WHICH
 * handle is active, not merely that the window has a browser.
 *
 * That distinction is load-bearing for the top-bar zoom badge: switching between two browser
 * tabs in one window changes the handle while [activeBrowserWindows]' set stays equal, so a
 * StateFlow on the set never emits and the badge would show the previous tab's zoom.
 *
 * Pure by construction, like [selectActiveHandleId] and [activeBrowserWindows] beside it, so
 * no JxBrowser browser is needed.
 */
class ActiveBrowserHandleIdStateTest {
    private fun entry(
        handleId: String,
        windowId: String = "w1",
        inMainPanel: Boolean = true,
        panelActive: Boolean = true,
        sequence: Long = 1,
    ) = ActiveBrowserRegistry.Entry(handleId, windowId, inMainPanel, panelActive, sequence)

    private val allLive: (String) -> Boolean = { true }

    @Test
    fun `no surfaces means no entry`() {
        assertEquals(emptyMap(), activeHandleIds(emptyList(), allLive))
    }

    @Test
    fun `keys are exactly the enabled window set`() {
        // The badge gates on a key being present; the two definitions must not drift.
        val candidates =
            listOf(
                entry("h1", windowId = "w1"),
                entry("h2", windowId = "w2", panelActive = false),
                entry("h3", windowId = "w3", inMainPanel = false),
            )

        assertEquals(activeBrowserWindows(candidates, allLive), activeHandleIds(candidates, allLive).keys)
    }

    @Test
    fun `the most recently shown surface is the advertised handle`() {
        val candidates =
            listOf(
                entry("first", sequence = 1),
                entry("second", sequence = 2),
            )

        assertEquals(mapOf("w1" to "second"), activeHandleIds(candidates, allLive))
    }

    @Test
    fun `a main-panel browser beats a newer sidebar surface`() {
        val candidates =
            listOf(
                entry("sidebar-new", inMainPanel = false, sequence = 2),
                entry("main-old", inMainPanel = true, sequence = 1),
            )

        assertEquals(mapOf("w1" to "main-old"), activeHandleIds(candidates, allLive))
    }

    @Test
    fun `a dead handle removes its window from the map`() {
        val candidates = listOf(entry("dead"))

        assertEquals(emptyMap(), activeHandleIds(candidates) { false })
    }

    @Test
    fun `windows are judged on their own surfaces`() {
        val candidates =
            listOf(
                entry("h1", windowId = "w1"),
                entry("h2", windowId = "w2"),
                entry("h3", windowId = "w1", sequence = 2),
            )

        val result = activeHandleIds(candidates, allLive)

        assertTrue(result.containsKey("w1"))
        assertTrue(result.containsKey("w2"))
        assertEquals("h3", result["w1"])
        assertEquals("h2", result["w2"])
    }
}
