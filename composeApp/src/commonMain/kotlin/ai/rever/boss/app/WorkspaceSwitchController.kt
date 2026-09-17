package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceApplyHooks
import ai.rever.boss.components.workspaces.applyPreparedWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.isUnsaved
import ai.rever.boss.components.workspaces.spaceToOpen
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.project.DefaultWorkingDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class WorkspaceSwitchController(
    private val state: BossAppState,
    private val split: SplitViewState,
    private val scope: CoroutineScope,
) {
    private val owner = WorkspaceSwitchGeneration()

    fun invalidate() {
        owner.next()
    }

    fun leaving(): LayoutWorkspace? =
        windowSpaceIdentity(
            split.currentWorkspaceId,
            workspaceManager.currentWorkspace.value,
            workspaceManager.workspaces.value,
        )

    fun leavingUnsaved(): Boolean {
        val id = split.currentWorkspaceId ?: return false
        return spaceIsUnsaved(id, workspaceManager.unsavedWorkspaces.value[state.windowId].orEmpty()) ||
            isUnsaved(
                extractCurrentWorkspace(
                    split,
                    state.windowProjectState.selectedProject.value.path,
                    defaultWorkingDirectory = DefaultWorkingDirectory.nominalPath(),
                ),
                workspaceManager.savedCopyOf(id),
            )
    }

    // Preparation crosses filesystem, persistence and plugin boundaries. Report failure here
    // rather than letting an arbitrary provider exception cancel the window's Compose scope.
    @Suppress("TooGenericExceptionCaught")
    fun resolve(
        workspace: LayoutWorkspace,
        keep: Boolean,
        explicitDiscard: Boolean,
    ) {
        val request =
            SwitchRequest(
                owner.next(),
                split.currentWorkspaceId,
                leaving()?.name ?: "this Space",
                keep,
                explicitDiscard,
            )
        scope.launch {
            try {
                val prepared =
                    spaceToOpen(
                        workspace,
                        state.windowProjectState.selectedProject.value.path,
                        publishSelection = false,
                    )
                val opened = if (prepared.id.isEmpty()) prepared.copy(id = LayoutWorkspace.generateId()) else prepared
                applyPreparedWorkspace(
                    opened,
                    split,
                    state.windowProjectState,
                    hooks = WorkspaceApplyHooks(beforeApply = { commit(request, opened) }),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                StatusMessageManager.showMessage("Could not open Space: ${failure.message ?: "Unexpected error"}")
            }
        }
    }

    private fun commit(
        request: SwitchRequest,
        opened: LayoutWorkspace,
    ): Boolean =
        when {
            !owner.isCurrent(request.generation) || split.currentWorkspaceId != request.leavingId -> {
                false
            }

            !request.keep && !request.explicitDiscard && leavingUnsaved() -> {
                // A target requested while clean can finish preparing after another edit.
                state.pendingWorkspaceSwitch = opened
                false
            }

            else -> {
                request.leavingId?.takeIf { it.isNotEmpty() }?.let { id ->
                    if (request.keep) {
                        split.preserveCurrentState(id, request.leavingName)
                    } else {
                        split.closeCurrentWorkspace()
                        workspaceManager.setWorkspaceUnsaved(state.windowId, id, false)
                    }
                }
                workspaceManager.loadWorkspace(opened)
                true
            }
        }

    private data class SwitchRequest(
        val generation: Long,
        val leavingId: String?,
        val leavingName: String,
        val keep: Boolean,
        val explicitDiscard: Boolean,
    )
}
