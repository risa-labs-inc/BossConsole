@file:Suppress("PackageNaming")

package ai.rever.boss.components.window_panel

import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitViewActiveTabsTest {
    private lateinit var tabRegistry: TabRegistry
    private lateinit var state: SplitViewState

    private object TestTabType : TabTypeInfo {
        override val typeId = TabTypeId("inventory-test", "test.plugin")
        override val displayName = "Inventory Test"
        override val icon = Icons.Outlined.Language
    }

    private data class TestTabInfo(
        override val id: String,
        override val typeId: TabTypeId = TestTabType.typeId,
        override val title: String = "Test Tab",
    ) : TabInfo {
        override val icon get() = Icons.Outlined.Language
    }

    private class TestTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = TestTabType

        @Composable
        override fun Content() {
            // no-op
        }
    }

    @BeforeTest
    fun setup() {
        tabRegistry =
            TabRegistry().apply {
                registerTabType(TestTabType) { config, ctx -> TestTabComponent(ctx, config) }
            }
        state = SplitViewState(tabRegistry, windowId = "w1")
    }

    private fun createTab(id: String) = TestTabInfo(id = id, title = "Title $id")

    @Test
    fun `single panel includes background tabs for search and lookup`() {
        val panel = state.getPanel(state.activePanelId)!!

        // Add multiple tabs
        val tab1 = createTab("tab1")
        val tab2 = createTab("tab2")
        val tab3 = createTab("tab3")

        panel.tabsComponent.addTab(tab1)
        panel.tabsComponent.addTab(tab2)
        panel.tabsComponent.addTab(tab3)

        // Select tab 2
        panel.tabsComponent.selectTab(1)

        state.preserveCurrentState("ws1")
        val activeTabs = state.collectAllActiveTabs(null, "w1")

        assertEquals(listOf("tab1", "tab2", "tab3"), activeTabs.map { it.tabInfo.id })
    }

    @Test
    fun `two splits return all open tabs regardless of selection`() {
        val leftPanel = state.getPanel(state.activePanelId)!!

        // Left pane has tab1 (background) and tab2 (active)
        leftPanel.tabsComponent.addTab(createTab("tab1"))
        leftPanel.tabsComponent.addTab(createTab("tab2"))
        leftPanel.tabsComponent.selectTab(1) // tab2

        // Split right
        val rightPanelId = state.splitPanel(leftPanel.id, SplitOrientation.VERTICAL)
        val rightPanel = state.getPanel(rightPanelId)!!

        // Right pane has tab3 (background) and tab4 (active)
        rightPanel.tabsComponent.addTab(createTab("tab3"))
        rightPanel.tabsComponent.addTab(createTab("tab4"))
        rightPanel.tabsComponent.selectTab(1) // tab4

        state.preserveCurrentState("ws1")
        val activeTabs = state.collectAllActiveTabs(null, "w1")

        assertEquals(4, activeTabs.size)

        val ids = activeTabs.map { it.tabInfo.id }.toSet()
        assertTrue(ids.contains("tab2"), "Left panel's active tab must be included")
        assertTrue(ids.contains("tab4"), "Right panel's active tab must be included")
        assertTrue(ids.contains("tab1"), "Background tab 1 must remain discoverable")
        assertTrue(ids.contains("tab3"), "Background tab 3 must remain discoverable")
    }

    @Test
    fun `unfocused right split remains in active tabs`() {
        val leftPanelId = state.activePanelId
        val rightPanelId = state.splitPanel(leftPanelId, SplitOrientation.VERTICAL)

        state.getPanel(leftPanelId)!!.tabsComponent.addTab(createTab("left-tab"))
        state.getPanel(rightPanelId)!!.tabsComponent.addTab(createTab("right-tab"))

        // Focus left panel
        state.setActivePanel(leftPanelId)
        assertEquals(leftPanelId, state.activePanelId)

        state.preserveCurrentState("ws1")
        val activeTabs = state.collectAllActiveTabs(null, "w1")

        assertEquals(2, activeTabs.size)
        val ids = activeTabs.map { it.tabInfo.id }.toSet()
        assertTrue(ids.contains("right-tab"), "Right split's active tab must be reported even when unfocused")
    }

    @Test
    fun `selection changes keep background tabs discoverable for pop-out return`() {
        val panel = state.getPanel(state.activePanelId)!!
        panel.tabsComponent.addTab(createTab("tab1"))
        panel.tabsComponent.addTab(createTab("tab2"))
        state.preserveCurrentState("ws1")

        panel.tabsComponent.selectTab(0)
        val before = state.collectAllActiveTabs(null, "w1")
        panel.tabsComponent.selectTab(1)
        assertEquals(before, state.collectAllActiveTabs(null, "w1"))

        val background = state.collectAllActiveTabs(null, "w1").first { it.tabInfo.id == "tab1" }
        state.selectTabInPanel(background.tabInfo.id, background.panelId)
        assertEquals(
            "tab1",
            panel.tabsComponent.tabsState.value.activeTab
                ?.id,
        )
    }

    @Test
    fun `duplicate tab ids are deduplicated by seenTabIds`() {
        val leftPanelId = state.activePanelId
        val rightPanelId = state.splitPanel(leftPanelId, SplitOrientation.VERTICAL)

        // The same tab ID can be encountered more than once during a tree transition.
        val duplicateTab = createTab("shared-tab")
        state.getPanel(leftPanelId)!!.tabsComponent.addTab(duplicateTab)
        state.getPanel(rightPanelId)!!.tabsComponent.addTab(duplicateTab)

        state.preserveCurrentState("ws1")
        val activeTabs = state.collectAllActiveTabs(null, "w1")

        assertEquals(1, activeTabs.size, "SeenTabIds must prevent duplicates")
        assertEquals("shared-tab", activeTabs.single().tabInfo.id)
    }

    @Test
    fun `empty panel contributes nothing`() {
        // Do not add any tabs. The active tab is null.
        state.preserveCurrentState("ws1")
        val activeTabs = state.collectAllActiveTabs(null, "w1")

        assertTrue(activeTabs.isEmpty(), "An empty panel should contribute nothing")
    }

    @Test
    fun `tabs remain discoverable while a panel has no selection`() {
        val panel = state.getPanel(state.activePanelId)!!
        panel.tabsComponent.addTab(createTab("background"))
        panel.tabsComponent.selectTab(-1)
        state.preserveCurrentState("ws1")

        assertNull(panel.tabsComponent.tabsState.value.activeTab)
        assertEquals(
            "background",
            state
                .collectAllActiveTabs(null, "w1")
                .single()
                .tabInfo.id,
        )
    }

    @Test
    fun `duplicate tab ids across workspaces prefer the current workspace`() {
        state.preserveCurrentState("ws1")
        state.getPanel(state.activePanelId)!!.tabsComponent.addTab(createTab("shared-tab"))
        state.preserveCurrentState("ws2", "First workspace")
        state.clearAllPanels()
        state.getPanel(state.activePanelId)!!.tabsComponent.addTab(createTab("shared-tab"))

        val tab = state.collectAllActiveTabs(null, "w1").single()
        assertEquals("shared-tab", tab.tabInfo.id)
        assertEquals("ws2", tab.workspaceId)
    }

    @Test
    fun `preserved workspace inventory survives switching and restoration`() {
        state.preserveCurrentState("ws1")
        val firstPanel = state.getPanel(state.activePanelId)!!
        firstPanel.tabsComponent.addTab(createTab("old-background"))
        firstPanel.tabsComponent.addTab(createTab("old-selected"))
        // The name belongs to the outgoing workspace, ws1.
        state.preserveCurrentState("ws2", "First workspace")
        state.clearAllPanels()
        val secondPanel = state.getPanel(state.activePanelId)!!
        secondPanel.tabsComponent.addTab(createTab("new-background"))
        secondPanel.tabsComponent.addTab(createTab("new-selected"))

        val tabs = state.collectAllActiveTabs(null, "w1")
        assertEquals(4, tabs.size)
        val firstWorkspaceTabs = tabs.filter { it.workspaceId == "ws1" }
        val secondWorkspaceTabs = tabs.filter { it.workspaceId == "ws2" }
        assertEquals(setOf("old-background", "old-selected"), firstWorkspaceTabs.map { it.tabInfo.id }.toSet())
        assertEquals(setOf("new-background", "new-selected"), secondWorkspaceTabs.map { it.tabInfo.id }.toSet())
        assertTrue(tabs.all { it.windowId == "w1" && it.panelId == "main" })
        assertTrue(firstWorkspaceTabs.all { it.workspaceName == "First workspace" })

        state.preserveCurrentState("ws1", "Second workspace")
        assertTrue(state.restorePreservedState("ws1"))
        val restoredTabs = state.collectAllActiveTabs(null, "w1")
        assertEquals(tabs.map { it.tabInfo.id }.toSet(), restoredTabs.map { it.tabInfo.id }.toSet())
        assertEquals(4, restoredTabs.size, "Restored tabs must not be duplicated")
    }
}
