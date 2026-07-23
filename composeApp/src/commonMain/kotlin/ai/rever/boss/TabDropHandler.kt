package ai.rever.boss

import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.window_panel.SplitViewState

/**
 * Handle the result of a tab drop operation.
 * Includes bounds checking to handle cases where tab list may have changed during drag.
 * Internal (not private) so the drop-path branching is unit-testable.
 */
internal fun handleTabDropResult(result: TabDropResult, splitViewState: SplitViewState) {
    when (result) {
        is TabDropResult.Reorder -> {
            // Reorder within the same panel
            val panel = splitViewState.getPanel(result.panelId)
            val tabCount = panel?.tabsComponent?.tabsState?.value?.tabs?.size ?: 0
            // Validate indices are within bounds before reordering
            if (result.fromIndex in 0 until tabCount && result.toIndex in 0..tabCount) {
                panel?.tabsComponent?.moveTab(result.fromIndex, result.toIndex)
            }
        }
        is TabDropResult.MoveToPanel -> {
            // Move tab from source panel to target panel
            val sourcePanel = splitViewState.getPanel(result.sourcePanelId)
            val targetPanel = splitViewState.getPanel(result.targetPanelId)

            if (sourcePanel != null && targetPanel != null) {
                fun activate(newIndex: Int) {
                    if (newIndex >= 0) {
                        targetPanel.tabsComponent.selectTab(newIndex)
                    }
                    splitViewState.setActivePanel(result.targetPanelId)
                }

                // Transfer the live component instance: the tab keeps running across the
                // move (a browser tab keeps its page and playing media) instead of being
                // destroyed in one panel and recreated-from-config in the other.
                val detached = sourcePanel.tabsComponent.detachTab(result.tabInfo.id)
                if (detached != null) {
                    activate(targetPanel.tabsComponent.adoptTab(detached))
                } else if (sourcePanel.tabsComponent.removeTabById(result.tabInfo.id)) {
                    // Component missing but the tab entry survived: recreate-from-config in
                    // the target after cleaning up the source. When the tab is gone entirely
                    // (closed mid-drag) the move is dropped — recreating from the stale
                    // drag-start snapshot would resurrect a closed tab.
                    activate(targetPanel.tabsComponent.addTab(result.tabInfo))
                }
            }
        }
        is TabDropResult.CreateSplit -> {
            // Cross-panel edge drag is a MOVE: detach the live component from the source so
            // the new split adopts it as-is (no reload, no leaked instance). Same-panel edge
            // drops keep their existing copy semantics (handled by tabToMove below).
            val crossPanel = result.sourcePanelId != result.targetPanelId
            val detached = if (crossPanel) {
                splitViewState.getPanel(result.sourcePanelId)
                    ?.tabsComponent?.detachTab(result.tabInfo.id)
            } else {
                null
            }
            // Cross-panel detach failed: recreate-from-config only if the tab entry still
            // exists in the source (component missing) — removing it first, mirroring
            // MoveToPanel. A tab closed mid-drag drops the move instead of resurrecting.
            val recreateTab = if (crossPanel && detached == null) {
                val stillInSource = splitViewState.getPanel(result.sourcePanelId)
                    ?.tabsComponent?.removeTabById(result.tabInfo.id) == true
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
                    detachedTab = detached
                )
            }
        }
    }
}
