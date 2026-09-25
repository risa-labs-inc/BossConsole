package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSettings
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.WorkspaceSwitchAction
import ai.rever.boss.components.workspaces.WorkspaceSwitchDialog
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.resolveOnWorkspaceSwitch
import ai.rever.boss.components.workspaces.spaceToOpen
import ai.rever.boss.components.workspaces.workspaceManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Switching this window from one workspace to another.
 *
 * Its own file because a switch is two decisions, not one: what happens to the workspace being
 * left, and whether to ask. Inline in the scaffold that was several branches in a composable
 * that is otherwise a layout.
 */
@Stable
internal class WorkspaceSwitch internal constructor(
    /** Begin a switch. May put the keep-or-close question on screen first. */
    val request: (LayoutWorkspace) -> Unit,
    /** Carry one out, the question having been settled. */
    val resolve: (workspace: LayoutWorkspace, keepLeaving: Boolean) -> Unit,
)

@Composable
@Suppress("CyclomaticComplexMethod") // Keep/close, same-Space reopen, and refusal each preserve distinct ownership.
internal fun rememberWorkspaceSwitch(
    state: BossAppState,
    splitViewState: SplitViewState,
): WorkspaceSwitch {
    val scope = rememberCoroutineScope()
    val settings by WorkspaceSettingsManager.currentSettings.collectAsState()

    val resolve: (LayoutWorkspace, Boolean) -> Unit = { workspace, keepLeaving ->
        scope.launch {
            val leaving = workspaceManager.currentWorkspace.value
            val leavingId = leaving?.id?.takeIf { it.isNotEmpty() }

            // Preserve whatever the answer to keep-or-close was: the leaving tree has to still
            // be in the map if the incoming Space turns out to be unbuildable, because nothing
            // on the refusal path can rebuild it. A close the user asked for happens below,
            // once the apply has actually landed - closing here cleared the live tabs first and
            // a refused apply then left an emptied window, the wipe this ordering exists for.
            if (leavingId != null && (keepLeaving || leavingId != workspace.id)) {
                splitViewState.preserveCurrentState(leavingId, leaving?.name.orEmpty())
            } else if (leavingId != null) {
                // Reopening this Space with discard must rebuild its saved definition.
                splitViewState.discardPreservedState(leavingId)
            }

            // A TEMPLATE picked here becomes a Space first: substituted, named for the project and
            // saved, so what gets loaded and applied is an ordinary Space. Returns `workspace`
            // unchanged for anything that is not a template, and for a template picked with no
            // project selected (which says so and applies as before). See `spaceToOpen`.
            val opened = spaceToOpen(workspace, state.windowProjectState.selectedProject.value.path)

            if (applyWorkspace(opened, splitViewState, state.windowProjectState)) {
                // The apply landed, so the manager can enter the Space (which is also what
                // re-skins the app) - and only then does a close destroy anything.
                workspaceManager.loadWorkspace(opened)
                if (leavingId != null && !keepLeaving && leavingId != opened.id) {
                    splitViewState.closeWorkspace(leavingId)
                    // The unsaved mark goes with the layout it was about. Closing destroys this
                    // window's copy of the Space - preserved state dropped, panels cleared - so
                    // after this there is nothing here that differs from the file, and a mark left
                    // behind would point at work that no longer exists and could never be saved.
                    workspaceManager.setWorkspaceUnsaved(state.windowId, leavingId, false)
                }
            } else {
                // Refused: nothing was built and nothing was destroyed. Put the leaving tree
                // back on screen and drop the snapshot just taken of it - it is the same tree,
                // and a preserved copy of the workspace currently shown would be written into
                // the next session record as a second running Space.
                if (leavingId != null) {
                    splitViewState.restorePreservedState(leavingId)
                    splitViewState.discardPreservedState(leavingId)
                }
                // `spaceToOpen` enters a materialised template itself - the loadWorkspace inside
                // it is how the save lands under the right identity - so a refusal can leave the
                // manager claiming a Space that was never applied. Point it back at what is on
                // screen, which also re-applies that Space's theme over the one just set.
                if (leaving == null) {
                    workspaceManager.resetToDefault()
                } else if (workspaceManager.currentWorkspace.value?.id != leaving.id) {
                    workspaceManager.loadWorkspace(leaving)
                }
            }
        }
    }

    val request: (LayoutWorkspace) -> Unit = { workspace ->
        val leaving = workspaceManager.currentWorkspace.value
        // Nothing to keep or close means nothing to ask about: a first switch, or one back onto
        // the workspace already showing. A dialog whose answer cannot matter is worse than none.
        val hasSomethingToLeave = leaving != null && leaving.id.isNotEmpty() && leaving.id != workspace.id
        val action = settings.resolveOnWorkspaceSwitch()
        when {
            !hasSomethingToLeave -> resolve(workspace, true)
            action == WorkspaceSwitchAction.ASK -> state.pendingWorkspaceSwitch = workspace
            else -> resolve(workspace, action == WorkspaceSwitchAction.KEEP)
        }
    }

    return remember(state, splitViewState, settings) { WorkspaceSwitch(request, resolve) }
}

/** The keep-or-close question, while one is outstanding. */
@Composable
internal fun WorkspaceSwitchPrompt(
    state: BossAppState,
    switch: WorkspaceSwitch,
) {
    val pending = state.pendingWorkspaceSwitch ?: return
    val scope = rememberCoroutineScope()
    val leaving = workspaceManager.currentWorkspace.value
    // Collected, not read once: the watcher can settle while the question is on screen, and the
    // dialog must not still be saying "no unsaved changes" by the time it is answered.
    val unsavedWorkspaces by workspaceManager.unsavedWorkspaces.collectAsState()

    WorkspaceSwitchDialog(
        leavingName = leaving?.name.orEmpty(),
        enteringName = pending.name,
        // The same rule as the vertical bar and the Space menu, so all three agree about the same
        // Space - including on Last Session, which is a slot rather than a document and so always
        // holds work that has never been saved anywhere.
        leavingUnsaved = spaceIsUnsaved(leaving?.id, unsavedWorkspaces[state.windowId].orEmpty()),
        onChoose = { keep, dontAskAgain ->
            state.pendingWorkspaceSwitch = null
            if (dontAskAgain) {
                scope.launch {
                    WorkspaceSettingsManager.setOnWorkspaceSwitch(
                        if (keep) WorkspaceSettings.SWITCH_KEEP else WorkspaceSettings.SWITCH_CLOSE,
                    )
                }
            }
            switch.resolve(pending, keep)
        },
        // Dismissing answers nothing, so it cancels the switch rather than guessing. The two
        // buttons throw away different things, and neither is a safe default for a dialog
        // somebody pressed Escape on.
        onDismiss = { state.pendingWorkspaceSwitch = null },
    )
}
