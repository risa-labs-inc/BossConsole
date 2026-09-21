package ai.rever.boss.app

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
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

/** Navigation restores live tabs without recreating either Space's running components. */
class FocusOpenTabTest {
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
        override fun Content() = Unit
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

    private fun space(id: String) =
        LayoutWorkspace(
            id = id,
            name = "Saved $id",
            description = "",
            layout = SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
        )

    @Test
    fun `search target in another Space with the same main panel id becomes visible`() {
        val state = newSplitViewState()
        state.enterWorkspace("a", "target", "other-a")
        val aPanel = state.getPanel("main")!!
        val targetComponent = aPanel.tabsComponent.getComponentById("target") as TransferTabComponent
        state.enterWorkspace("b", "b-tab")
        val bPanel = state.getPanel("main")!!
        val loaded = mutableListOf<LayoutWorkspace>()
        val targetSpace = space("a")

        assertTrue(state.focusOpenTab("target", listOf(targetSpace, space("b")), loaded::add))
        assertEquals("a", state.currentWorkspaceId)
        assertSame(aPanel, state.getPanel("main"))
        assertSame(targetComponent, state.getPanel("main")!!.tabsComponent.getComponentById("target"))
        assertEquals(0, targetComponent.destroyCount)
        assertEquals(
            "target",
            aPanel.tabsComponent.tabsState.value.activeTab
                ?.id,
        )
        assertSame(targetSpace, loaded.single())
        assertSame(bPanel, state.panelsInWorkspace("b").single())
        assertEquals(listOf("b-tab"), state.tabIdsIn("b"))

        assertTrue(state.focusOpenTab("b-tab", listOf(targetSpace, space("b")), loaded::add))
        assertSame(bPanel, state.getPanel("main"))
        assertEquals(listOf("target", "other-a"), state.tabIdsIn("a"))
    }

    @Test
    fun `current Space selects the actual tab pane without reloading workspace metadata`() {
        val state = newSplitViewState()
        state.enterWorkspace("a", "a-tab")
        val right = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(right)!!.tabsComponent.addTab(TransferTabInfo("right-tab"))
        state.setActivePanel("main")

        assertTrue(state.focusOpenTab("right-tab", emptyList()) { error("must not reload") })
        assertEquals("a", state.currentWorkspaceId)
        assertEquals(right, state.activePanelId)
    }

    @Test
    fun `a fresh window without a Space id can still select its tabs`() {
        val state = newSplitViewState()
        val panel = state.getPanel("main")!!
        panel.tabsComponent.addTab(TransferTabInfo("first"))
        panel.tabsComponent.addTab(TransferTabInfo("second"))
        assertNull(state.currentWorkspaceId)

        assertTrue(state.focusOpenTab("first", emptyList()) { error("must not load") })
        assertEquals(
            "first",
            panel.tabsComponent.tabsState.value.activeTab
                ?.id,
        )
        assertNull(state.currentWorkspaceId)
    }

    @Test
    fun `a tab moved since search resolves in its new Space`() {
        val state = newSplitViewState()
        state.enterWorkspace("a", "target", "stays-a")
        state.enterWorkspace("b", "b-tab")
        assertTrue(state.moveTabToWorkspace("target", "b", targetPanelId = "main"))

        assertTrue(state.focusOpenTab("target", emptyList()) { error("already in destination") })
        assertEquals("b", state.currentWorkspaceId)
        assertEquals(
            "target",
            state
                .getPanel("main")!!
                .tabsComponent.tabsState.value.activeTab
                ?.id,
        )
    }

    @Test
    fun `closed search target does not switch Spaces or load a saved layout`() {
        val state = newSplitViewState()
        state.enterWorkspace("a", "target")
        state.enterWorkspace("b", "b-tab")
        assertTrue(state.closeTabAnywhere("target"))
        val before = state.getPanel("main")

        assertFalse(state.focusOpenTab("target", listOf(space("a"))) { error("must not load") })
        assertEquals("b", state.currentWorkspaceId)
        assertSame(before, state.getPanel("main"))
    }

    @Test
    fun `unsaved running Space is focusable and keeps its preserved name`() {
        val state = newSplitViewState()
        state.enterWorkspace("a", "target")
        state.preserveCurrentState("b", "Unsaved research")
        state.clearAllPanels()
        state.getPanel("main")!!.tabsComponent.addTab(TransferTabInfo("b-tab"))
        val loaded = mutableListOf<LayoutWorkspace>()

        assertTrue(state.focusOpenTab("target", emptyList(), loaded::add))
        assertEquals("a", loaded.single().id)
        assertEquals("Unsaved research", loaded.single().name)
    }

    @Test
    fun `focusing destination window leaves another window unchanged`() {
        val source = newSplitViewState()
        source.enterWorkspace("source", "source-tab")
        val destination = newSplitViewState()
        destination.enterWorkspace("a", "target")
        destination.enterWorkspace("b", "b-tab")

        assertTrue(destination.focusOpenTab("target", emptyList()) {})
        assertEquals("a", destination.currentWorkspaceId)
        assertEquals("source", source.currentWorkspaceId)
        assertEquals(
            "source-tab",
            source
                .getPanel("main")!!
                .tabsComponent.tabsState.value.activeTab
                ?.id,
        )
    }
}
