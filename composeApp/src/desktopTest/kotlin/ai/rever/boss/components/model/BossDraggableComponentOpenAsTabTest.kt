package ai.rever.boss.components.model

import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BossDraggableComponent.requestOpenAsTab] is the single-instance rule for "open this panel
 * in the main area", and it has to hold for every caller of it - the header drag-out and,
 * since BossConsole#177, `SplitViewOperations.openPanelAsTab`.
 *
 * The rule matters because nothing downstream enforces it. `BossTabsComponent.addTab` does not
 * dedupe on `TabInfo.id`, so a second promote would leave two tabs rendering one cached panel
 * component, and `ProcessPendingFocusHostedTab` stops its scan at the first hosting tab.
 */
class BossDraggableComponentOpenAsTabTest {
    private fun component() = BossDraggableComponent(PanelRegistry())

    private val panel = PanelId("codebase", 1)

    @Test
    fun `promotes a panel that is not open as a tab`() {
        val draggable = component()

        draggable.requestOpenAsTab(panel)

        assertEquals(panel, draggable.pendingPromoteToTab?.panelId)
        assertNull(draggable.pendingPromoteToTab?.target, "the plugin-facing call targets the active panel")
        assertNull(draggable.pendingFocusHostedTab, "nothing to focus - no tab hosts it yet")
    }

    @Test
    fun `carries the drop target through, for the header drag-out`() {
        val draggable = component()
        val target = TabDropTarget.ExistingPanel("panel-2")

        draggable.requestOpenAsTab(panel, target)

        assertEquals(target, draggable.pendingPromoteToTab?.target)
    }

    @Test
    fun `focuses the existing tab instead of promoting a second copy`() {
        val draggable = component()
        draggable.markHostedAsTab(panel)

        draggable.requestOpenAsTab(panel)

        assertEquals(panel, draggable.pendingFocusHostedTab)
        assertNull(
            draggable.pendingPromoteToTab,
            "a second promote would create a duplicate tab over the same cached component",
        )
    }

    @Test
    fun `an unrelated hosted panel does not suppress the promote`() {
        val draggable = component()
        draggable.markHostedAsTab(PanelId("git-log", 15))

        draggable.requestOpenAsTab(panel)

        assertEquals(panel, draggable.pendingPromoteToTab?.panelId)
        assertNull(draggable.pendingFocusHostedTab)
    }

    /**
     * The hosted marker is a count, not a flag: a cross-panel move creates the new hosting tab
     * before closing the old one, so the count goes 1 -> 2 -> 1 and must not read as unhosted
     * in between.
     */
    @Test
    fun `hosted stays true until the last hosting tab is unmarked`() {
        val draggable = component()

        draggable.markHostedAsTab(panel)
        draggable.markHostedAsTab(panel)
        draggable.unmarkHostedAsTab(panel)
        assertTrue(draggable.isHostedAsTab(panel), "one hosting tab is still live")
        draggable.requestOpenAsTab(panel)
        assertNull(draggable.pendingPromoteToTab)

        draggable.clearPendingFocusHostedTab()
        draggable.unmarkHostedAsTab(panel)
        draggable.requestOpenAsTab(panel)
        assertEquals(panel, draggable.pendingPromoteToTab?.panelId, "the last tab closed - promote again")
    }
}
