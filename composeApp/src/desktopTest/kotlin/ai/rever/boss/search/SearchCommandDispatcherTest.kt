package ai.rever.boss.search

import ai.rever.boss.keymap.model.KeymapActions
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SearchCommandDispatcherTest {
    private val windowId = "window-under-test"
    private val availableTargets = SearchCommandTargets(8, hasClosedTabs = true, hasActiveBrowser = true)

    private fun invocation(
        action: SearchCommandInvocation.Action,
        index: Int? = null,
        targetWindowId: String? = windowId,
    ) = SearchCommandInvocation(action, targetWindowId, index)

    private val expectedDispatches =
        mapOf(
            KeymapActions.WINDOW_NEW to
                listOf(invocation(SearchCommandInvocation.Action.WINDOW_NEW, targetWindowId = null)),
            KeymapActions.WINDOW_CLOSE to listOf(invocation(SearchCommandInvocation.Action.WINDOW_CLOSE)),
            KeymapActions.TAB_NEW to listOf(invocation(SearchCommandInvocation.Action.TAB_NEW)),
            KeymapActions.TAB_CLOSE to listOf(invocation(SearchCommandInvocation.Action.TAB_CLOSE)),
            KeymapActions.TAB_NEXT to
                listOf(
                    invocation(SearchCommandInvocation.Action.TAB_NEXT),
                    invocation(SearchCommandInvocation.Action.TAB_CYCLE_COMMIT),
                ),
            KeymapActions.TAB_PREVIOUS to
                listOf(
                    invocation(SearchCommandInvocation.Action.TAB_PREVIOUS),
                    invocation(SearchCommandInvocation.Action.TAB_CYCLE_COMMIT),
                ),
            KeymapActions.TAB_REOPEN_CLOSED to listOf(invocation(SearchCommandInvocation.Action.TAB_REOPEN_CLOSED)),
            KeymapActions.TAB_NEXT_POSITIONAL to listOf(invocation(SearchCommandInvocation.Action.TAB_NEXT_POSITIONAL)),
            KeymapActions.TAB_PREVIOUS_POSITIONAL to
                listOf(invocation(SearchCommandInvocation.Action.TAB_PREVIOUS_POSITIONAL)),
            KeymapActions.TAB_SELECT_1 to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, 0)),
            KeymapActions.TAB_SELECT_2 to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, 1)),
            KeymapActions.TAB_SELECT_3 to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, 2)),
            KeymapActions.TAB_SELECT_4 to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, 3)),
            KeymapActions.TAB_SELECT_5 to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, 4)),
            KeymapActions.TAB_SELECT_6 to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, 5)),
            KeymapActions.TAB_SELECT_7 to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, 6)),
            KeymapActions.TAB_SELECT_8 to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, 7)),
            KeymapActions.TAB_SELECT_LAST to listOf(invocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, -1)),
            KeymapActions.BROWSER_RELOAD to listOf(invocation(SearchCommandInvocation.Action.BROWSER_RELOAD)),
            KeymapActions.BROWSER_ZOOM_RESET to listOf(invocation(SearchCommandInvocation.Action.BROWSER_ZOOM_RESET)),
            KeymapActions.BROWSER_ZOOM_IN to listOf(invocation(SearchCommandInvocation.Action.BROWSER_ZOOM_IN)),
            KeymapActions.BROWSER_ZOOM_OUT to listOf(invocation(SearchCommandInvocation.Action.BROWSER_ZOOM_OUT)),
            KeymapActions.BROWSER_FIND to listOf(invocation(SearchCommandInvocation.Action.BROWSER_FIND)),
            KeymapActions.BROWSER_BACK to listOf(invocation(SearchCommandInvocation.Action.BROWSER_BACK)),
            KeymapActions.BROWSER_FORWARD to listOf(invocation(SearchCommandInvocation.Action.BROWSER_FORWARD)),
            KeymapActions.BROWSER_DEVTOOLS to listOf(invocation(SearchCommandInvocation.Action.BROWSER_DEVTOOLS)),
            KeymapActions.PANEL_NAVIGATE_LEFT to listOf(invocation(SearchCommandInvocation.Action.PANEL_NAVIGATE_LEFT)),
            KeymapActions.PANEL_NAVIGATE_RIGHT to
                listOf(invocation(SearchCommandInvocation.Action.PANEL_NAVIGATE_RIGHT)),
            KeymapActions.PANEL_NAVIGATE_UP to listOf(invocation(SearchCommandInvocation.Action.PANEL_NAVIGATE_UP)),
            KeymapActions.PANEL_NAVIGATE_DOWN to listOf(invocation(SearchCommandInvocation.Action.PANEL_NAVIGATE_DOWN)),
            KeymapActions.PANEL_SPLIT_VERTICAL to
                listOf(invocation(SearchCommandInvocation.Action.PANEL_SPLIT_VERTICAL)),
            KeymapActions.PANEL_SPLIT_HORIZONTAL to
                listOf(invocation(SearchCommandInvocation.Action.PANEL_SPLIT_HORIZONTAL)),
            KeymapActions.QUICK_SWITCHER_OPEN to listOf(invocation(SearchCommandInvocation.Action.QUICK_SWITCHER_OPEN)),
            KeymapActions.WORKSPACE_SAVE to listOf(invocation(SearchCommandInvocation.Action.WORKSPACE_SAVE)),
            KeymapActions.CODEBASE_OPEN to listOf(invocation(SearchCommandInvocation.Action.CODEBASE_OPEN)),
            KeymapActions.GLOBAL_SEARCH_OPEN to listOf(invocation(SearchCommandInvocation.Action.GLOBAL_SEARCH_OPEN)),
            KeymapActions.FOCUS_MODE_TOGGLE to listOf(invocation(SearchCommandInvocation.Action.FOCUS_MODE_TOGGLE)),
            KeymapActions.SETTINGS_OPEN to listOf(invocation(SearchCommandInvocation.Action.SETTINGS_OPEN)),
            KeymapActions.CHROME_DENSITY_CYCLE to
                listOf(invocation(SearchCommandInvocation.Action.CHROME_DENSITY_CYCLE)),
            KeymapActions.HELP_SHORTCUTS to listOf(invocation(SearchCommandInvocation.Action.HELP_SHORTCUTS)),
        )

    private val unsupported =
        setOf(
            KeymapActions.EDITOR_SAVE,
            KeymapActions.EDITOR_SAVE_ALL,
            KeymapActions.EDITOR_FIND,
            KeymapActions.EDITOR_REPLACE,
            KeymapActions.EDITOR_FIND_NEXT,
            KeymapActions.EDITOR_FIND_PREVIOUS,
            KeymapActions.EDITOR_GO_TO_LINE,
            KeymapActions.TEST_EXTERNAL_LINK,
        )

    @Test
    fun `all registered actions are exactly forty dispatchable and eight explicitly unsupported`() {
        assertEquals(48, KeymapActions.getAllActionIds().size)
        assertEquals(KeymapActions.getAllActionIds().toSet(), SearchCommandCatalog.classifications.keys)
        assertEquals(expectedDispatches.keys, SearchCommandCatalog.supportedActionIds)
        assertEquals(unsupported, SearchCommandCatalog.unsupportedActionIds)
        assertEquals(40, SearchCommandCatalog.supportedActionIds.size)
        assertEquals(8, SearchCommandCatalog.unsupportedActionIds.size)
        unsupported.forEach { actionId ->
            val classification =
                assertIs<SearchCommandClassification.Unsupported>(
                    SearchCommandCatalog.classifications.getValue(actionId),
                )
            assertTrue(classification.reason.isNotBlank(), "$actionId must explain why it is unavailable")
        }
    }

    @Test
    fun `production dispatcher emits exact ordered invocations for every supported action`() {
        expectedDispatches.forEach { (actionId, expected) ->
            val actual = mutableListOf<SearchCommandInvocation>()
            val outcome = SearchCommandDispatcher.dispatch(actionId, windowId, availableTargets, actual::add)

            assertEquals(SearchCommandDispatchOutcome.Dispatched, outcome, actionId)
            assertEquals(expected, actual, actionId)
        }
    }

    @Test
    fun `unsupported and stale ids are explicit and never invoke a target`() {
        (unsupported + "stale.command").forEach { actionId ->
            val actual = mutableListOf<SearchCommandInvocation>()
            val outcome = SearchCommandDispatcher.dispatch(actionId, windowId, availableTargets, actual::add)

            assertIs<SearchCommandDispatchOutcome.Rejected>(outcome, actionId)
            assertTrue(outcome.reason.isNotBlank(), actionId)
            assertTrue(actual.isEmpty(), actionId)
        }
    }

    @Test
    fun `search advertises supported commands and omits unsupported commands`() =
        runBlocking {
            val browserFind =
                GlobalSearchService
                    .search(KeymapActions.BROWSER_FIND, windowId, emptyList())
                    .filterIsInstance<SearchResult.CommandResult>()
                    .singleOrNull { it.actionId == KeymapActions.BROWSER_FIND }
            val editorSave =
                GlobalSearchService
                    .search(KeymapActions.EDITOR_SAVE, windowId, emptyList())
                    .filterIsInstance<SearchResult.CommandResult>()
                    .singleOrNull { it.actionId == KeymapActions.EDITOR_SAVE }
            val externalLink =
                GlobalSearchService
                    .search(KeymapActions.TEST_EXTERNAL_LINK, windowId, emptyList())
                    .filterIsInstance<SearchResult.CommandResult>()
                    .singleOrNull { it.actionId == KeymapActions.TEST_EXTERNAL_LINK }

            assertNotNull(browserFind)
            assertEquals(null, editorSave)
            assertEquals(null, externalLink)
        }
}
