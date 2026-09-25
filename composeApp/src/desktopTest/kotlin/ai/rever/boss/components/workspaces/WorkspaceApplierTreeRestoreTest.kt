package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitNode
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the tree recursion of [applyWorkspace] against a real [SplitViewState]: split
 * panels are real tabs components, so "a tab materialized twice" is an observable panel
 * state here, not an inference. The restore contract under test: every saved tab that
 * can still be restored comes back EXACTLY ONCE, no matter how deeply its panel is
 * nested (#1210).
 */
class WorkspaceApplierTreeRestoreTest {
    private class StubComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = CodeEditorTabType

        @Composable
        override fun Content() = Unit
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(CodeEditorTabType) { config, ctx -> StubComponent(ctx, config) }
        }

    @AfterTest
    fun tearDown() = TabUpdateRegistry.clear()

    private var savedPanelCounter = 0

    private fun editorTab(name: String) =
        TabConfig(
            type = "editor",
            title = name,
            filePath = "$PROJECT/$name",
        )

    private fun panel(
        vararg tabs: TabConfig,
        pinnedCount: Int = 0,
    ): SplitConfig.SinglePanel {
        val id = "saved-panel-${savedPanelCounter++}"
        return SplitConfig.SinglePanel(
            PanelConfig(id = id, tabs = tabs.toList(), pinnedCount = pinnedCount),
        )
    }

    /** Applies [layout] into a fresh split state and returns the state for inspection. */
    private fun appliedState(layout: SplitConfig): SplitViewState {
        val splitViewState = SplitViewState(tabRegistry, windowId = WINDOW_ID)
        runBlocking {
            applyWorkspace(
                workspace =
                    LayoutWorkspace(
                        id = "ws-tree",
                        name = "ws",
                        description = "",
                        layout = layout,
                        projectPath = PROJECT,
                    ),
                splitViewState = splitViewState,
            )
        }
        return splitViewState
    }

    /** Every panel's tab titles, in split-tree order (left/top before right/bottom). */
    private fun restoredTitles(layout: SplitConfig): List<List<String>> {
        val state = appliedState(layout)
        return state.getAllPanels().map { panel ->
            panel.tabsComponent.tabsState.value.tabs
                .map { it.title }
        }
    }

    @Test
    fun `a nested split restores every tab exactly once`() {
        // Splitting the right side again used to materialize the nested subtree's first
        // tab twice: once as splitPanel's pre-created copy, once in the recursion's loop.
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("Left.kt")),
                right =
                    SplitConfig.VerticalSplit(
                        left = panel(editorTab("R1.kt"), editorTab("R2.kt")),
                        right = panel(editorTab("R3.kt")),
                    ),
            )

        assertEquals(
            listOf(
                listOf("Left.kt"),
                listOf("R1.kt", "R2.kt"),
                listOf("R3.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `a horizontal nest inside a vertical split restores without duplicates`() {
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("Left.kt")),
                right =
                    SplitConfig.HorizontalSplit(
                        top = panel(editorTab("T1.kt"), editorTab("T2.kt")),
                        bottom = panel(editorTab("B1.kt")),
                    ),
            )

        assertEquals(
            listOf(
                listOf("Left.kt"),
                listOf("T1.kt", "T2.kt"),
                listOf("B1.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `identical saved tabs in one pane each restore - none is consumed twice`() {
        // The pre-created copy must skip its consumed config by identity, not by
        // equality: an equality-based skip would drop one of two identical editor tabs.
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("Left.kt")),
                right = panel(editorTab("Same.kt"), editorTab("Same.kt"), editorTab("Other.kt")),
            )

        assertEquals(
            listOf(
                listOf("Left.kt"),
                listOf("Same.kt", "Same.kt", "Other.kt"),
            ),
            restoredTitles(layout),
        )
    }

    private sealed interface RestoredTree {
        data class Panel(val titles: List<String>) : RestoredTree
        data class VerticalSplit(val left: RestoredTree, val right: RestoredTree) : RestoredTree
        data class HorizontalSplit(val top: RestoredTree, val bottom: RestoredTree) : RestoredTree
    }

    private fun restoredTree(state: SplitViewState): RestoredTree =
        state.rootNode.toRestoredTree()

    private fun SplitNode.toRestoredTree(): RestoredTree =
        when (this) {
            is SplitNode.Panel ->
                RestoredTree.Panel(tabsComponent.tabsState.value.tabs.map { it.title })
            is SplitNode.VerticalSplit ->
                RestoredTree.VerticalSplit(left.toRestoredTree(), right.toRestoredTree())
            is SplitNode.HorizontalSplit ->
                RestoredTree.HorizontalSplit(top.toRestoredTree(), bottom.toRestoredTree())
        }

    private fun treePanel(vararg titles: String) = RestoredTree.Panel(titles.toList())

    @Test
    fun `a nested split on the left restores in saved order`() {
        val layout =
            SplitConfig.VerticalSplit(
                left =
                    SplitConfig.VerticalSplit(
                        left = panel(editorTab("A.kt")),
                        right = panel(editorTab("B.kt")),
                    ),
                right = panel(editorTab("C.kt")),
            )

        val state = appliedState(layout)

        assertEquals(
            RestoredTree.VerticalSplit(
                left =
                    RestoredTree.VerticalSplit(
                        left = treePanel("A.kt"),
                        right = treePanel("B.kt"),
                    ),
                right = treePanel("C.kt"),
            ),
            restoredTree(state),
        )
        assertEquals(
            listOf(
                listOf("A.kt"),
                listOf("B.kt"),
                listOf("C.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `a nested split on the top restores in saved order`() {
        val layout =
            SplitConfig.HorizontalSplit(
                top =
                    SplitConfig.HorizontalSplit(
                        top = panel(editorTab("A.kt")),
                        bottom = panel(editorTab("B.kt")),
                    ),
                bottom = panel(editorTab("C.kt")),
            )

        val state = appliedState(layout)

        assertEquals(
            RestoredTree.HorizontalSplit(
                top =
                    RestoredTree.HorizontalSplit(
                        top = treePanel("A.kt"),
                        bottom = treePanel("B.kt"),
                    ),
                bottom = treePanel("C.kt"),
            ),
            restoredTree(state),
        )
        assertEquals(
            listOf(
                listOf("A.kt"),
                listOf("B.kt"),
                listOf("C.kt"),
            ),
            restoredTitles(layout),
        )
    }

    private companion object {
        const val PROJECT = "/tmp/tree-restore-proj"
        const val WINDOW_ID = "tree-restore-nest-test"
    }
}
