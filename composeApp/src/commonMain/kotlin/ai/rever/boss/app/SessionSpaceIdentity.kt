package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.extractRunningWorkspaces
import ai.rever.boss.components.workspaces.sessionSetOf
import ai.rever.boss.components.workspaces.workspaceManager

/** Recovery snapshots remain session records, never imported as saved Spaces. */
internal fun prepareSessionSpace(workspace: LayoutWorkspace): LayoutWorkspace =
    ai.rever.boss.components.workspaces.recoverIntoDefaultSpace(
        workspace,
        workspaceManager.savedCopyOf(ai.rever.boss.components.workspaces.DefaultSpace.ID)
            ?: ai.rever.boss.components.workspaces.DefaultSpace.planetBerul,
    )

/** A new window must not adopt the hidden recovery slot as its visible identity. */
internal fun updateSessionSpace(
    current: LayoutWorkspace,
    splitViewState: SplitViewState,
) {
    val bound =
        splitViewState.currentWorkspaceId
            ?.takeIf { it != LAST_SESSION_ID }
            ?.let(workspaceManager::savedCopyOf)
    val space =
        if (bound != null && current.id == LAST_SESSION_ID) {
            current.copy(id = bound.id, name = bound.name)
        } else {
            prepareSessionSpace(current)
        }
    if (splitViewState.currentWorkspaceId == null || splitViewState.currentWorkspaceId == LAST_SESSION_ID) {
        splitViewState.rebindCurrentWorkspace(space.id)
    }
    workspaceManager.updateCurrentWorkspace(space)
}

/** Keep the identity-preserving set as fresh as the legacy crash-recovery snapshot. */
internal suspend fun saveSessionRecovery(
    windowId: String,
    record: LayoutWorkspace,
    splitViewState: SplitViewState,
) {
    writeInSessionRecovery(windowId, record, set = {
        val spaces =
            extractRunningWorkspaces(splitViewState, record.projectPath.orEmpty(), identityFor = { id ->
                workspaceManager.currentWorkspace.value?.takeIf { it.id == id } ?: workspaceManager.savedCopyOf(id)
            })
        sessionSetOf(spaces, splitViewState.currentWorkspaceId)
    })
}
