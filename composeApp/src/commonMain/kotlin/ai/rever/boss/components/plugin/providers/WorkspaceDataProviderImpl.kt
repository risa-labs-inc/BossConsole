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
    private val workspaceManager: WorkspaceManager
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

    override fun saveCurrentWorkspace(name: String?): LayoutWorkspace? {
        return workspaceManager.saveCurrentWorkspace(name)
    }

    override fun exportWorkspace(workspace: LayoutWorkspace): String {
        return workspaceManager.exportWorkspace(workspace)
    }

    override fun deleteWorkspace(name: String) {
        // Find workspace by name and delete it
        val workspace = workspaceManager.workspaces.value.find { it.name == name }
        if (workspace != null) {
            workspaceManager.deleteWorkspace(workspace.id)
        }
    }

    override fun renameWorkspace(oldName: String, newName: String) {
        // Find workspace by old name and rename it
        val workspace = workspaceManager.workspaces.value.find { it.name == oldName }
        if (workspace != null) {
            workspaceManager.renameWorkspace(workspace.id, newName)
        }
    }
}
