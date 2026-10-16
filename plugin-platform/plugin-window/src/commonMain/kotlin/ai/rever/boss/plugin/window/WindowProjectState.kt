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

    /**
     * The selected project's path, or null when no project has been selected.
     *
     * [selectedProject] never emits null: before anything is chosen it holds the
     * sentinel above, a `Project` named "No Project" whose path is the empty string.
     * That is convenient for the UI, which wants something to render either way, and
     * it is a trap for everything else. `boss-plugin-api` declares both
     * `PluginContext.projectPath` and `WindowProjectStateProvider.getSelectedProjectPath`
     * as `String?`, and the former's KDoc says "Returns null if no project is
     * selected". A plugin writing the obvious `context.projectPath ?: fallback` got
     * the empty string instead, because an Elvis never fires on a non-null value, and
     * an empty path resolves against the filesystem root rather than failing.
     *
     * A point read, not a flow: this snapshots [selectedProject]'s current value. A
     * caller that must react to later project switches collects [selectedProject] and
     * maps it itself.
     *
     * This exists so the sentinel is recognised by the class that creates it, once,
     * instead of by every caller remembering to test for blankness. A caller that
     * genuinely needs a non-null string for a native boundary can still write
     * `selectedProjectPath ?: ""`, which is at least explicit about the substitution.
     */
    val selectedProjectPath: String?
        get() = _selectedProject.value.path.takeIf { it.isNotBlank() }

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
