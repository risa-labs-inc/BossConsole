package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `applyWorkspace` must not clear the live tree before the incoming one is proven to build.
 *
 * `clearAllPanels()` ran first and `applyWorkspaceNode` skipped whatever it could not build -
 * a tab whose type belongs to a plugin uninstalled since the Space was saved resolves to
 * nothing - so switching to that Space wiped the live window and built nothing on top of it.
 * From the user's side that reads as "my work vanished".
 *
 * Driven through the real [applyWorkspace] on a real [SplitViewState], the same seam
 * [WorkspaceApplierMigrationTest] uses: the failure lives in the ordering between clearing and
 * building, which a pure-function test cannot see.
 */
class WorkspaceApplyFailClosedTest {
    private class StubTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(TerminalTabType) { config, ctx -> StubTabComponent(ctx, config, TerminalTabType) }
        }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    private fun newState(): Pair<SplitViewState, WindowProjectState> {
        val splitViewState = SplitViewState(tabRegistry, windowId = "fail-closed-window")
        val windowProjectState =
            WindowProjectState(windowId = "fail-closed-window").apply {
                selectProject(Project(name = "proj", path = "/tmp/boss-fail-closed-project", lastOpened = 0L))
            }
        return splitViewState to windowProjectState
    }

    private fun workspace(
        layout: SplitConfig,
        projectPath: String? = null,
    ) = LayoutWorkspace(
        id = "incoming",
        name = "Incoming",
        description = "d",
        layout = layout,
        projectPath = projectPath,
    )

    private fun tabsOnScreen(state: SplitViewState): List<TabInfo> {
        val panels = state.getAllPanels()
        return panels.flatMap { it.tabsComponent.tabsState.value.tabs }
    }

    /**
     * The failing-before test for the card: a Space whose tabs are all types nothing can
     * build. The live tree must still be there afterwards - and the window's project must not
     * have moved either, since nothing was entered.
     */
    @Test
    fun `a workspace whose tabs are all unknown types does not clear the live tree`() {
        val (state, projectState) = newState()
        // The work on screen: one live terminal the user is looking at.
        state.getPanelTabsComponent("main")!!.addTab(
            TerminalTabInfo(id = "live-terminal", title = "Live"),
        )

        val unbuildable =
            workspace(
                SinglePanel(
                    PanelConfig(
                        id = "main",
                        tabs =
                            listOf(
                                TabConfig(type = "uninstalled-plugin-tab", title = "Gone"),
                                TabConfig(type = "another-missing-type", title = "Also gone"),
                            ),
                    ),
                ),
                // A recorded project, so the refusal can also be checked for the one mutation
                // it must not perform: moving the window's selection.
                projectPath = "/tmp/boss-fail-closed-incoming",
            )

        val applied =
            runBlocking { applyWorkspace(unbuildable, state, projectState, restoreProject = true) }

        assertFalse(applied, "the refusal is reported so callers can keep their bookkeeping honest")
        assertEquals(
            listOf("live-terminal"),
            tabsOnScreen(state).map { it.id },
            "an unbuildable workspace must leave the live tree untouched",
        )
        assertNull(
            state.currentWorkspaceId,
            "and the refused workspace must not be claimed as current - the tree on screen " +
                "would otherwise be preserved under an id it does not belong to",
        )
        assertEquals(
            "/tmp/boss-fail-closed-project",
            projectState.selectedProject.value.path,
            "a refused apply is not a switch - the window's project stays where it was",
        )
    }

    @Test
    fun `a split whose leading tab is unbuildable still refuses when nothing else can build`() {
        // The split gate is on the FIRST leaf: a right side whose leading tab is gone is
        // skipped whole, so its later buildable-looking tabs must not count toward "can build".
        val (state, projectState) = newState()
        state.getPanelTabsComponent("main")!!.addTab(TerminalTabInfo(id = "live-terminal", title = "Live"))

        val unbuildable =
            workspace(
                VerticalSplit(
                    left =
                        SinglePanel(
                            PanelConfig(id = "l", tabs = listOf(TabConfig(type = "gone", title = "g"))),
                        ),
                    right =
                        SinglePanel(
                            PanelConfig(
                                id = "r",
                                tabs =
                                    listOf(
                                        TabConfig(type = "gone", title = "g"),
                                        TabConfig(type = "terminal", title = "Would land"),
                                    ),
                            ),
                        ),
                ),
            )

        val applied =
            runBlocking { applyWorkspace(unbuildable, state, projectState, restoreProject = false) }

        assertFalse(applied)
        assertEquals(listOf("live-terminal"), tabsOnScreen(state).map { it.id })
    }

    @Test
    fun `a buildable workspace still applies, claims its id and restores its project`() {
        val (state, projectState) = newState()

        val buildable =
            workspace(
                SinglePanel(
                    PanelConfig(
                        id = "main",
                        tabs = listOf(TabConfig(type = "terminal", title = "Term")),
                    ),
                ),
                projectPath = "/tmp/boss-fail-closed-incoming",
            )

        val applied =
            runBlocking { applyWorkspace(buildable, state, projectState, restoreProject = true) }

        assertTrue(applied)
        assertEquals(1, tabsOnScreen(state).size, "the workspace's one tab is built")
        assertEquals("incoming", state.currentWorkspaceId)
        // Deferring the selection until the build is proven must not drop it from the success
        // path: entering a Space still means entering its project.
        assertEquals("/tmp/boss-fail-closed-incoming", projectState.selectedProject.value.path)
    }

    @Test
    fun `one buildable tab cannot hide missing tabs and overwrite the saved layout`() {
        val (state, project) = newState()
        state.getPanelTabsComponent("main")!!.addTab(TerminalTabInfo(id = "live", title = "Live"))
        val partial =
            workspace(
                SinglePanel(
                    PanelConfig(
                        id = "main",
                        tabs =
                            listOf(
                                TabConfig(type = "terminal", title = "Present"),
                                TabConfig(type = "gone", title = "Gone"),
                            ),
                    ),
                ),
            )

        assertFalse(runBlocking { applyWorkspace(partial, state, project) })
        assertEquals(listOf("live"), tabsOnScreen(state).map { it.id })
        assertNull(state.currentWorkspaceId)
    }

    @Test
    fun `a workspace declaring no tabs is empty by design and still applies`() {
        val (state, projectState) = newState()
        state.getPanelTabsComponent("main")!!.addTab(TerminalTabInfo(id = "live-terminal", title = "Live"))

        val emptyByDesign = workspace(SinglePanel(PanelConfig(id = "main", tabs = emptyList())))

        val applied =
            runBlocking { applyWorkspace(emptyByDesign, state, projectState, restoreProject = false) }

        assertTrue(applied)
        assertEquals(
            emptyList(),
            tabsOnScreen(state),
            "an empty Space is a state to switch TO, not a failure to refuse",
        )
        assertEquals("incoming", state.currentWorkspaceId)
    }

    @Test
    fun `closing a preserved outgoing Space leaves incoming tabs intact`() =
        runBlocking {
            val (state, project) = newState()
            val layout = SinglePanel(PanelConfig("main", listOf(TabConfig(type = "terminal", title = "Term"))))
            assertTrue(applyWorkspace(workspace(layout).copy(id = "outgoing"), state, project))
            state.preserveCurrentState("outgoing", "Outgoing")
            assertTrue(applyWorkspace(workspace(layout), state, project))
            val incomingIds = tabsOnScreen(state).map { it.id }

            assertTrue(state.closeWorkspace("outgoing"))

            assertEquals("incoming", state.currentWorkspaceId)
            assertEquals(incomingIds, tabsOnScreen(state).map { it.id })
            assertEquals(1, incomingIds.size)
            assertFalse(state.hasPreservedState("outgoing"))
        }
}
