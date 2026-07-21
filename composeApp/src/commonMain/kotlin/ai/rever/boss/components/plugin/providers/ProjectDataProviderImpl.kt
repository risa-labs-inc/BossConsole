package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.plugin.api.ProjectChangeEvent
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.window.selectProjectInWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Implementation of ProjectDataProvider that wraps ProjectState.
 * Converts between composeApp's Project type and plugin's ProjectData type.
 */
class ProjectDataProviderImpl(
    private val windowProjectState: WindowProjectState?
) : ProjectDataProvider {

    private val scope = CoroutineScope(Dispatchers.Main)

    // Map ProjectState's recentProjects to plugin's ProjectData type
    private val _recentProjects = MutableStateFlow<List<ProjectData>>(emptyList())
    override val recentProjects: StateFlow<List<ProjectData>> = _recentProjects.asStateFlow()

    init {
        // Sync with ProjectState
        scope.launch {
            ProjectState.recentProjects.collect { projects ->
                _recentProjects.value = projects.map { it.toProjectData() }
            }
        }
    }

    override fun updateRecentProjects(project: ProjectData) {
        ProjectState.updateRecentProjects(project.toProject())
    }

    override fun removeRecentProject(projectPath: String) {
        ProjectState.removeRecentProject(projectPath)
    }

    override fun selectProject(project: ProjectData) {
        val previousPath = windowProjectState?.selectedProject?.value?.path
        selectProjectInWindow(windowProjectState, project.toProject())
        publishSystemEvent(
            ProjectChangeEvent(
                projectPath = project.path,
                previousProjectPath = previousPath,
                windowId = windowProjectState?.windowId ?: "",
            )
        )
    }

    // Extension functions for type conversion
    private fun Project.toProjectData(): ProjectData = ProjectData(
        name = name,
        path = path,
        lastOpened = lastOpened
    )

    private fun ProjectData.toProject(): Project = Project(
        name = name,
        path = path,
        lastOpened = lastOpened
    )
}
