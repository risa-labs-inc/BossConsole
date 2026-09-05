package ai.rever.boss.components.window_panel

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cross-workspace tab addressing on [SplitViewState].
 *
 * One window RUNS several workspaces at once and shows one of them: `preserveCurrentState` keeps
 * the whole split tree of the one you leave, and those stay live [BossTabsComponent]s. That was
 * already true of the read side - `collectAllActiveTabs` walks the preserved trees - but every
 * write path read `_rootNode.value` alone, so nothing could act on a tab that was not on screen.
 *
 * These pin down the write side:
 *
 * - `moveTabToWorkspace` transfers the LIVE component between workspaces, so a moved browser tab
 *   keeps its page rather than being destroyed here and rebuilt from config there.
 * - It refuses everything it cannot do honestly (unknown tab, dead destination, same workspace).
 * - A pane emptied by the move is pruned, in a preserved tree as well as the current one, and the
 *   preserved state's recorded activePanelId never survives pointing at a pruned pane.
 * - `selectTabAnywhere` / `closeTabAnywhere` reach into preserved workspaces without dragging the
 *   current one anywhere.
 */
class SplitViewWorkspaceTransferTest {
    private object TransferTabType : TabTypeInfo {
        override val typeId = TabTypeId("transfer-test", "test.plugin")
        override val displayName = "Transfer Test"
        override val icon = Icons.Outlined.Language
    }

    private data class TransferTabInfo(
        override val id: String,
        override val typeId: TabTypeId = TransferTabType.typeId,
        override val title: String = "Transfer Tab",
    ) : TabInfo {
        override val icon get() = Icons.Outlined.Language
    }

    private class TransferTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = TransferTabType
        var destroyCount = 0

        init {
            lifecycle.subscribe(
                callbacks =
                    object : Lifecycle.Callbacks {
                        override fun onDestroy() {
                            destroyCount++
                        }
                    },
            )
        }

        @Composable
        override fun Content() {
        }
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(TransferTabType) { config, ctx -> TransferTabComponent(ctx, config) }
        }

    private fun newSplitViewState() = SplitViewState(tabRegistry, windowId = "test-window")

    /**
     * Put [state] into workspace [id] with one tab per entry in [tabIds], leaving that workspace
     * current. Call twice to end up with one preserved workspace and one on screen, which is the
     * shape every test below needs.
     */
    private fun SplitViewState.enterWorkspace(
        id: String,
        vararg tabIds: String,
    ) {
        preserveCurrentState(id, workspaceName = id)
        // preserveCurrentState only records the OUTGOING tree; the incoming workspace starts from
        // whatever is on screen, so reset to a single empty pane the way applyWorkspace would.
        clearAllPanels()
        tabIds.forEach { getPanel("main")!!.tabsComponent.addTab(TransferTabInfo(id = it)) }
    }

    private fun SplitViewState.tabIdsIn(workspaceId: String): List<String> =
        panelsInWorkspace(workspaceId).flatMap { panel ->
            panel.tabsComponent.tabsState.value.tabs
                .map { it.id }
        }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    @Test
    fun `a preserved workspace names its panes the way the tab bar would`() {
        // The tab bar reads MEASURED rectangles, and a workspace behind the one on screen was
        // never composed - so its names come from the split tree with the dividers assumed
        // centred. Same words either way, which is the point: `splitPosition` is what a plugin
        // listing these tabs prints as its group header.
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        val right = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(right)!!.tabsComponent.addTab(TransferTabInfo(id = "tab-a2"))
        state.enterWorkspace("ws-b", "tab-b")

        val positions =
            state
                .collectAllActiveTabs(windowId = "test-window")
                .associate { it.tabInfo.id to it.splitPosition }

        assertEquals("Left", positions["tab-a"])
        assertEquals("Right", positions["tab-a2"])
        // One pane is not a split, so a position would be a claim about a divider that is not
        // there. Null is what ActiveTabData documents for it.
        assertNull(positions["tab-b"])
    }

    @Test
    fun `a nested pane in a preserved workspace gets its corner, not its branch`() {
        // "Top right", the way the bar says it - NOT "RIGHT > TOP", which is what reading the
        // saved SplitConfig tree produces and is the drift this replaced.
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-left")
        val right = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(right)!!.tabsComponent.addTab(TransferTabInfo(id = "tab-top-right"))
        val bottomRight = state.splitPanel(right, SplitOrientation.HORIZONTAL)
        state.getPanel(bottomRight)!!.tabsComponent.addTab(TransferTabInfo(id = "tab-bottom-right"))
        state.enterWorkspace("ws-b", "tab-b")

        val positions =
            state
                .collectAllActiveTabs(windowId = "test-window")
                .associate { it.tabInfo.id to it.splitPosition }

        assertEquals("Left", positions["tab-left"])
        assertEquals("Top right", positions["tab-top-right"])
        assertEquals("Bottom right", positions["tab-bottom-right"])
    }

    @Test
    fun `moves a live tab from the current workspace into a preserved one`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")

        val component = state.getPanel("main")!!.tabsComponent.getComponentById("tab-b")

        assertTrue(state.moveTabToWorkspace("tab-b", "ws-a"))

        assertEquals(listOf("tab-a", "tab-b"), state.tabIdsIn("ws-a"))
        assertEquals(emptyList(), state.tabIdsIn("ws-b"))
        // The LIVE instance travelled: same object, never destroyed. A recreate-from-config would
        // reload a browser tab and stop its media, which is the whole point of detach/adopt.
        val landed =
            state
                .panelsInWorkspace("ws-a")
                .firstNotNullOf { it.tabsComponent.getComponentById("tab-b") }
        assertSame(component, landed)
        assertEquals(0, (landed as TransferTabComponent).destroyCount)
    }

    @Test
    fun `moves a live tab out of a preserved workspace into the current one`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")

        val component =
            state
                .panelsInWorkspace("ws-a")
                .firstNotNullOf { it.tabsComponent.getComponentById("tab-a") }

        assertTrue(state.moveTabToWorkspace("tab-a", "ws-b"))

        assertEquals(listOf("tab-b", "tab-a"), state.tabIdsIn("ws-b"))
        assertSame(component, state.getPanel("main")!!.tabsComponent.getComponentById("tab-a"))
        assertEquals(0, (component as TransferTabComponent).destroyCount)
    }

    @Test
    fun `moves between two preserved workspaces without touching the current one`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")
        state.enterWorkspace("ws-c", "tab-c")

        assertTrue(state.moveTabToWorkspace("tab-a", "ws-b"))

        assertEquals(listOf("tab-b", "tab-a"), state.tabIdsIn("ws-b"))
        assertEquals(listOf("tab-c"), state.tabIdsIn("ws-c"))
        assertEquals("ws-c", state.currentWorkspaceId)
    }

    @Test
    fun `both workspaces name their first pane main, and the move still works`() {
        // Regression guard. Panel ids are unique only WITHIN a tree and clearAllPanels always
        // names the first pane "main", so a same-id check would reject the commonest move there is.
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")

        assertEquals("main", state.panelsInWorkspace("ws-a").single().id)
        assertEquals("main", state.panelsInWorkspace("ws-b").single().id)
        assertTrue(state.moveTabToWorkspace("tab-b", "ws-a"))
    }

    @Test
    fun `refuses an unknown tab, a dead destination and its own workspace`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")

        assertFalse(state.moveTabToWorkspace("no-such-tab", "ws-a"))
        assertFalse(state.moveTabToWorkspace("tab-b", "ws-never-opened"))
        assertFalse(state.moveTabToWorkspace("tab-b", "ws-b"))

        assertEquals(listOf("tab-a"), state.tabIdsIn("ws-a"))
        assertEquals(listOf("tab-b"), state.tabIdsIn("ws-b"))
    }

    @Test
    fun `prunes a pane the move emptied in a preserved workspace`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        val splitId = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(splitId)!!.tabsComponent.addTab(TransferTabInfo(id = "tab-a2"))
        state.setActivePanel(splitId)
        state.enterWorkspace("ws-b", "tab-b")

        assertEquals(2, state.panelsInWorkspace("ws-a").size)

        assertTrue(state.moveTabToWorkspace("tab-a2", "ws-b"))

        // The emptied pane is gone and the surviving one still holds its tab: a preserved tree is
        // an immutable snapshot in a state map, so this only works if the entry was REWRITTEN.
        assertEquals(1, state.panelsInWorkspace("ws-a").size)
        assertEquals(listOf("tab-a"), state.tabIdsIn("ws-a"))
    }

    @Test
    fun `repoints a preserved workspace's active panel when that pane is pruned`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        val splitId = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(splitId)!!.tabsComponent.addTab(TransferTabInfo(id = "tab-a2"))
        // Preserve ws-a with the pane we are about to empty recorded as its active one.
        state.setActivePanel(splitId)
        state.enterWorkspace("ws-b", "tab-b")

        assertTrue(state.moveTabToWorkspace("tab-a2", "ws-b"))

        // restorePreservedState writes activePanelId straight into _activePanelId, so a stale one
        // would leave the restored workspace pointing at a pane that is not in its own tree.
        val survivor = state.panelsInWorkspace("ws-a").single()
        assertEquals(survivor.id, state.activePanelIdForWorkspace("ws-a"))
        state.restorePreservedState("ws-a")
        assertEquals(survivor.id, state.activePanelId)
    }

    @Test
    fun `keeps the last pane of a workspace even when the move empties it`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")

        assertTrue(state.moveTabToWorkspace("tab-a", "ws-b"))

        // Empty, but still restorable. Pruning to nothing would leave a live workspace with no
        // tree at all, and restorePreservedState has nothing to put on screen.
        assertEquals(1, state.panelsInWorkspace("ws-a").size)
        assertEquals(emptyList(), state.tabIdsIn("ws-a"))
        assertTrue(state.restorePreservedState("ws-a"))
    }

    @Test
    fun `findTabLocation reaches tabs in workspaces that are not on screen`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")

        assertEquals("ws-a", state.findTabLocation("tab-a")?.workspaceId)
        assertEquals("ws-b", state.findTabLocation("tab-b")?.workspaceId)
        assertNull(state.findTabLocation("no-such-tab"))
    }

    @Test
    fun `selectTabAnywhere selects inside a preserved workspace without switching to it`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a1", "tab-a2")
        state.enterWorkspace("ws-b", "tab-b")

        assertTrue(state.selectTabAnywhere("tab-a1"))

        val preserved = state.panelsInWorkspace("ws-a").single()
        assertEquals(
            "tab-a1",
            preserved.tabsComponent.tabsState.value.activeTab
                ?.id,
        )
        // The window did not go anywhere.
        assertEquals("ws-b", state.currentWorkspaceId)
    }

    @Test
    fun `closeTabAnywhere closes a tab in a preserved workspace`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a1", "tab-a2")
        state.enterWorkspace("ws-b", "tab-b")

        val component =
            state
                .panelsInWorkspace("ws-a")
                .firstNotNullOf { it.tabsComponent.getComponentById("tab-a1") } as TransferTabComponent

        assertTrue(state.closeTabAnywhere("tab-a1"))
        assertFalse(state.closeTabAnywhere("tab-a1"))

        assertEquals(listOf("tab-a2"), state.tabIdsIn("ws-a"))
        // A closed tab is destroyed, unlike a moved one.
        assertEquals(1, component.destroyCount)
    }

    @Test
    fun `switchToLiveWorkspace brings a preserved workspace back with its layout intact`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a1", "tab-a2")
        val splitId = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(splitId)!!.tabsComponent.addTab(TransferTabInfo(id = "tab-a3"))
        state.enterWorkspace("ws-b", "tab-b")

        assertTrue(state.switchToLiveWorkspace("ws-a", leavingWorkspaceName = "ws-b"))

        assertEquals("ws-a", state.currentWorkspaceId)
        // Its whole tree came back, split and all - a live switch restores, it does not rebuild
        // from a saved config.
        assertEquals(2, state.getAllPanels().size)
        assertEquals(listOf("tab-a1", "tab-a2", "tab-a3"), state.tabIdsIn("ws-a"))
        // And the one we left is still running behind it.
        assertEquals(listOf("tab-b"), state.tabIdsIn("ws-b"))
    }

    @Test
    fun `switchToLiveWorkspace refuses the current workspace and one that is not running`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")

        assertFalse(state.switchToLiveWorkspace("ws-b"))
        assertFalse(state.switchToLiveWorkspace("ws-never-opened"))
        assertEquals("ws-b", state.currentWorkspaceId)
    }

    @Test
    fun `selecting a tab before the switch survives the restore`() {
        // The order focus depends on: selectTabAnywhere records the tab as its pane's active one
        // AND repoints the preserved activePanelId, then restorePreservedState reads both into
        // the live state. Selecting after the swap would race the restore.
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a1", "tab-a2")
        val splitId = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(splitId)!!.tabsComponent.addTab(TransferTabInfo(id = "tab-a3"))
        state.enterWorkspace("ws-b", "tab-b")

        assertTrue(state.selectTabAnywhere("tab-a3"))
        assertTrue(state.switchToLiveWorkspace("ws-a", leavingWorkspaceName = "ws-b"))

        // The pane holding it is active, and it is the selected tab in that pane.
        val pane = state.getPanel(state.activePanelId)!!
        assertEquals(
            "tab-a3",
            pane.tabsComponent.tabsState.value.activeTab
                ?.id,
        )
    }

    @Test
    fun `activePanelIdForWorkspace falls back to a real pane when the recorded one is gone`() {
        val state = newSplitViewState()
        state.enterWorkspace("ws-a", "tab-a")
        state.enterWorkspace("ws-b", "tab-b")

        assertNotNull(state.activePanelIdForWorkspace("ws-a"))
        assertNull(state.activePanelIdForWorkspace("ws-never-opened"))
    }
}
