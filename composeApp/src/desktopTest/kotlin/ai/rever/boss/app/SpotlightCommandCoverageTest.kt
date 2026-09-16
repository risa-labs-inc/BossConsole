package ai.rever.boss.app

import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.search.GlobalSearchService
import ai.rever.boss.search.SPOTLIGHT_UNSUPPORTED_COMMAND_IDS
import ai.rever.boss.search.SearchResult
import ai.rever.boss.window.ClosedTabHistory
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * BossConsole#700: [KeymapActions.getAllActionIds] and [BossAppDialogs]'s `onCommandSelect`
 * `when` used to disagree silently - 26 of the 48 advertised commands fell through a bare
 * `else -> {}` with no signal to the user or a test. This pins that every catalog id is
 * classified as exactly one of: already handled by `onCommandSelect`'s own pre-existing
 * branches (mirrored below, unchanged by this fix), dispatched by
 * [dispatchSpotlightTabBrowserCommand], or named in [SPOTLIGHT_UNSUPPORTED_COMMAND_IDS]. A
 * future catalog addition nobody classifies fails this test instead of silently doing nothing
 * when a user selects it.
 */
class SpotlightCommandCoverageTest {
    /** Mirrors the ids `onCommandSelect`'s own `when` branches already handled before this fix. */
    private val preExistingHandledIds =
        setOf(
            KeymapActions.WINDOW_NEW,
            KeymapActions.WINDOW_CLOSE,
            KeymapActions.TAB_NEW,
            KeymapActions.TAB_CLOSE,
            KeymapActions.BROWSER_RELOAD,
            KeymapActions.BROWSER_ZOOM_RESET,
            KeymapActions.BROWSER_ZOOM_IN,
            KeymapActions.BROWSER_ZOOM_OUT,
            KeymapActions.PANEL_NAVIGATE_LEFT,
            KeymapActions.PANEL_NAVIGATE_RIGHT,
            KeymapActions.PANEL_NAVIGATE_UP,
            KeymapActions.PANEL_NAVIGATE_DOWN,
            KeymapActions.PANEL_SPLIT_VERTICAL,
            KeymapActions.PANEL_SPLIT_HORIZONTAL,
            KeymapActions.QUICK_SWITCHER_OPEN,
            KeymapActions.WORKSPACE_SAVE,
            KeymapActions.CODEBASE_OPEN,
            KeymapActions.GLOBAL_SEARCH_OPEN,
            KeymapActions.FOCUS_MODE_TOGGLE,
            KeymapActions.CHROME_DENSITY_CYCLE,
            KeymapActions.SETTINGS_OPEN,
            KeymapActions.HELP_SHORTCUTS,
        )

    /** The 18 ids [dispatchSpotlightTabBrowserCommand] recognizes. */
    private val tabBrowserCommandIds =
        setOf(
            KeymapActions.TAB_NEXT,
            KeymapActions.TAB_PREVIOUS,
            KeymapActions.TAB_REOPEN_CLOSED,
            KeymapActions.TAB_NEXT_POSITIONAL,
            KeymapActions.TAB_PREVIOUS_POSITIONAL,
            KeymapActions.TAB_SELECT_1,
            KeymapActions.TAB_SELECT_2,
            KeymapActions.TAB_SELECT_3,
            KeymapActions.TAB_SELECT_4,
            KeymapActions.TAB_SELECT_5,
            KeymapActions.TAB_SELECT_6,
            KeymapActions.TAB_SELECT_7,
            KeymapActions.TAB_SELECT_8,
            KeymapActions.TAB_SELECT_LAST,
            KeymapActions.BROWSER_FIND,
            KeymapActions.BROWSER_BACK,
            KeymapActions.BROWSER_FORWARD,
            KeymapActions.BROWSER_DEVTOOLS,
        )

    private data class FakeTab(
        override val id: String,
        override val title: String = id,
    ) : TabInfo {
        override val typeId = TabTypeId("test", "test")
        override val icon: ImageVector = Icons.Default.Add
        override val tabIcon: TabIcon? = null
    }

    private var collectorJob: Job? = null

    @AfterTest
    fun cleanup() {
        collectorJob?.cancel()
        collectorJob = null
    }

    @Test
    fun `every catalog id is classified as handled, dispatched, or explicitly unsupported`() {
        val catalog = KeymapActions.getAllActionIds().toSet()
        val classified = preExistingHandledIds + tabBrowserCommandIds + SPOTLIGHT_UNSUPPORTED_COMMAND_IDS

        assertEquals(
            catalog,
            classified,
            "catalog and dispatch classification disagree - " +
                "missing from classification: ${catalog - classified}, " +
                "classified but not in the catalog: ${classified - catalog}",
        )
    }

    @Test
    fun `the three classifications do not overlap`() {
        assertTrue(
            (preExistingHandledIds intersect tabBrowserCommandIds).isEmpty(),
            "an id is claimed by both the pre-existing handlers and the new tab-browser dispatch",
        )
        assertTrue(
            (preExistingHandledIds intersect SPOTLIGHT_UNSUPPORTED_COMMAND_IDS).isEmpty(),
            "an id is claimed by both the pre-existing handlers and the unsupported set",
        )
        assertTrue(
            (tabBrowserCommandIds intersect SPOTLIGHT_UNSUPPORTED_COMMAND_IDS).isEmpty(),
            "an id is claimed by both the new tab-browser dispatch and the unsupported set",
        )
    }

    @Test
    fun `dispatchSpotlightTabBrowserCommand recognizes exactly the tab and browser command ids`() {
        val windowId = "recognition-test-window"
        // Eight tabs open (enough for every TAB_SELECT_1..8 index) and a closed-tab entry, so
        // every recognized id's availability gate passes and the outcome tells "recognized"
        // apart from "unavailable" cleanly.
        MenuActionsHandler.updateActivePanelTabCount(windowId, 8)
        ClosedTabHistory.record(windowId, FakeTab("closed-1"))
        try {
            for (id in tabBrowserCommandIds) {
                assertIs<SpotlightDispatchOutcome.Dispatched>(
                    dispatchSpotlightTabBrowserCommand(id, windowId),
                    "$id should be dispatched",
                )
            }
            for (id in KeymapActions.getAllActionIds().toSet() - tabBrowserCommandIds) {
                assertIs<SpotlightDispatchOutcome.NotRecognized>(
                    dispatchSpotlightTabBrowserCommand(id, windowId),
                    "$id should NOT be recognized here",
                )
            }
        } finally {
            MenuActionsHandler.cleanupWindow(windowId)
        }
    }

    @Test
    fun `tab-stepping commands report Unavailable rather than broadcasting when there is nowhere to go`() {
        val windowId = "unavailable-test-window"
        // Never primed: activePanelTabCount defaults to 0, ClosedTabHistory has no entries.
        try {
            for (
            id in
            listOf(
                KeymapActions.TAB_NEXT,
                KeymapActions.TAB_PREVIOUS,
                KeymapActions.TAB_NEXT_POSITIONAL,
                KeymapActions.TAB_PREVIOUS_POSITIONAL,
                KeymapActions.TAB_SELECT_1,
                KeymapActions.TAB_SELECT_LAST,
                KeymapActions.TAB_REOPEN_CLOSED,
            )
            ) {
                assertIs<SpotlightDispatchOutcome.Unavailable>(
                    dispatchSpotlightTabBrowserCommand(id, windowId),
                    "$id should report Unavailable with no tabs and no closed-tab history",
                )
            }
        } finally {
            MenuActionsHandler.cleanupWindow(windowId)
        }
    }

    @Test
    fun `an out-of-range tab-select index is Unavailable, not silently dispatched`() {
        val windowId = "out-of-range-test-window"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 3)
        try {
            // Indices 0..2 exist; 3..7 (TAB_SELECT_4..TAB_SELECT_8) do not.
            for (id in KeymapActions.TAB_SELECT_BY_INDEX.drop(3)) {
                assertIs<SpotlightDispatchOutcome.Unavailable>(
                    dispatchSpotlightTabBrowserCommand(id, windowId),
                    "$id should be Unavailable with only 3 tabs open",
                )
            }
            for (id in KeymapActions.TAB_SELECT_BY_INDEX.take(3)) {
                assertIs<SpotlightDispatchOutcome.Dispatched>(
                    dispatchSpotlightTabBrowserCommand(id, windowId),
                    "$id should be dispatched with 3 tabs open",
                )
            }
        } finally {
            MenuActionsHandler.cleanupWindow(windowId)
        }
    }

    @Test
    fun `TAB_NEXT and TAB_PREVIOUS commit their MRU cycle immediately`() {
        // Spotlight has no modifier-release gesture to commit an MRU cycle on (unlike Ctrl+Tab),
        // so each step must be followed by an explicit commit in the same call. Dispatchers.
        // Unconfined runs the collector synchronously up to its first suspension point before
        // `launch` returns, so it is guaranteed attached before the dispatch below emits -
        // the same pattern ShortcutKeyUpSemanticsTest uses for this exact flow.
        val windowId = "commit-test-window"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 2)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val events = mutableListOf<Pair<String, MenuActionsHandler.TabSwitchAction>>()
        collectorJob = scope.launch { MenuActionsHandler.tabSwitchEvents.collect { events.add(it) } }

        try {
            dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_NEXT, windowId)
            dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_PREVIOUS, windowId)

            assertEquals(
                listOf(
                    windowId to MenuActionsHandler.TabSwitchAction.NEXT,
                    windowId to MenuActionsHandler.TabSwitchAction.COMMIT,
                    windowId to MenuActionsHandler.TabSwitchAction.PREVIOUS,
                    windowId to MenuActionsHandler.TabSwitchAction.COMMIT,
                ),
                events,
            )
        } finally {
            scope.cancel()
            MenuActionsHandler.cleanupWindow(windowId)
        }
    }

    @Test
    fun `positional tab stepping emits no commit`() {
        val windowId = "positional-test-window"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 2)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val events = mutableListOf<Pair<String, MenuActionsHandler.TabSwitchAction>>()
        collectorJob = scope.launch { MenuActionsHandler.tabSwitchEvents.collect { events.add(it) } }

        try {
            dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_NEXT_POSITIONAL, windowId)
            dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_PREVIOUS_POSITIONAL, windowId)

            assertEquals(
                listOf(
                    windowId to MenuActionsHandler.TabSwitchAction.NEXT_POSITIONAL,
                    windowId to MenuActionsHandler.TabSwitchAction.PREVIOUS_POSITIONAL,
                ),
                events,
            )
        } finally {
            scope.cancel()
            MenuActionsHandler.cleanupWindow(windowId)
        }
    }

    @Test
    fun `select-tab commands use zero-based indices, for every one of the eight positions`() {
        // Widened from sampling TAB_SELECT_1/8/LAST: a hand-typed index for one of the middle
        // six (e.g. TAB_SELECT_5 wired to index 3) would previously have passed CI with only
        // the two ends asserted.
        val windowId = "select-test-window"
        MenuActionsHandler.updateActivePanelTabCount(windowId, 8)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val events = mutableListOf<Pair<String, Int>>()
        collectorJob = scope.launch { MenuActionsHandler.selectTabIndexEvents.collect { events.add(it) } }

        try {
            KeymapActions.TAB_SELECT_BY_INDEX.forEach { id ->
                dispatchSpotlightTabBrowserCommand(id, windowId)
            }
            dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_SELECT_LAST, windowId)

            assertEquals(
                KeymapActions.TAB_SELECT_BY_INDEX.indices.map { windowId to it } +
                    (windowId to MenuActionsHandler.LAST_TAB_INDEX),
                events,
            )
        } finally {
            scope.cancel()
            MenuActionsHandler.cleanupWindow(windowId)
        }
    }

    @Test
    fun `GlobalSearchService excludes the unsupported ids from its results entirely`() =
        runBlocking {
            // BossConsole#700's acceptance criteria: an unsupported command must not be offered as
            // selectable at all, not merely report itself unavailable after being picked.
            for (id in SPOTLIGHT_UNSUPPORTED_COMMAND_IDS) {
                val description = KeymapActions.getDescription(id)
                val hits = GlobalSearchService.search(description, "search-exclusion-test-window", emptyList())
                assertTrue(
                    hits.none { it is SearchResult.CommandResult && it.actionId == id },
                    "$id ($description) was offered by search despite being unsupported",
                )
            }
        }
}
