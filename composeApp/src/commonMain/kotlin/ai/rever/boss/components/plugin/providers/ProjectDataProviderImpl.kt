package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.plugin.api.ProjectChangeEvent
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.window.selectProjectInWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Implementation of ProjectDataProvider that wraps ProjectState.
 * Converts between composeApp's Project type and plugin's ProjectData type.
 */
class ProjectDataProviderImpl(
    private val windowProjectState: WindowProjectState?,
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

        // ONE publisher for every project change, whatever caused it.
        //
        // This used to live in selectProject() below, so only PLUGIN-initiated
        // selections were announced. The two callers that matter most were not:
        // WorkspaceApplier.applyWorkspace calls windowProjectState.selectProject
        // directly on startup restore, and so does the top bar's picker. A panel
        // built before that restore - the normal case, since workspace JSON is read
        // sequentially off disk while plugins load in parallel - never heard that a
        // project had arrived, and rendered empty. Observing the state itself catches
        // all three callers instead of the one. The bus is replay = 0: a publish that
        // never happens cannot be recovered later, so this must not be per-caller.
        if (windowProjectState != null) {
            scope.launch {
                // Seeded from the current value so the StateFlow's replay of it is not
                // announced as a change. The seed is "" (no project) at startup, and
                // telling every plugin the project became "" moments before the real
                // restore lands is exactly the clear-yourself signal to avoid.
                var previousPath = windowProjectState.selectedProject.value.path
                windowProjectState.selectedProject
                    .map { it.path }
                    .distinctUntilChanged()
                    .collect { path ->
                        if (path == previousPath) return@collect
                        val from = previousPath
                        previousPath = path
                        publishSystemEvent(
                            ProjectChangeEvent(
                                projectPath = path,
                                previousProjectPath = from,
                                windowId = windowProjectState.windowId,
                            ),
                        )
                    }
            }
        }
    }

    override fun updateRecentProjects(project: ProjectData) {
        ProjectState.updateRecentProjects(project.toProject())
    }

    override fun removeRecentProject(projectPath: String) {
        ProjectState.removeRecentProject(projectPath)
    }

    // No publish here: the init collector above announces this selection, and every
    // other one, from the state it lands in. Publishing here too would double-fire.
    override fun selectProject(project: ProjectData) {
        selectProjectInWindow(windowProjectState, project.toProject())
    }

    // Extension functions for type conversion
    private fun Project.toProjectData(): ProjectData =
        ProjectData(
            name = name,
            path = path,
            lastOpened = lastOpened,
        )

    private fun ProjectData.toProject(): Project =
        Project(
            name = name,
            path = path,
            lastOpened = lastOpened,
        )
}
