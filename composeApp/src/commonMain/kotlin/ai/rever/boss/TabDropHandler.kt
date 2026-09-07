package ai.rever.boss

import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.window_panel.SplitViewState

/**
 * Handle the result of a tab drop operation.
 * Includes bounds checking to handle cases where tab list may have changed during drag.
 * Internal (not private) so the drop-path branching is unit-testable.
 */
internal fun handleTabDropResult(
    result: TabDropResult,
    splitViewState: SplitViewState,
) {
    when (result) {
        is TabDropResult.Reorder -> {
            // Reorder within the same panel
            val panel = splitViewState.getPanel(result.panelId)
            val tabCount =
                panel
                    ?.tabsComponent
                    ?.tabsState
                    ?.value
                    ?.tabs
                    ?.size ?: 0
            // Validate indices are within bounds before reordering
            if (result.fromIndex in 0 until tabCount && result.toIndex in 0..tabCount) {
                panel?.tabsComponent?.moveTab(result.fromIndex, result.toIndex)
            }
        }

        is TabDropResult.MoveToPanel -> {
            moveTabToPanel(result, splitViewState)
        }

        is TabDropResult.CreateSplit -> {
            // Cross-panel edge drag is a MOVE: detach the live component from the source so
            // the new split adopts it as-is (no reload, no leaked instance). Same-panel edge
            // drops keep their existing copy semantics (handled by tabToMove below).
            val crossPanel = result.sourcePanelId != result.targetPanelId
            val detached =
                if (crossPanel) {
                    splitViewState
                        .getPanel(result.sourcePanelId)
                        ?.tabsComponent
                        ?.detachTab(result.tabInfo.id)
                } else {
                    null
                }
            // Cross-panel detach failed: recreate-from-config only if the tab entry still
            // exists in the source (component missing) — removing it first, mirroring
            // MoveToPanel. A tab closed mid-drag drops the move instead of resurrecting.
            val recreateTab =
                if (crossPanel && detached == null) {
                    val stillInSource =
                        splitViewState
                            .getPanel(result.sourcePanelId)
                            ?.tabsComponent
                            // A move, as in MoveToPanel above.
                            ?.removeTabById(result.tabInfo.id, recordForReopen = false) == true
                    if (stillInSource) result.tabInfo else null
                } else if (!crossPanel) {
                    result.tabInfo
                } else {
                    null
                }

            // Create a new split with the tab
            if (detached != null || recreateTab != null) {
                splitViewState.splitPanel(
                    panelId = result.targetPanelId,
                    orientation = result.orientation,
                    tabToMove = recreateTab,
                    detachedTab = detached,
                )
            }
        }

        is TabDropResult.Bookmark -> {
            // Handled where the bookmark dialog lives, which is the tab bar of the panel that
            // started the drag - this function moves tabs between panels and has no way to ask
            // which collections to file one under. Named rather than folded into an else, so a
            // new result type has to decide what it does here instead of silently doing nothing.
        }
    }
}

/**
 * Move a tab into another panel of the same window, at the slot the drop named.
 *
 * Its own function rather than a branch of [handleTabDropResult], because it is the only branch
 * with two fallbacks and a landing position to apply and the nesting had outgrown the `when`.
 */
private fun moveTabToPanel(
    result: TabDropResult.MoveToPanel,
    splitViewState: SplitViewState,
) {
    val sourcePanel = splitViewState.getPanel(result.sourcePanelId) ?: return
    val targetPanel = splitViewState.getPanel(result.targetPanelId) ?: return

    fun land(newIndex: Int) {
        if (newIndex >= 0) {
            targetPanel.tabsComponent.selectTab(newIndex)
            // Adopted or added at the END of the destination's list, so the slot the drop named
            // is applied afterwards: there is no adopt-at-index on BossTabsComponent, and one
            // move within the list it just joined is cheaper than adding one - the same shape
            // SplitViewState.moveTabToWorkspace uses for a named pane. Null means the drop named
            // a panel and no position in it (a centre drop, a sidebar promotion), which appends.
            //
            // AFTER selectTab, not before: TabsNavigation.moveTab carries activeIndex along with
            // the tab it moves, so selecting first leaves the arriving tab selected wherever it
            // lands. Selecting after would need the post-move index, which is the thing this call
            // is establishing.
            //
            // This is also what makes pinning follow the insertion line: moveTab goes through
            // pinnedCountAfterMove, so landing above the destination's pinned separator pins the
            // tab exactly as dragging one up there within a panel does.
            result.targetIndex?.let { index ->
                splitViewState.reorderWithinPanel(targetPanel, result.tabInfo.id, index)
            }
        }
        splitViewState.setActivePanel(result.targetPanelId)
    }

    // Transfer the live component instance: the tab keeps running across the move (a browser tab
    // keeps its page and playing media) instead of being destroyed in one panel and
    // recreated-from-config in the other.
    val detached = sourcePanel.tabsComponent.detachTab(result.tabInfo.id)
    if (detached != null) {
        land(targetPanel.tabsComponent.adoptTab(detached))
        // A move, not a close: recordForReopen = false for the same reason adoptTab's stale-holder
        // cleanup passes it. The tab is about to exist again in this window, and recording would
        // silently burn a history slot.
    } else if (sourcePanel.tabsComponent.removeTabById(result.tabInfo.id, recordForReopen = false)) {
        // Component missing but the tab entry survived: recreate-from-config in the target after
        // cleaning up the source. When the tab is gone entirely (closed mid-drag) the move is
        // dropped - recreating from the stale drag-start snapshot would resurrect a closed tab.
        land(targetPanel.tabsComponent.addTab(result.tabInfo))
    }
}
