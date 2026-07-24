package ai.rever.boss.plugin.window

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Callback interface for project selection events.
 * Implement this to handle project updates (e.g., updating recent projects list).
 */
fun interface ProjectSelectionCallback {
    fun onProjectSelected(project: Project)
}

/**
 * Per-window project state.
 * Each window has its own selected project, independent of other windows.
 *
 * @property windowId Unique identifier for the window
 */
class WindowProjectState(
    val windowId: String,
) {
    private val logger = BossLogger.forComponent("WindowProjectState")
    private val _selectedProject =
        MutableStateFlow(
            Project(
                name = "No Project",
                path = "",
                lastOpened = 0L,
            ),
        )
    val selectedProject: StateFlow<Project> = _selectedProject.asStateFlow()

    // Callback for project selection (e.g., to update recent projects)
    private var projectSelectionCallback: ProjectSelectionCallback? = null

    /**
     * Set the callback to be notified when a project is selected.
     */
    fun setProjectSelectionCallback(callback: ProjectSelectionCallback?) {
        projectSelectionCallback = callback
    }

    /**
     * Select a project in this window.
     *
     * @param project The project to select
     */
    fun selectProject(project: Project) {
        val updatedProject = project.copy(lastOpened = System.currentTimeMillis())
        _selectedProject.value = updatedProject
        // Notify callback (e.g., to update recent projects list)
        projectSelectionCallback?.onProjectSelected(updatedProject)
        logger.debug(LogCategory.FILE, "Selected project", mapOf("windowId" to windowId, "name" to project.name, "path" to project.path))
    }

    /**
     * Get the currently selected project.
     */
    fun currentProject(): Project = _selectedProject.value
}
