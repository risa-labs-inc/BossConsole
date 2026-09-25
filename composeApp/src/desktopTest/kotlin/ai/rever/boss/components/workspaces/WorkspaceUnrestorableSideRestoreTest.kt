package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
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
 * The unrestorable-side half of the workspace restore contract: an initial tab whose
 * type no longer resolves (e.g. a plugin tab persisted as "unknown") must not take the
 * restorable tabs behind it with it (#1211), a side whose tabs are ALL unrestorable is
 * skipped without leaving a ghost panel, and pinned counts stay stable per panel.
 */
class WorkspaceUnrestorableSideRestoreTest {
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
                // No engine boot from a test: a layout that mentions a browser tab here
                // does it to prove that tab is NOT restored, and the desktop warm would
                // create the real browser profile directory for a tab that never lands.
                warmEngine = {},
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

    /** A plugin tab persisted as an unknown type: saved, but no longer restorable. */
    private fun unknownTab() =
        TabConfig(
            type = "unknown-plugin",
            title = "ghost",
        )

    /**
     * A saved browser tab. Restore builds a `FluckTabInfo` for it unconditionally - it is
     * the registry, not the builder, that decides whether the tab can live in a panel, so
     * with no browser factory registered this config still produces a TabInfo whose type
     * has no factory. Exactly the case [firstRestorableTab] must judge on the tab it built.
     */
    private fun browserTab(name: String) =
        TabConfig(
            type = "browser",
            title = name,
            url = "https://$name.example.com/",
        )

    @Test
    fun `an unrestorable first tab in a split pane does not drop the restorable tab`() {
        // [unknown, editor] in the right pane used to lose the editor on restart; the
        // loss was position-dependent, which is the broken invariant.
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("Left.kt")),
                right = panel(unknownTab(), editorTab("Kept.kt")),
            )

        assertEquals(
            listOf(
                listOf("Left.kt"),
                listOf("Kept.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `a pane whose tabs are all unrestorable is skipped without leaving a ghost panel`() {
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("Left.kt")),
                right = panel(unknownTab(), unknownTab()),
            )

        assertEquals(listOf(listOf("Left.kt")), restoredTitles(layout))
    }

    @Test
    fun `a saved tab whose built type has no registered factory opens no ghost panel`() {
        // The saved browser tab still BUILDS a FluckTabInfo (the builder has no registry
        // check), but with no browser factory registered addTab drops it. Judged on the
        // saved config alone the side looks restorable, so splitPanel opened a second
        // panel whose only tab was then dropped - a ghost empty panel. The gate must
        // require a factory for the tab it actually built, so the side counts as
        // unrestorable and no split happens at all.
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("Left.kt")),
                right = panel(browserTab("Ghost")),
            )

        assertEquals(listOf(listOf("Left.kt")), restoredTitles(layout))
    }

    @Test
    fun `a nested split whose left side restores nothing folds in instead of leaving a ghost`() {
        // The nested left panel holds only unrestorable tabs, so the panel created for
        // the nested subtree would stay empty once the nested right side splits away.
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("Left.kt")),
                right =
                    SplitConfig.VerticalSplit(
                        left = panel(unknownTab()),
                        right = panel(editorTab("Kept.kt")),
                    ),
            )

        assertEquals(
            listOf(
                listOf("Left.kt"),
                listOf("Kept.kt"),
            ),
            restoredTitles(layout),
        )
    }

    @Test
    fun `pinned counts restore per panel and clamp when unrestorable tabs are gone`() {
        val layout =
            SplitConfig.VerticalSplit(
                left = panel(editorTab("A.kt"), editorTab("B.kt"), pinnedCount = 1),
                right = panel(unknownTab(), editorTab("C.kt"), pinnedCount = 2),
            )

        val panels = appliedState(layout).getAllPanels()

        assertEquals(2, panels.size)
        assertEquals(
            1,
            panels[0].tabsComponent.pinnedCount,
            "the left panel's pin survived restore",
        )
        assertEquals(
            1,
            panels[1].tabsComponent.pinnedCount,
            "pins clamp to restored tabs, not saved ghosts",
        )
    }

    private companion object {
        const val PROJECT = "/tmp/tree-restore-proj"
        const val WINDOW_ID = "tree-restore-skip-test"
    }
}
