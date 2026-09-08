package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [activeBrowserWindows], the rule behind the enabled flag on
 * View > Back / Forward / Developer Tools.
 *
 * That flag is not cosmetic. A Compose MenuBar accelerator fires from anywhere in the window
 * whatever the binding's ShortcutContext, so an enabled item consumes its chord for every other
 * tab type - Cmd+[ and Cmd+] are outdent/indent in an editor. "The window has a browser
 * somewhere" is therefore the wrong question, and was the first version of this: entries arrive
 * from every composed surface, a sidebar slot and the background half of a split included.
 *
 * Pure by construction, like [selectActiveHandleId] beside it, so no JxBrowser browser is needed.
 */
class ActiveBrowserWindowStateTest {
    private fun entry(
        handleId: String,
        windowId: String = "w1",
        inMainPanel: Boolean = true,
        panelActive: Boolean = true,
        sequence: Long = 1,
    ) = ActiveBrowserRegistry.Entry(handleId, windowId, inMainPanel, panelActive, sequence)

    private val allLive: (String) -> Boolean = { true }

    @Test
    fun `no surfaces means no window is enabled`() {
        assertEquals(emptySet(), activeBrowserWindows(emptyList(), allLive))
    }

    @Test
    fun `a browser in the active main panel enables its window`() {
        assertEquals(setOf("w1"), activeBrowserWindows(listOf(entry("h1")), allLive))
    }

    @Test
    fun `a browser in a background split does not enable the window`() {
        // The failure this gate exists for: browser on the left, editor on the right, focus in
        // the editor. Cmd+] must reach the editor as indent, not navigate the background browser.
        val candidates = listOf(entry("h1", panelActive = false))

        assertFalse("w1" in activeBrowserWindows(candidates, allLive))
    }

    @Test
    fun `a browser in a sidebar slot does not enable the window`() {
        // LocalIsPanelActive defaults to true outside a managed panel, so a sidebar-slot browser
        // reports itself active too; only inMainPanel separates the two.
        val candidates = listOf(entry("h1", inMainPanel = false))

        assertFalse("w1" in activeBrowserWindows(candidates, allLive))
    }

    @Test
    fun `a registered but invalid handle does not enable the window`() {
        // Otherwise the item stays enabled, the accelerator consumes the chord, and activeIn
        // returns null: chord eaten, nothing happens, which is the state the flag prevents.
        val candidates = listOf(entry("dead"))

        assertFalse("w1" in activeBrowserWindows(candidates) { false })
    }

    @Test
    fun `each window is judged on its own surfaces`() {
        val candidates =
            listOf(
                entry("active", windowId = "w1"),
                entry("background", windowId = "w2", panelActive = false),
                entry("sidebar", windowId = "w3", inMainPanel = false),
                entry("also-active", windowId = "w4"),
            )

        assertEquals(setOf("w1", "w4"), activeBrowserWindows(candidates, allLive))
    }

    @Test
    fun `one qualifying surface is enough for a window with several`() {
        // A split with a browser on both sides: only one panel is active, and that is enough.
        val candidates =
            listOf(
                entry("background", panelActive = false, sequence = 2),
                entry("active", panelActive = true, sequence = 1),
            )

        assertEquals(setOf("w1"), activeBrowserWindows(candidates, allLive))
    }

    @Test
    fun `the gate is stricter than the dispatch target, deliberately`() {
        // selectActiveHandleId ranks and always answers when a candidate exists, so a sidebar
        // browser still gets Zoom and Reload. This gate filters, because its menu items would
        // otherwise steal their chords from the editor the user is actually in. Pinned so the
        // asymmetry is a decision rather than a later "bug fix" that reopens the swallowing.
        val sidebarOnly = listOf(entry("sidebar", inMainPanel = false))

        assertTrue(activeBrowserWindows(sidebarOnly, allLive).isEmpty())
        assertEquals("sidebar", selectActiveHandleId(sidebarOnly, "w1"))
    }
}
