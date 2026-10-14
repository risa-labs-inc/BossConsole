package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.workspace.SplitConfig
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Whether the Space on screen has changes that are not on disk.
 *
 * **These are measurements, not assertions about intent.** A save affordance that is permanently
 * lit is worse than none, and the naive comparison IS permanently true - so each normalisation
 * below is pinned by driving the real thing: a real [SplitViewState] with a real split in it,
 * extracted by the real [extractCurrentWorkspace], saved the way `saveCurrentWorkspace` saves,
 * and applied back by the real [applyWorkspace]. What the first run of this printed is in
 * `WorkspaceDirtyState.kt`.
 *
 * The two facts that matter, both of which a reasoned test would have missed:
 *
 * - Two extracts of an unchanged window differ in `id` and `timestamp`, because the extractor
 *   mints a `generateId()` and reads the clock every call. Its `name` and `description` are
 *   always "Current" too, where a saved Space carries its own.
 * - After a RESTORE the layouts differ in the panel ids and nothing else - identical tabs,
 *   identical pinned counts, identical shape - because `applyWorkspace` throws the saved ids away
 *   and mints new ones.
 */
class WorkspaceDirtyStateTest {
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

    private var nextWindow = 0

    private fun newState() = SplitViewState(tabRegistry, windowId = "dirty-${nextWindow++}")

    private fun editor(path: String) =
        EditorTabInfo(
            id = "editor-$path",
            typeId = CodeEditorTabType.typeId,
            title = path.substringAfterLast('/'),
            filePath = path,
        )

    private fun SplitViewState.addEditor(
        panelId: String,
        path: String,
    ) {
        getPanel(panelId)!!.tabsComponent.addTab(editor(path))
    }

    private fun extract(state: SplitViewState) =
        extractCurrentWorkspace(
            state,
            projectPath = PROJECT,
            defaultWorkingDirectory = "/tmp/dirty-default",
        )

    /** What `saveCurrentWorkspace` writes: the Space's own identity, carrying the live layout. */
    private fun savedFrom(live: LayoutWorkspace) =
        LayoutWorkspace(
            id = "workspace-1",
            name = "My Space",
            description = "Saved workspace",
            layout = live.layout,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            projectPath = PROJECT,
        )

    /** A window with a vertical split: one editor on the left, one on the right. */
    private fun splitWindow(): SplitViewState {
        val state = newState()
        state.addEditor("main", "$PROJECT/a.kt")
        val right = state.splitPanel("main", SplitOrientation.VERTICAL)
        state.addEditor(right, "$PROJECT/b.kt")
        return state
    }

    private fun panelIdsOf(layout: SplitConfig): List<String> =
        when (layout) {
            is SplitConfig.SinglePanel -> listOf(layout.panel.id)
            is SplitConfig.VerticalSplit -> panelIdsOf(layout.left) + panelIdsOf(layout.right)
            is SplitConfig.HorizontalSplit -> panelIdsOf(layout.top) + panelIdsOf(layout.bottom)
        }

    // ==================== the measurements ====================

    /**
     * The headline case, and the one the whole feature turns on. Saving must leave the Space
     * CLEAN, or the button it drives is on for ever.
     */
    @Test
    fun `a Space reads as saved immediately after it is saved`() {
        val state = splitWindow()
        val saved = savedFrom(extract(state))

        // A FRESH extract, not the one that was saved: the affordance is recomputed from a new
        // walk of the live tree every time anything in it changes, and every one of those walks
        // carries a new id and a new clock reading.
        Thread.sleep(CLOCK_TICK)
        assertFalse(
            isUnsaved(extract(state), saved),
            "saving must leave the Space clean; a permanently lit save button is worse than none",
        )
    }

    /**
     * The measurement behind the identity half of the normalisation. Stated as its own test so the
     * REASON the comparison cannot be `==` is pinned, not just its consequence.
     */
    @Test
    fun `two extracts of an unchanged window are not equal, and differ only in identity`() {
        val state = splitWindow()
        val first = extract(state)
        Thread.sleep(CLOCK_TICK)
        val second = extract(state)

        assertNotEquals(first, second, "the extractor mints an id and reads the clock every call")
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.timestamp, second.timestamp)
        // Everything the user could have changed is identical, which is why normalising the rest
        // is safe rather than lenient.
        assertEquals(first.layout, second.layout)
        assertEquals(first.projectPath, second.projectPath)
        assertFalse(isUnsaved(second, first))
    }

    /**
     * The measurement behind the panel-id half. `applyWorkspace` calls `clearAllPanels()` and then
     * `splitPanel`, which mints an id per pane, so a saved id is a record of the session that
     * wrote it. Restore maps panes by POSITION, which is why position is the normalisation.
     */
    @Test
    fun `a restored Space reads as saved, though its panel ids are all new`() {
        val saved = savedFrom(extract(splitWindow()))

        val restored = newState()
        runBlocking {
            applyWorkspace(
                workspace = saved,
                splitViewState = restored,
                windowProjectState = null,
                warmEngine = {},
            )
        }
        val live = extract(restored)

        assertNotEquals(
            panelIdsOf(saved.layout),
            panelIdsOf(live.layout),
            "if the ids ever start matching, this test is no longer measuring anything",
        )
        assertNotEquals(saved.layout, live.layout, "so the raw layouts differ after a restore")
        assertFalse(isUnsaved(live, saved), "and the Space is nevertheless clean")
    }

    // ==================== what still counts as dirt ====================

    @Test
    fun `a Space that has never been saved is unsaved`() {
        // The other half of the rule: `workspaces` holds what is on disk, so a Space missing from
        // it is one nothing can restore. A template applied as-is lands here.
        assertTrue(isUnsaved(extract(splitWindow()), saved = null))
    }

    @Test
    fun `splitting a pane makes the Space unsaved`() {
        val state = splitWindow()
        val saved = savedFrom(extract(state))

        val third = state.splitPanel("main", SplitOrientation.HORIZONTAL)
        state.addEditor(third, "$PROJECT/c.kt")

        assertTrue(isUnsaved(extract(state), saved), "a new pane is a change to the layout")
    }

    @Test
    fun `adding a tab makes the Space unsaved`() {
        val state = splitWindow()
        val saved = savedFrom(extract(state))

        state.addEditor("main", "$PROJECT/d.kt")

        assertTrue(isUnsaved(extract(state), saved))
    }

    @Test
    fun `moving a tab to the other pane makes the Space unsaved`() {
        // The case that a panel-id normalisation done carelessly would swallow: both trees have
        // two panes with two tabs between them, and only WHICH pane holds which tab differs. A
        // normalisation that dropped pane identity instead of renumbering it would read clean.
        val state = splitWindow()
        val saved = savedFrom(extract(state))

        val panes = panelIdsOf(extract(state).layout)
        state.getPanel(panes[1])!!.tabsComponent.removeTab(0)
        state.addEditor(panes[0], "$PROJECT/b.kt")

        assertTrue(isUnsaved(extract(state), saved), "which pane a tab is in is part of the layout")
    }

    @Test
    fun `selecting a different project makes the Space unsaved`() {
        // A Space remembers the project it was saved with and restores it, so this is a real
        // change to what the Space would come back as.
        val state = splitWindow()
        val saved = savedFrom(extract(state))

        val elsewhere =
            extractCurrentWorkspace(
                state,
                projectPath = "/tmp/other-project",
                defaultWorkingDirectory = "/tmp/dirty-default",
            )
        assertTrue(isUnsaved(elsewhere, saved))
    }

    // ==================== the affordance's own rule ====================

    @Test
    fun `pinning a tab makes the Space unsaved`() {
        val state = splitWindow()
        val saved = savedFrom(extract(state))

        state.getPanel("main")!!.tabsComponent.setPinnedCount(1)

        assertTrue(isUnsaved(extract(state), saved), "pinnedCount is persisted, so it is dirt")
    }

    private companion object {
        const val PROJECT = "/tmp/dirty-proj"

        /**
         * Long enough that the two extracts read different milliseconds.
         *
         * Without it the identity test can pass for the wrong reason - two calls inside one
         * millisecond mint the SAME `workspace-<millis>` id - and would then be pinning nothing.
         */
        const val CLOCK_TICK = 5L
    }
}
