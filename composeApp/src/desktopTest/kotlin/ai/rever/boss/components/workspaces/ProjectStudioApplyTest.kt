package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitNode
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the real applyWorkspace of the Project Studio template - the first built-in whose
 * right/bottom side is itself a split, so the same recursive call carries the tab its
 * splitPanel just moved AND the tabs that came after it. The fix is in
 * `WorkspaceApplier.applyWorkspaceNode`: the recursive branch now passes `skipFirstTab = true`
 * so the moved tab is not added a second time. Without it, the bottom-left pane picked up
 * Build twice (the second landing on top of the first, with Agent then appended underneath)
 * and the same defect would have hit any future template with a nested right/bottom side.
 *
 * Drives the real [applyWorkspace] against the template's own [LayoutWorkspace] and walks the
 * resulting split tree the way [WorkspaceDirtyStateTest] does - asserted against the four-pane
 * "research + plan + build + demo" shape the description names, with the tab each pane carries.
 */
class ProjectStudioApplyTest {
    private class StubTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    /** Host stub for the jupyter tab type. The plugin's own JupyterTabType lives in
     *  `boss_plugins` and is not on this classpath; the id the [JupyterTabInfo] uses is the
     *  contract that matters for the applier's restore fallback. */
    private object StubJupyterTabType : TabTypeInfo {
        override val typeId: TabTypeId = JupyterTabInfo.TYPE_ID
        override val displayName = "Notebook"
        override val icon = Icons.Outlined.Code
    }

    /** Registry with all four Project Studio tab types registered. */
    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(FluckTabType) { config, ctx -> StubTabComponent(ctx, config, FluckTabType) }
            registerTabType(TerminalTabType) { config, ctx -> StubTabComponent(ctx, config, TerminalTabType) }
            registerTabType(CodeEditorTabType) { config, ctx -> StubTabComponent(ctx, config, CodeEditorTabType) }
            registerTabType(StubJupyterTabType) { config, ctx -> StubTabComponent(ctx, config, StubJupyterTabType) }
        }

    @AfterTest
    fun tearDown() = TabUpdateRegistry.clear()

    /**
     * The headline case for PR #1124: applying Project Studio must produce a four-pane tree
     * with the layout the description names (research / plan-and-notes / build-and-agent /
     * demo), and Build must not land twice in the bottom-left pane.
     */
    @Test
    fun `applying project studio lands every phase's tab exactly once`() {
        // The Project Studio template as shipped. The real [PredefinedWorkspaces] entry -
        // a copy is built here so the test does not depend on a particular order in
        // `allWorkspaces`, only on the SHAPE the entry describes.
        val studio = PredefinedWorkspaces.allWorkspaces.first { it.id == PredefinedWorkspaces.PROJECT_STUDIO_ID }

        val state = SplitViewState(tabRegistry, windowId = "project-studio-apply")
        runBlocking {
            applyWorkspace(
                workspace = studio,
                splitViewState = state,
                windowProjectState = null,
                restoreProject = false,
                warmEngine = {},
            )
        }

        // The tree must be a HorizontalSplit (outer row) whose top and bottom are both
        // VerticalSplits (the four-pane grid), with each leaf a SinglePanel of the right
        // tabs and tab titles in the right places.
        val root = state.rootNode
        assertTrue(root is SplitNode.HorizontalSplit, "outer split must be horizontal; got ${root::class.simpleName}")

        val top = (root as SplitNode.HorizontalSplit).top
        assertTrue(top is SplitNode.VerticalSplit, "top half must be a vertical split; got ${top::class.simpleName}")
        val bottom = root.bottom
        val bottomKind = "${bottom::class.simpleName}"
        assertTrue(
            bottom is SplitNode.VerticalSplit,
            "bottom half must be a vertical split; got $bottomKind",
        )

        val topLeft = (top as SplitNode.VerticalSplit).left
        val topRight = top.right
        val bottomLeft = (bottom as SplitNode.VerticalSplit).left
        val bottomRight = bottom.right

        assertTrue(topLeft is SplitNode.Panel, "top-left must be a panel")
        assertTrue(topRight is SplitNode.Panel, "top-right must be a panel")
        assertTrue(bottomLeft is SplitNode.Panel, "bottom-left must be a panel")
        assertTrue(bottomRight is SplitNode.Panel, "bottom-right must be a panel")

        assertEquals(listOf("Research"), tabTitles(topLeft as SplitNode.Panel))
        assertEquals(listOf("PLAN.md", "NOTES.md"), tabTitles(topRight as SplitNode.Panel))
        assertEquals(listOf("Build", "Agent"), tabTitles(bottomLeft as SplitNode.Panel))
        assertEquals(listOf("Demo notebook"), tabTitles(bottomRight as SplitNode.Panel))
    }

    /**
     * The other half of the headline: the fix matters even when the template's bottom side
     * is itself a single-tab SinglePanel, because the recursive branch was the one that
     * duplicated. With the fix, the SinglePanel branch is unchanged, and a single-tab
     * bottom-right pane ends up with exactly one tab.
     */
    @Test
    fun `a single-tab nested pane still lands one tab, not zero`() {
        // Hand-roll a minimal nested-split template: HorizontalSplit over a VerticalSplit,
        // where the bottom VerticalSplit's left has one tab and right has one tab. The fix
        // must skip the moved tab without dropping the rest of the panel's tabs.
        val terminal = TabConfig(type = "terminal", title = "Only", initialCommand = "echo")
        val workspace =
            LayoutWorkspace(
                id = "nested-test",
                name = "Nested test",
                description = "minimal nested split for the apply test",
                layout =
                    HorizontalSplit(
                        top =
                            VerticalSplit(
                                left =
                                    SinglePanel(
                                        PanelConfig(id = "p-top-left", tabs = listOf(terminal)),
                                    ),
                                right =
                                    SinglePanel(
                                        PanelConfig(id = "p-top-right", tabs = listOf(terminal)),
                                    ),
                            ),
                        bottom =
                            VerticalSplit(
                                left =
                                    SinglePanel(
                                        PanelConfig(id = "p-bottom-left", tabs = listOf(terminal)),
                                    ),
                                right =
                                    SinglePanel(
                                        PanelConfig(id = "p-bottom-right", tabs = listOf(terminal)),
                                    ),
                            ),
                    ),
            )

        val state = SplitViewState(tabRegistry, windowId = "nested-apply")
        runBlocking {
            applyWorkspace(
                workspace = workspace,
                splitViewState = state,
                windowProjectState = null,
                restoreProject = false,
                warmEngine = {},
            )
        }

        // Every leaf pane must end up with exactly one tab. If `skipFirstTab` were wrongly
        // applied to a SinglePanel branch that was never preceded by a splitPanel move, the
        // leaf would land zero tabs - the worst possible outcome for a hand-rolled layout.
        fun leaves(node: SplitNode): List<SplitNode.Panel> =
            when (node) {
                is SplitNode.Panel -> listOf(node)
                is SplitNode.VerticalSplit -> leaves(node.left) + leaves(node.right)
                is SplitNode.HorizontalSplit -> leaves(node.top) + leaves(node.bottom)
            }
        val allLeaves = leaves(state.rootNode)
        assertEquals(4, allLeaves.size, "the nested tree must have four leaf panes")
        allLeaves.forEach { panel ->
            assertEquals(1, panel.tabsComponent.tabsState.value.tabs.size, "every leaf pane lands one tab, not zero")
        }
    }

    /**
     * The 15s wait gate: when jupyter is NOT registered, the apply must not include it in
     * `requiredTabTypes` (the fallback in `createTabFromWorkspaceConfig` rebuilds it as an
     * editor tab). Without the fix, `awaitTabTypes` parked the whole apply on the plugin
     * registration timeout for every cold start without the optional plugin.
     */
    @Test
    fun `a layout with a jupyter tab does not park on the plugin wait when jupyter is absent`() {
        val registryWithoutJupyter =
            TabRegistry().apply {
                registerTabType(FluckTabType) { config, ctx -> StubTabComponent(ctx, config, FluckTabType) }
                registerTabType(TerminalTabType) { config, ctx -> StubTabComponent(ctx, config, TerminalTabType) }
                registerTabType(CodeEditorTabType) { config, ctx -> StubTabComponent(ctx, config, CodeEditorTabType) }
                // Deliberately no JupyterTabType.
            }

        val studio = PredefinedWorkspaces.allWorkspaces.first { it.id == PredefinedWorkspaces.PROJECT_STUDIO_ID }
        val state = SplitViewState(registryWithoutJupyter, windowId = "no-jupyter-apply")

        // The window for the timeout - if the jupyter wait is still on, this trips.
        runBlocking {
            withTimeout(2_000L) {
                applyWorkspace(
                    workspace = studio,
                    splitViewState = state,
                    windowProjectState = null,
                    restoreProject = false,
                    warmEngine = {},
                )
            }
        }

        // The jupyter pane must end up holding an editor tab (the fallback path) rather
        // than nothing - so the workspace is not silently broken by the absent plugin.
        val bottomRight = bottomRightLeaf(state.rootNode)
        assertNotNull(bottomRight, "bottom-right leaf must exist")
        val titles =
            bottomRight!!
                .tabsComponent
                .tabsState.value
                .tabs
                .map { it.title }
        assertEquals(listOf("Demo notebook"), titles, "missing jupyter falls back to an editor tab")
    }

    private fun tabTitles(panel: SplitNode.Panel): List<String> =
        panel
            .tabsComponent
            .tabsState.value
            .tabs
            .map { it.title }

    private fun bottomRightLeaf(root: SplitNode): SplitNode.Panel? =
        when (root) {
            is SplitNode.Panel -> root
            is SplitNode.HorizontalSplit -> bottomRightLeaf(root.bottom)
            is SplitNode.VerticalSplit -> bottomRightLeaf(root.right)
        }
}
