package ai.rever.boss.app

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Restoring a whole window: every Space it was running comes back LIVE, and the one that was
 * showing is the one on screen.
 *
 * Driven through the real [restoreLastSessionSet] against a real [SplitViewState], because the
 * claim is about a sequence of side effects on that state - apply, preserve, apply - and the two
 * ways it can go wrong are invisible to a pure test: dropping the preserve leaves one Space
 * running instead of three, and restoring in the wrong order leaves the window on a Space the user
 * was not looking at.
 */
class LastSessionSetRestoreTest {
    private class StubComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = CodeEditorTabType

        @Composable
        override fun Content() {
        }
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(CodeEditorTabType) { config, ctx -> StubComponent(ctx, config) }
        }

    @AfterTest
    fun tearDown() = TabUpdateRegistry.clear()

    private fun space(id: String) =
        LayoutWorkspace(
            id = id,
            name = "Space $id",
            description = "d",
            layout =
                SplitConfig.SinglePanel(
                    PanelConfig(
                        id = "panel-$id",
                        // One editor per Space, named after it, so a restored tree can be told
                        // from the Space next to it.
                        tabs = listOf(TabConfig(type = "editor", title = "$id.kt", filePath = "$PROJECT/$id.kt")),
                    ),
                ),
            projectPath = PROJECT,
        )

    private fun restore(set: LastSessionSet): SplitViewState {
        val splitViewState = SplitViewState(tabRegistry, windowId = "restore-test")
        val windowProjectState =
            WindowProjectState(windowId = "restore-test").apply {
                selectProject(Project(name = "proj", path = PROJECT, lastOpened = 0L))
            }
        runBlocking {
            restoreLastSessionSet(
                set = set,
                splitViewState = splitViewState,
                windowProjectState = windowProjectState,
                // The default reaches the global WorkspaceManager, which a test must not write to.
                onLoad = {},
            )
        }
        return splitViewState
    }

    private fun SplitViewState.titlesIn(workspaceId: String): List<String> =
        panelsInWorkspace(workspaceId).flatMap { panel ->
            panel.tabsComponent.tabsState.value.tabs
                .map { it.title }
        }

    @Test
    fun `every Space in the set comes back as a live Space this window is running`() {
        // The point of the whole feature. Before it, a restart brought back one Space and dropped
        // the rest, and `liveWorkspaceIds` would hold a single id here.
        val state = restore(LastSessionSet("b", listOf(space("a"), space("b"), space("c"))))

        assertEquals(setOf("a", "b", "c"), state.liveWorkspaceIds)
    }

    @Test
    fun `the Space that was showing is the one on screen`() {
        val state = restore(LastSessionSet("b", listOf(space("a"), space("b"), space("c"))))

        assertEquals("b", state.currentWorkspaceId)
        assertEquals(listOf("b.kt"), state.titlesIn("b"), "and its own tabs are the ones showing")
    }

    @Test
    fun `each restored Space keeps its own tabs`() {
        // The failure this catches is a preserve that files one Space's tree under another's id:
        // every id is live, and every one of them holds the same tabs.
        val state = restore(LastSessionSet("c", listOf(space("a"), space("b"), space("c"))))

        assertEquals(listOf("a.kt"), state.titlesIn("a"))
        assertEquals(listOf("b.kt"), state.titlesIn("b"))
        assertEquals(listOf("c.kt"), state.titlesIn("c"))
    }

    @Test
    fun `a restored Space can be switched back to without being rebuilt`() {
        // Which is what "live" means: `restorePreservedState` finds a tree rather than answering
        // false and leaving the caller to apply the layout from scratch.
        val state = restore(LastSessionSet("b", listOf(space("a"), space("b"))))

        state.preserveCurrentState("b", "Space b")
        assertTrue(state.restorePreservedState("a"), "the Space behind the one on screen is preserved")
        assertEquals(listOf("a.kt"), state.titlesIn("a"))
        assertEquals("a", state.currentWorkspaceId)
    }

    @Test
    fun `a two-Space set restores both`() {
        // The smallest set that is written at all, so it is the common case rather than an edge.
        val state = restore(LastSessionSet("a", listOf(space("a"), space("b"))))

        assertEquals(setOf("a", "b"), state.liveWorkspaceIds)
        assertEquals("a", state.currentWorkspaceId)
    }

    private companion object {
        const val PROJECT = "/tmp/restore-proj"
    }
}
