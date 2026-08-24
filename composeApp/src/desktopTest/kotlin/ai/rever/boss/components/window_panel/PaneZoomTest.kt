package ai.rever.boss.components.window_panel

import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Showing one pane alone, from a double-click on the split map.
 *
 * Zoom hides panes, it does not close them: the split tree is untouched, so exiting restores the
 * arrangement without anything having to remember it. What the state has to get right is the two
 * ways a zoom can go stale - the user activating a different pane, and the zoomed pane closing.
 */
class PaneZoomTest {
    private fun state(): SplitViewState = SplitViewState(TabRegistry(), windowId = "w1")

    @Test
    fun `a window starts unzoomed`() {
        assertNull(state().zoomedPanelId)
    }

    @Test
    fun `zooming shows that pane and makes it active`() {
        val s = state()
        s.zoomPanel("main")

        assertEquals("main", s.zoomedPanelId)
        assertEquals("main", s.activePanelId)
    }

    @Test
    fun `exiting leaves the pane active`() {
        // Exiting is about the layout, not about where the user is. Sending them back to whatever
        // pane was active before they zoomed would undo a navigation they did on purpose.
        val s = state()
        s.zoomPanel("main")
        s.exitZoom()

        assertNull(s.zoomedPanelId)
        assertEquals("main", s.activePanelId)
    }

    @Test
    fun `zoom follows the pane the user activates`() {
        // Otherwise clicking a tab in another pane's group while zoomed appears to do nothing -
        // the same complaint the single tab bar was built to answer.
        val s = state()
        s.zoomPanel("main")
        s.setActivePanel("other")

        assertEquals("other", s.zoomedPanelId)
    }

    @Test
    fun `activating a pane while unzoomed does not start a zoom`() {
        val s = state()
        s.setActivePanel("other")

        assertNull(s.zoomedPanelId)
    }

    @Test
    fun `closing the zoomed pane exits zoom rather than zooming onto nothing`() {
        val s = state()
        s.splitPanel("main", SplitOrientation.VERTICAL)
        val second = s.getAllPanels().last().id
        s.zoomPanel(second)
        s.closePanel(second)

        assertNull(s.zoomedPanelId)
    }

    @Test
    fun `closing a pane that is not zoomed leaves the zoom alone`() {
        val s = state()
        s.splitPanel("main", SplitOrientation.VERTICAL)
        val second = s.getAllPanels().last().id
        s.zoomPanel("main")
        s.closePanel(second)

        assertEquals("main", s.zoomedPanelId)
    }

    // ---- naming a pane -------------------------------------------------------

    @Test
    fun `a pane has no name until someone gives it one`() {
        // Its label falls back to where it sits, which paneLabel derives from geometry.
        assertNull(state().panelName("main"))
    }

    @Test
    fun `a named pane keeps its name`() {
        val s = state()
        s.renamePanel("main", "Logs")

        assertEquals("Logs", s.panelName("main"))
    }

    @Test
    fun `a blank name clears it rather than storing empty`() {
        // Clearing is how you get back to the derived position, so the field starting on the
        // current label makes "select all, delete" the undo.
        val s = state()
        s.renamePanel("main", "Logs")
        s.renamePanel("main", "   ")

        assertNull(s.panelName("main"))
    }

    @Test
    fun `surrounding space is not part of the name`() {
        val s = state()
        s.renamePanel("main", "  Logs  ")

        assertEquals("Logs", s.panelName("main"))
    }

    @Test
    fun `closing a pane forgets its name`() {
        // Ids are not reused, but a name outliving its pane is a leak with a user-visible tail.
        val s = state()
        s.splitPanel("main", SplitOrientation.VERTICAL)
        val second = s.getAllPanels().last().id
        s.renamePanel(second, "Logs")
        s.closePanel(second)

        assertNull(s.panelName(second))
    }

    // ---- splitting a pane from its own menu ---------------------------------

    @Test
    fun `splitting a pane adds one and leaves the original`() {
        // The pane menu's split takes no tab: it splits the PANE, so the new one starts empty.
        // Splitting from a tab's own menu is the other gesture, and that one moves the tab.
        val s = state()
        s.splitPanel("main", SplitOrientation.VERTICAL)

        val panels = s.getAllPanels()
        assertEquals(2, panels.size)
        assertTrue(panels.any { it.id == "main" }, "the pane being split must survive it")
    }

    @Test
    fun `a pane split both ways ends up with three`() {
        val s = state()
        s.splitPanel("main", SplitOrientation.VERTICAL)
        s.splitPanel("main", SplitOrientation.HORIZONTAL)

        assertEquals(3, s.getAllPanels().size)
    }

    @Test
    fun `splitting the zoomed pane leaves the zoom on the pane that was split`() {
        // The new pane is not what the user was looking at, and zoom follows the ACTIVE pane -
        // so a split that silently moved the zoom would hide the pane they just split.
        val s = state()
        s.zoomPanel("main")
        s.splitPanel("main", SplitOrientation.VERTICAL)

        assertEquals("main", s.zoomedPanelId)
    }
}
