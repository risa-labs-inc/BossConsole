package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.composer.ComposerTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabType
import ai.rever.boss.plugin.tab.diff.DiffTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the RESTORE side of the layout round trip: [createTabFromWorkspaceConfig]
 * over a saved [TabConfig] tree.
 *
 * [WorkspaceExtractorTest] covers the extract side; this test exists because
 * the two sides are separate decisions and used to disagree. A blank diff
 * path used to restore as a diff tab that can never show anything, while the
 * composer branch refused a blank session id and the extractor refused to
 * persist a blank path - three answers to "no scope". All three must agree
 * on "no scope, no tab".
 */
class WorkspaceApplierRestoreTest {
    private val tabRegistry =
        TabRegistry().apply {
            listOf(CodeEditorTabType, DiffTabType, ComposerTabType).forEach { type ->
                registerTabType(type) { _, _ -> throw UnsupportedOperationException("not used by restore") }
            }
        }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    private fun restore(tabConfig: TabConfig): TabInfo? =
        createTabFromWorkspaceConfig(
            tabConfig = tabConfig,
            resolvedProjectPath = "/tmp/proj",
            splitViewState = SplitViewState(tabRegistry, windowId = "restore-test"),
        )

    // ==================== diff tabs ====================

    @Test
    fun `a saved file diff restores as a working tree diff of that file`() {
        val tab =
            restore(
                TabConfig(
                    type = "diff",
                    title = "main.kt",
                    filePath = "src/main.kt",
                ),
            )

        val diff = assertIs<DiffTabInfo>(tab)
        assertEquals("src/main.kt", diff.filePath)
        assertTrue(
            !diff.staged,
            "the extractor only persists working-tree diffs, so restore must not invent staged ones",
        )
        assertNull(diff.fromRef)
        assertNull(diff.toRef)
    }

    @Test
    fun `a blank diff path restores no tab`() {
        // A corrupt or hand-edited layout: no scope, no tab. Agreeing with the
        // composer branch (blank session id) and the extractor (refuses to
        // persist a blank path) is the contract this test exists for.
        val tab =
            restore(
                TabConfig(
                    type = "diff",
                    title = "Diff",
                    filePath = "",
                ),
            )
        assertNull(tab, "a diff tab with no scope can never show anything; it must not be restored")
    }

    @Test
    fun `a null diff path restores no tab`() {
        val tab = restore(TabConfig(type = "diff", title = "Diff"))
        assertNull(tab)
    }

    // ==================== composer tabs ====================

    @Test
    fun `a saved composer tab restores with its session id`() {
        val tab =
            restore(
                TabConfig(
                    type = "composer",
                    title = "Composer",
                    filePath = "session-abc123",
                ),
            )

        val composer = assertIs<ComposerTabInfo>(tab)
        assertEquals("session-abc123", composer.sessionId)
    }

    @Test
    fun `a blank composer session id restores no tab`() {
        // The branch the diff one used to disagree with; the round trip only
        // holds if both sides drop the scopeless tab.
        val tab =
            restore(
                TabConfig(
                    type = "composer",
                    title = "Composer",
                    filePath = "",
                ),
            )
        assertNull(tab, "a composer tab with no session id cannot reload a session")
    }

    @Test
    fun `an unknown tab type restores nothing rather than crashing the layout`() {
        val tab = restore(TabConfig(type = "mystery", title = "?"))
        assertNull(tab)
    }

    // ===========================================================================
    // Nested split subtree restoration (regression for #1210).
    //
    // `applyWorkspaceNode` previously materialised the first tab of any nested split
    // subtree TWICE - once via `splitPanel(tabToMove = firstRightTabInfo)`'s copy and
    // again when the recursion's SinglePanel branch added every config back to its
    // panel. Pinned counts shifted with the duplicate. A browser tab meant two live
    // Chromium processes for one saved URL.
    //
    // The tests below build a real nested tree fixture (Outer vertical whose right is
    // a vertical whose right is a SinglePanel of three tabs), apply it to a fresh
    // `SplitViewState`, and pin that every persisted tab lands EXACTLY ONCE in the
    // pane that was saved to hold it.
    // ===========================================================================

    private object RestoreTabType : TabTypeInfo {
        override val typeId = TabTypeId("restore-test", "test.plugin")
        override val displayName = "Restore Test"
        override val icon = Icons.Outlined.Language
    }

    private class RestoreTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() {
            // Fixture; the applier only exercises tab creation and pin counting.
        }
    }

    /** Registry that can build every tab type the nested fixtures use. */
    private val restoreTabRegistry =
        TabRegistry().apply {
            listOf(TerminalTabType, CodeEditorTabType, FluckTabType).forEach { type ->
                registerTabType(type) { config, ctx -> RestoreTabComponent(ctx, config, type) }
            }
            registerTabType(RestoreTabType) { config, ctx -> RestoreTabComponent(ctx, config, RestoreTabType) }
        }

    private fun newRestoreSplitViewState() = SplitViewState(restoreTabRegistry, windowId = "restore-test-window")

    /**
     * Outer vertical split whose right is itself a vertical split (top = `B`, bottom =
     * `SinglePanel([X, Y, Z])`). The first tab the restore encounters via
     * `getFirstTab(node.right)` is `X`; before the fix that tab was added twice.
     */
    private fun nestedRightWorkspace(): LayoutWorkspace =
        LayoutWorkspace(
            id = "nested-right",
            name = "Nested Right",
            description = "Outer vertical split whose right is a vertical split",
            layout =
                VerticalSplit(
                    left =
                        SinglePanel(
                            PanelConfig(
                                id = "main",
                                tabs = listOf(TabConfig("terminal", "Main Term")),
                                pinnedCount = 0,
                            ),
                        ),
                    right =
                        VerticalSplit(
                            left =
                                SinglePanel(
                                    PanelConfig(
                                        id = "right-top",
                                        tabs = listOf(TabConfig("terminal", "Right Top B")),
                                        pinnedCount = 0,
                                    ),
                                ),
                            right =
                                SinglePanel(
                                    PanelConfig(
                                        id = "right-bottom",
                                        tabs =
                                            listOf(
                                                TabConfig("terminal", "First X"),
                                                TabConfig("terminal", "Second Y"),
                                                TabConfig("terminal", "Third Z"),
                                            ),
                                        pinnedCount = 1,
                                    ),
                                ),
                        ),
                ),
        )

    /** Mirror image using the horizontal branch, which carries the same shape of bug. */
    private fun nestedBottomWorkspace(): LayoutWorkspace =
        LayoutWorkspace(
            id = "nested-bottom",
            name = "Nested Bottom",
            description = "Outer horizontal split whose bottom is a horizontal split",
            layout =
                HorizontalSplit(
                    top =
                        SinglePanel(
                            PanelConfig(
                                id = "main",
                                tabs = listOf(TabConfig("terminal", "Main Term")),
                                pinnedCount = 0,
                            ),
                        ),
                    bottom =
                        HorizontalSplit(
                            top =
                                SinglePanel(
                                    PanelConfig(
                                        id = "bot-top",
                                        tabs = listOf(TabConfig("terminal", "Bottom Top B")),
                                        pinnedCount = 0,
                                    ),
                                ),
                            bottom =
                                SinglePanel(
                                    PanelConfig(
                                        id = "bot-bottom",
                                        tabs =
                                            listOf(
                                                TabConfig("terminal", "First X"),
                                                TabConfig("terminal", "Second Y"),
                                                TabConfig("terminal", "Third Z"),
                                            ),
                                        pinnedCount = 1,
                                    ),
                                ),
                        ),
                ),
        )

    private fun tabTitlesByPanel(state: SplitViewState): Map<String, List<String>> =
        state.getAllPanels().associate { panel ->
            panel.id to
                panel.tabsComponent.tabsState.value.tabs
                    .map { it.title }
        }

    @Test
    fun `nested vertical split restores every tab exactly once`() =
        runBlocking {
            val state = newRestoreSplitViewState()
            applyWorkspace(nestedRightWorkspace(), state, windowProjectState = null)

            // Saved panel ids are NOT preserved through applyWorkspace (splitPanel mints
            // its own ids on each new pane); what matters is the content of each panel.
            // The nested subtree restores as three panes, each with exactly the tabs the
            // fixture saved to it, so the union by tab title is the assertion.
            val byTitle = tabTitlesByPanel(state)
            assertEquals(3, byTitle.size, "nested restore must produce three panes, got $byTitle")
            assertEquals(
                listOf("Main Term"),
                byTitle.values.single { it.size == 1 && it.single() == "Main Term" },
                "main pane holds only its saved tab",
            )
            assertEquals(
                listOf("Right Top B"),
                byTitle.values.single { it.size == 1 && it.single() == "Right Top B" },
                "nested top pane holds only its saved tab",
            )
            assertEquals(
                listOf("First X", "Second Y", "Third Z"),
                byTitle.values.single { it.size == 3 },
                "deepest pane holds the saved three-tab set, exactly once each",
            )
        }

    @Test
    fun `nested horizontal split restores every tab exactly once`() =
        runBlocking {
            val state = newRestoreSplitViewState()
            applyWorkspace(nestedBottomWorkspace(), state, windowProjectState = null)

            val byTitle = tabTitlesByPanel(state)
            assertEquals(3, byTitle.size, "horizontal nested restore must produce three panes, got $byTitle")
            assertEquals(
                listOf("Main Term"),
                byTitle.values.single { it.size == 1 && it.single() == "Main Term" },
                "main pane holds only its saved tab",
            )
            assertEquals(
                listOf("Bottom Top B"),
                byTitle.values.single { it.size == 1 && it.single() == "Bottom Top B" },
                "nested top pane holds only its saved tab",
            )
            assertEquals(
                listOf("First X", "Second Y", "Third Z"),
                byTitle.values.single { it.size == 3 },
                "deepest pane holds the saved three-tab set, exactly once each",
            )
        }

    @Test
    fun `nested split restores the saved pinned count on the deepest panel`() =
        runBlocking {
            val state = newRestoreSplitViewState()
            applyWorkspace(nestedRightWorkspace(), state, windowProjectState = null)

            // The deepest panel is whichever pane holds all three of X/Y/Z; the saved pinned
            // count of 1 must survive on it - the duplicate-first-tab bug shifted this to 0
            // because the duplicate moved the first tab into the unpinned block.
            val deepest =
                state.getAllPanels().single { panel ->
                    panel.tabsComponent.tabsState.value.tabs
                        .map { it.title } ==
                        listOf("First X", "Second Y", "Third Z")
                }
            assertEquals(
                1,
                deepest.tabsComponent.pinnedCount,
                "first tab of the deepest panel stays pinned (a duplicate insert would shift this)",
            )
        }

    @Test
    fun `nested split restore yields exactly one tab per persisted title`() =
        runBlocking {
            val state = newRestoreSplitViewState()
            applyWorkspace(nestedRightWorkspace(), state, windowProjectState = null)

            // The real signal for the duplicate path: two tabs of the same title in the same
            // (or different) pane. applier-generated tab ids would hide this; titles survive.
            val titles = tabTitlesByPanel(state).values.flatten()
            assertEquals(
                titles.size,
                titles.distinct().size,
                "no tab title appears twice in the restored tree",
            )
            assertEquals(5, titles.size, "Main + B + X + Y + Z = 5 tabs after restore")
        }
}
