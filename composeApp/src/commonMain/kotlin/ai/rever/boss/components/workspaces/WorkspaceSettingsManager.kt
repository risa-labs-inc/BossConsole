package ai.rever.boss.components.workspaces

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Settings for workspace behavior.
 */
@Serializable
data class WorkspaceSettings(
    /**
     * The ID of the default workspace to apply when a project is selected.
     * Use "none" to disable auto-applying workspace.
     */
    val defaultWorkspaceId: String = "workspace-claude-code",
)

/**
 * Manager for workspace settings.
 * Handles persistence and retrieval of workspace configuration.
 */
expect object WorkspaceSettingsManager {
    /**
     * Current workspace settings as a reactive flow.
     */
    val currentSettings: StateFlow<WorkspaceSettings>

    /**
     * Save current settings to persistent storage.
     */
    suspend fun saveSettings()

    /**
     * Update settings and persist.
     */
    suspend fun updateSettings(settings: WorkspaceSettings)

    /**
     * Update the default workspace ID.
     */
    suspend fun setDefaultWorkspaceId(workspaceId: String)

    /**
     * Get the default workspace to apply, or null if disabled.
     */
    fun getDefaultWorkspace(): LayoutWorkspace?
}
