package ai.rever.boss.search

/**
 * Target availability read from the invoking window at selection, not when search results were built.
 * This gates dispatch; asynchronous collectors still own execution if a target later disappears.
 */
data class SearchCommandTargets(
    val activePanelTabCount: Int,
    val hasClosedTabs: Boolean,
    val hasActiveBrowser: Boolean,
) {
    internal fun unavailableReason(invocation: SearchCommandInvocation): String? =
        when (invocation.action) {
            SearchCommandInvocation.Action.TAB_NEXT,
            SearchCommandInvocation.Action.TAB_PREVIOUS,
            SearchCommandInvocation.Action.TAB_NEXT_POSITIONAL,
            SearchCommandInvocation.Action.TAB_PREVIOUS_POSITIONAL,
            -> {
                if (activePanelTabCount < 2) "Open another tab in this pane to switch tabs." else null
            }

            SearchCommandInvocation.Action.TAB_CLOSE,
            SearchCommandInvocation.Action.PANEL_SPLIT_VERTICAL,
            SearchCommandInvocation.Action.PANEL_SPLIT_HORIZONTAL,
            -> {
                if (activePanelTabCount == 0) "There are no tabs in this pane." else null
            }

            SearchCommandInvocation.Action.TAB_SELECT_INDEX -> {
                tabSelectionRejection(invocation.index)
            }

            SearchCommandInvocation.Action.TAB_REOPEN_CLOSED -> {
                if (!hasClosedTabs) "There are no closed tabs to reopen in this window." else null
            }

            SearchCommandInvocation.Action.BROWSER_RELOAD,
            SearchCommandInvocation.Action.BROWSER_ZOOM_RESET,
            SearchCommandInvocation.Action.BROWSER_ZOOM_IN,
            SearchCommandInvocation.Action.BROWSER_ZOOM_OUT,
            SearchCommandInvocation.Action.BROWSER_FIND,
            SearchCommandInvocation.Action.BROWSER_BACK,
            SearchCommandInvocation.Action.BROWSER_FORWARD,
            SearchCommandInvocation.Action.BROWSER_DEVTOOLS,
            -> {
                if (!hasActiveBrowser) "Select a browser tab in this pane first." else null
            }

            else -> {
                null
            }
        }

    private fun tabSelectionRejection(index: Int?): String? =
        when {
            activePanelTabCount == 0 -> "There are no tabs in this pane."
            index == -1 -> null
            index == null || index !in 0 until activePanelTabCount -> "That tab is not available in this pane."
            else -> null
        }
}
