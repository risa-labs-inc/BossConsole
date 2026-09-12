package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import kotlinx.coroutines.flow.StateFlow

/**
 * Implementation of WorkspaceDataProvider that wraps WorkspaceManager.
 * This allows plugins to interact with workspaces without direct coupling.
 */
class WorkspaceDataProviderImpl(
    private val workspaceManager: WorkspaceManager,
) : WorkspaceDataProvider {
    override val workspaces: StateFlow<List<LayoutWorkspace>>
        get() = workspaceManager.workspaces

    override val currentWorkspace: StateFlow<LayoutWorkspace?>
        get() = workspaceManager.currentWorkspace

    override fun loadWorkspace(workspace: LayoutWorkspace) {
        workspaceManager.loadWorkspace(workspace)
    }

    override fun updateCurrentWorkspace(newWorkspace: LayoutWorkspace) {
        workspaceManager.updateCurrentWorkspace(newWorkspace)
    }

    override fun saveCurrentWorkspace(name: String?): LayoutWorkspace? = workspaceManager.saveCurrentWorkspace(name)

    override fun exportWorkspace(workspace: LayoutWorkspace): String = workspaceManager.exportWorkspace(workspace)

    // Both of these pass the name straight through, and the lookup-then-pass-the-id shape they
    // replaced was a silent no-op. WorkspaceManager.deleteWorkspace and renameWorkspace both match
    // on NAME (`_workspaces.value.find { it.name == ... }`), so handing them an id matched nothing
    // and returned normally - the caller was told nothing was wrong and the workspace was still
    // there. The sibling adapter in services/bookmarks/ always passed the name; this copy did not.
    override fun deleteWorkspace(name: String) {
        workspaceManager.deleteWorkspace(name)
    }

    override fun renameWorkspace(
        oldName: String,
        newName: String,
    ) {
        workspaceManager.renameWorkspace(oldName, newName)
    }
}
