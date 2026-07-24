package ai.rever.boss.services.bookmarks

import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapter implementation that wraps WorkspaceManager for the plugin API.
 */
class WorkspaceDataProviderImpl(
    private val manager: WorkspaceManager = workspaceManager,
) : WorkspaceDataProvider {
    override val workspaces: StateFlow<List<LayoutWorkspace>> = manager.workspaces

    override val currentWorkspace: StateFlow<LayoutWorkspace?> = manager.currentWorkspace

    override fun loadWorkspace(workspace: LayoutWorkspace) {
        manager.loadWorkspace(workspace)
    }

    override fun updateCurrentWorkspace(newWorkspace: LayoutWorkspace) {
        manager.updateCurrentWorkspace(newWorkspace)
    }

    override fun saveCurrentWorkspace(name: String?): LayoutWorkspace? = manager.saveCurrentWorkspace(name)

    override fun exportWorkspace(workspace: LayoutWorkspace): String = manager.exportWorkspace(workspace)

    override fun deleteWorkspace(name: String) {
        manager.deleteWorkspace(name)
    }

    override fun renameWorkspace(
        oldName: String,
        newName: String,
    ) {
        manager.renameWorkspace(oldName, newName)
    }
}
