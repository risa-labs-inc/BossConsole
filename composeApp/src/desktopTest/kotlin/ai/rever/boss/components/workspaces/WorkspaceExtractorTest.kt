package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.plugin.tab_types.PanelHostTabInfo
import ai.rever.boss.components.plugin.tab_types.PanelHostTabType
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins down [extractCurrentWorkspace]: the SplitViewState → SplitConfig tree walk
 * that serializes the live split layout (workspace save / session restore).
 *
 * Uses real [SplitViewState] / BossTabsComponent fixtures (same approach as
 * BossTabsComponentMoveTest) so the walk runs over the exact runtime tree shape.
 *
 * Note: neither SplitNode nor SplitConfig carries a split ratio, so there is no
 * ratio to preserve — structure, panel ids, and tab mapping are the contract.
 */
class WorkspaceExtractorTest {

    private object JupyterStubType : TabTypeInfo {
        override val typeId = JupyterTabInfo.TYPE_ID
        override val displayName = "Notebook"
        override val icon = Icons.Outlined.Code
    }

    private object CustomTabType : TabTypeInfo {
        override val typeId = TabTypeId("custom-widget", "test.plugin")
        override val displayName = "Custom Widget"
        override val icon = Icons.Outlined.Language
    }

    private data class CustomTabInfo(
        override val id: String,
        override val title: String
    ) : TabInfo {
        override val typeId: TabTypeId = CustomTabType.typeId
        override val icon get() = Icons.Outlined.Language
    }

    /** Minimal stand-in component; the extractor only reads TabInfo, never the component. */
    private class StubTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo
    ) : TabComponentWithUI, ComponentContext by ctx {
        @Composable
        override fun Content() {
            // Fixture tab renders nothing; extraction only reads tab metadata.
        }
    }

    private val tabRegistry = TabRegistry().apply {
        listOf(TerminalTabType, CodeEditorTabType, FluckTabType, JupyterStubType, CustomTabType, PanelHostTabType)
            .forEach { type ->
                registerTabType(type) { config, ctx -> StubTabComponent(ctx, config, type) }
            }
    }

    private fun newSplitViewState() = SplitViewState(tabRegistry, windowId = "test-window")

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    // ==================== layout structure ====================

    @Test
    fun `fresh state extracts as a single empty main panel`() {
        val workspace = extractCurrentWorkspace(newSplitViewState())

        val layout = assertIs<SinglePanel>(workspace.layout)
        assertEquals("main", layout.panel.id)
        assertTrue(layout.panel.tabs.isEmpty())
    }

    @Test
    fun `vertical split extracts with original panel on the left`() {
        val state = newSplitViewState()
        val newPanelId = state.splitPanel("main", SplitOrientation.VERTICAL)

        val layout = assertIs<VerticalSplit>(extractCurrentWorkspace(state).layout)
        assertEquals("main", assertIs<SinglePanel>(layout.left).panel.id)
        assertEquals(newPanelId, assertIs<SinglePanel>(layout.right).panel.id)
    }

    @Test
    fun `horizontal split extracts with original panel on top`() {
        val state = newSplitViewState()
        val newPanelId = state.splitPanel("main", SplitOrientation.HORIZONTAL)

        val layout = assertIs<HorizontalSplit>(extractCurrentWorkspace(state).layout)
        assertEquals("main", assertIs<SinglePanel>(layout.top).panel.id)
        assertEquals(newPanelId, assertIs<SinglePanel>(layout.bottom).panel.id)
    }

    @Test
    fun `nested splits extract the full tree with tabs in the right panels`() {
        val state = newSplitViewState()
        state.getPanel("main")!!.tabsComponent.addTab(TerminalTabInfo(id = "t-main", title = "Main Term"))

        val rightId = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.getPanel(rightId)!!.tabsComponent.addTab(TerminalTabInfo(id = "t-right", title = "Right Term"))

        val bottomId = state.splitPanel(rightId, SplitOrientation.HORIZONTAL)
        state.getPanel(bottomId)!!.tabsComponent.addTab(TerminalTabInfo(id = "t-bottom", title = "Bottom Term"))

        // main | (right / bottom)
        val layout = assertIs<VerticalSplit>(extractCurrentWorkspace(state).layout)
        val left = assertIs<SinglePanel>(layout.left)
        assertEquals("main", left.panel.id)
        assertEquals(listOf("Main Term"), left.panel.tabs.map { it.title })

        val rightSplit = assertIs<HorizontalSplit>(layout.right)
        val top = assertIs<SinglePanel>(rightSplit.top)
        assertEquals(rightId, top.panel.id)
        assertEquals(listOf("Right Term"), top.panel.tabs.map { it.title })

        val bottom = assertIs<SinglePanel>(rightSplit.bottom)
        assertEquals(bottomId, bottom.panel.id)
        assertEquals(listOf("Bottom Term"), bottom.panel.tabs.map { it.title })
    }

    @Test
    fun `empty side panels extract as panels with no tabs`() {
        val state = newSplitViewState()
        state.getPanel("main")!!.tabsComponent.addTab(TerminalTabInfo(id = "t-1", title = "Term"))
        val newPanelId = state.splitPanel("main", SplitOrientation.VERTICAL)

        val layout = assertIs<VerticalSplit>(extractCurrentWorkspace(state).layout)
        assertEquals(1, assertIs<SinglePanel>(layout.left).panel.tabs.size)
        val right = assertIs<SinglePanel>(layout.right)
        assertEquals(newPanelId, right.panel.id)
        assertTrue(right.panel.tabs.isEmpty())
    }

    // ==================== tab-info mapping ====================

    @Test
    fun `terminal tab maps command and working directory`() {
        val state = newSplitViewState()
        state.getPanel("main")!!.tabsComponent.addTab(
            TerminalTabInfo(
                id = "term-1",
                title = "Terminal: build",
                initialCommand = "./gradlew build",
                workingDirectory = "/repo"
            )
        )

        val tab = singleTab(state)
        assertEquals("terminal", tab.type)
        assertEquals("Terminal: build", tab.title)
        assertEquals("./gradlew build", tab.initialCommand)
        assertEquals("/repo", tab.workingDirectory)
        assertNull(tab.url)
        assertNull(tab.filePath)
    }

    @Test
    fun `editor tab maps file path`() {
        val state = newSplitViewState()
        state.getPanel("main")!!.tabsComponent.addTab(
            EditorTabInfo(id = "ed-1", title = "App.kt", filePath = "/repo/src/App.kt")
        )

        val tab = singleTab(state)
        assertEquals("editor", tab.type)
        assertEquals("App.kt", tab.title)
        assertEquals("/repo/src/App.kt", tab.filePath)
    }

    @Test
    fun `jupyter tab maps file path`() {
        val state = newSplitViewState()
        state.getPanel("main")!!.tabsComponent.addTab(
            JupyterTabInfo(id = "jp-1", title = "analysis.ipynb", filePath = "/repo/analysis.ipynb")
        )

        val tab = singleTab(state)
        assertEquals("jupyter", tab.type)
        assertEquals("analysis.ipynb", tab.title)
        assertEquals("/repo/analysis.ipynb", tab.filePath)
    }

    @Test
    fun `browser tab saves the CURRENT url, not the initial one`() {
        val state = newSplitViewState()
        val fluckTab = FluckTabInfo(
            id = "fluck-1",
            typeId = FluckTabType.typeId,
            _title = "Docs",
            url = "https://start.example",
            faviconCacheKey = "fav-1"
        ).updateNavigation("Docs 2", "https://current.example/page")
        state.getPanel("main")!!.tabsComponent.addTab(fluckTab)

        val tab = singleTab(state)
        assertEquals("browser", tab.type)
        // Restoring the workspace must reopen where the user actually is.
        assertEquals("https://current.example/page", tab.url)
        assertEquals("fav-1", tab.faviconCacheKey)
    }

    @Test
    fun `unrecognized tab types map to unknown with title only`() {
        val state = newSplitViewState()
        state.getPanel("main")!!.tabsComponent.addTab(CustomTabInfo(id = "c-1", title = "Widget"))

        val tab = singleTab(state)
        assertEquals("unknown", tab.type)
        assertEquals("Widget", tab.title)
        assertNull(tab.url)
        assertNull(tab.filePath)
    }

    @Test
    fun `transient panel-host tabs are excluded, other tabs keep their order`() {
        val state = newSplitViewState()
        val component = state.getPanel("main")!!.tabsComponent
        component.addTab(TerminalTabInfo(id = "term-1", title = "Term"))
        // Sidebar-promoted panel tab: persisting it would serialize as "unknown"
        // and crash WorkspaceApplier on restore.
        component.addTab(PanelHostTabInfo(PanelId("test-panel", 1), "Promoted", Icons.Outlined.Language))
        component.addTab(EditorTabInfo(id = "ed-1", title = "App.kt", filePath = "/repo/App.kt"))

        val layout = assertIs<SinglePanel>(extractCurrentWorkspace(state).layout)
        assertEquals(listOf("terminal" to "Term", "editor" to "App.kt"), layout.panel.tabs.map { it.type to it.title })
    }

    // ==================== workspace metadata ====================

    @Test
    fun `defaults - generated id, default name and description, blank project path becomes null`() {
        val workspace = extractCurrentWorkspace(newSplitViewState())

        assertTrue(workspace.id.startsWith("workspace-"))
        assertEquals("Current", workspace.name)
        assertEquals("Current layout workspace", workspace.description)
        assertNull(workspace.projectPath)
        assertTrue(workspace.timestamp > 0)
    }

    @Test
    fun `explicit name, description and project path are preserved`() {
        val workspace = extractCurrentWorkspace(
            newSplitViewState(),
            projectPath = "/Users/dev/proj",
            name = "My Workspace",
            description = "Saved layout"
        )

        assertEquals("My Workspace", workspace.name)
        assertEquals("Saved layout", workspace.description)
        assertEquals("/Users/dev/proj", workspace.projectPath)
    }

    private fun singleTab(state: SplitViewState): TabConfig {
        val layout = assertIs<SinglePanel>(extractCurrentWorkspace(state).layout)
        assertEquals(1, layout.panel.tabs.size)
        return layout.panel.tabs.single()
    }
}
