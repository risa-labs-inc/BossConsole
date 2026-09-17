package ai.rever.boss.search

import ai.rever.boss.keymap.model.KeymapActions

/** A typed operation emitted by Spotlight's command dispatcher. */
data class SearchCommandInvocation(
    val action: Action,
    val windowId: String?,
    val index: Int? = null,
) {
    enum class Action {
        WINDOW_NEW,
        WINDOW_CLOSE,
        TAB_NEW,
        TAB_CLOSE,
        TAB_NEXT,
        TAB_PREVIOUS,
        TAB_CYCLE_COMMIT,
        TAB_REOPEN_CLOSED,
        TAB_NEXT_POSITIONAL,
        TAB_PREVIOUS_POSITIONAL,
        TAB_SELECT_INDEX,
        BROWSER_RELOAD,
        BROWSER_ZOOM_RESET,
        BROWSER_ZOOM_IN,
        BROWSER_ZOOM_OUT,
        BROWSER_FIND,
        BROWSER_BACK,
        BROWSER_FORWARD,
        BROWSER_DEVTOOLS,
        PANEL_NAVIGATE_LEFT,
        PANEL_NAVIGATE_RIGHT,
        PANEL_NAVIGATE_UP,
        PANEL_NAVIGATE_DOWN,
        PANEL_SPLIT_VERTICAL,
        PANEL_SPLIT_HORIZONTAL,
        QUICK_SWITCHER_OPEN,
        WORKSPACE_SAVE,
        CODEBASE_OPEN,
        GLOBAL_SEARCH_OPEN,
        FOCUS_MODE_TOGGLE,
        SETTINGS_OPEN,
        CHROME_DENSITY_CYCLE,
        HELP_SHORTCUTS,
    }
}

sealed interface SearchCommandClassification {
    @ConsistentCopyVisibility
    data class Supported internal constructor(
        internal val invocations: (String) -> List<SearchCommandInvocation>,
    ) : SearchCommandClassification

    data class Unsupported(
        val reason: String,
    ) : SearchCommandClassification
}

/**
 * Single source of truth for commands that Spotlight can actually execute.
 *
 * Editor actions stay in the keymap registry for shortcut configuration, but are not advertised by
 * Spotlight until the editor plugin exposes a host invocation capability.
 */
object SearchCommandCatalog {
    private fun windowAction(action: SearchCommandInvocation.Action) =
        SearchCommandClassification.Supported { windowId -> listOf(SearchCommandInvocation(action, windowId)) }

    private fun selectTab(index: Int) =
        SearchCommandClassification.Supported { windowId ->
            listOf(SearchCommandInvocation(SearchCommandInvocation.Action.TAB_SELECT_INDEX, windowId, index))
        }

    val classifications: Map<String, SearchCommandClassification> =
        linkedMapOf(
            KeymapActions.WINDOW_NEW to
                SearchCommandClassification.Supported {
                    listOf(SearchCommandInvocation(SearchCommandInvocation.Action.WINDOW_NEW, null))
                },
            KeymapActions.WINDOW_CLOSE to windowAction(SearchCommandInvocation.Action.WINDOW_CLOSE),
            KeymapActions.TAB_NEW to windowAction(SearchCommandInvocation.Action.TAB_NEW),
            KeymapActions.TAB_CLOSE to windowAction(SearchCommandInvocation.Action.TAB_CLOSE),
            KeymapActions.TAB_NEXT to
                SearchCommandClassification.Supported { windowId ->
                    listOf(
                        SearchCommandInvocation(SearchCommandInvocation.Action.TAB_NEXT, windowId),
                        SearchCommandInvocation(SearchCommandInvocation.Action.TAB_CYCLE_COMMIT, windowId),
                    )
                },
            KeymapActions.TAB_PREVIOUS to
                SearchCommandClassification.Supported { windowId ->
                    listOf(
                        SearchCommandInvocation(SearchCommandInvocation.Action.TAB_PREVIOUS, windowId),
                        SearchCommandInvocation(SearchCommandInvocation.Action.TAB_CYCLE_COMMIT, windowId),
                    )
                },
            KeymapActions.TAB_REOPEN_CLOSED to windowAction(SearchCommandInvocation.Action.TAB_REOPEN_CLOSED),
            KeymapActions.TAB_NEXT_POSITIONAL to windowAction(SearchCommandInvocation.Action.TAB_NEXT_POSITIONAL),
            KeymapActions.TAB_PREVIOUS_POSITIONAL to
                windowAction(SearchCommandInvocation.Action.TAB_PREVIOUS_POSITIONAL),
            KeymapActions.TAB_SELECT_1 to selectTab(0),
            KeymapActions.TAB_SELECT_2 to selectTab(1),
            KeymapActions.TAB_SELECT_3 to selectTab(2),
            KeymapActions.TAB_SELECT_4 to selectTab(3),
            KeymapActions.TAB_SELECT_5 to selectTab(4),
            KeymapActions.TAB_SELECT_6 to selectTab(5),
            KeymapActions.TAB_SELECT_7 to selectTab(6),
            KeymapActions.TAB_SELECT_8 to selectTab(7),
            KeymapActions.TAB_SELECT_LAST to selectTab(-1),
            KeymapActions.BROWSER_RELOAD to windowAction(SearchCommandInvocation.Action.BROWSER_RELOAD),
            KeymapActions.BROWSER_ZOOM_RESET to windowAction(SearchCommandInvocation.Action.BROWSER_ZOOM_RESET),
            KeymapActions.BROWSER_ZOOM_IN to windowAction(SearchCommandInvocation.Action.BROWSER_ZOOM_IN),
            KeymapActions.BROWSER_ZOOM_OUT to windowAction(SearchCommandInvocation.Action.BROWSER_ZOOM_OUT),
            KeymapActions.BROWSER_FIND to windowAction(SearchCommandInvocation.Action.BROWSER_FIND),
            KeymapActions.BROWSER_BACK to windowAction(SearchCommandInvocation.Action.BROWSER_BACK),
            KeymapActions.BROWSER_FORWARD to windowAction(SearchCommandInvocation.Action.BROWSER_FORWARD),
            KeymapActions.BROWSER_DEVTOOLS to windowAction(SearchCommandInvocation.Action.BROWSER_DEVTOOLS),
            KeymapActions.PANEL_NAVIGATE_LEFT to windowAction(SearchCommandInvocation.Action.PANEL_NAVIGATE_LEFT),
            KeymapActions.PANEL_NAVIGATE_RIGHT to windowAction(SearchCommandInvocation.Action.PANEL_NAVIGATE_RIGHT),
            KeymapActions.PANEL_NAVIGATE_UP to windowAction(SearchCommandInvocation.Action.PANEL_NAVIGATE_UP),
            KeymapActions.PANEL_NAVIGATE_DOWN to windowAction(SearchCommandInvocation.Action.PANEL_NAVIGATE_DOWN),
            KeymapActions.PANEL_SPLIT_VERTICAL to windowAction(SearchCommandInvocation.Action.PANEL_SPLIT_VERTICAL),
            KeymapActions.PANEL_SPLIT_HORIZONTAL to windowAction(SearchCommandInvocation.Action.PANEL_SPLIT_HORIZONTAL),
            KeymapActions.QUICK_SWITCHER_OPEN to windowAction(SearchCommandInvocation.Action.QUICK_SWITCHER_OPEN),
            KeymapActions.WORKSPACE_SAVE to windowAction(SearchCommandInvocation.Action.WORKSPACE_SAVE),
            KeymapActions.CODEBASE_OPEN to windowAction(SearchCommandInvocation.Action.CODEBASE_OPEN),
            KeymapActions.GLOBAL_SEARCH_OPEN to windowAction(SearchCommandInvocation.Action.GLOBAL_SEARCH_OPEN),
            KeymapActions.FOCUS_MODE_TOGGLE to windowAction(SearchCommandInvocation.Action.FOCUS_MODE_TOGGLE),
            KeymapActions.SETTINGS_OPEN to windowAction(SearchCommandInvocation.Action.SETTINGS_OPEN),
            KeymapActions.CHROME_DENSITY_CYCLE to windowAction(SearchCommandInvocation.Action.CHROME_DENSITY_CYCLE),
            KeymapActions.HELP_SHORTCUTS to windowAction(SearchCommandInvocation.Action.HELP_SHORTCUTS),
            KeymapActions.EDITOR_SAVE to unsupportedEditor(),
            KeymapActions.EDITOR_SAVE_ALL to unsupportedEditor(),
            KeymapActions.EDITOR_FIND to unsupportedEditor(),
            KeymapActions.EDITOR_REPLACE to unsupportedEditor(),
            KeymapActions.EDITOR_FIND_NEXT to unsupportedEditor(),
            KeymapActions.EDITOR_FIND_PREVIOUS to unsupportedEditor(),
            KeymapActions.EDITOR_GO_TO_LINE to unsupportedEditor(),
            KeymapActions.TEST_EXTERNAL_LINK to
                SearchCommandClassification.Unsupported("Debug action has no host command route"),
        )

    private fun unsupportedEditor() =
        SearchCommandClassification.Unsupported(
            "Editor plugin does not expose a host command route",
        )

    val supportedActionIds: Set<String>
        get() = classifications.filterValues { it is SearchCommandClassification.Supported }.keys

    val unsupportedActionIds: Set<String>
        get() = classifications.filterValues { it is SearchCommandClassification.Unsupported }.keys
}

sealed interface SearchCommandDispatchOutcome {
    data object Dispatched : SearchCommandDispatchOutcome

    data class Rejected(
        val reason: String,
    ) : SearchCommandDispatchOutcome
}

/** Resolves an action id through [SearchCommandCatalog] and emits only typed, supported operations. */
object SearchCommandDispatcher {
    fun dispatch(
        actionId: String,
        windowId: String,
        targets: SearchCommandTargets,
        invoke: (SearchCommandInvocation) -> Unit,
    ): SearchCommandDispatchOutcome =
        when (val classification = SearchCommandCatalog.classifications[actionId]) {
            is SearchCommandClassification.Supported -> {
                val invocations = classification.invocations(windowId)
                val reason = invocations.firstNotNullOfOrNull(targets::unavailableReason)
                if (reason != null) {
                    SearchCommandDispatchOutcome.Rejected(reason)
                } else {
                    invocations.forEach(invoke)
                    SearchCommandDispatchOutcome.Dispatched
                }
            }

            is SearchCommandClassification.Unsupported -> {
                SearchCommandDispatchOutcome.Rejected(classification.reason)
            }

            null -> {
                SearchCommandDispatchOutcome.Rejected("Unknown command: $actionId")
            }
        }
}
