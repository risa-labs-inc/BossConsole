package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.window.selectProjectInWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementation of ProjectDataProvider that wraps ProjectState.
 * Converts between composeApp's Project type and plugin's ProjectData type.
 */
class ProjectDataProviderImpl(
    private val windowProjectState: WindowProjectState?,
) : ProjectDataProvider {
    // SupervisorJob, matching DefaultPlugin.pluginScope. There is one collector today, so this
    // is future-proofing rather than a fix: with a plain Job a second one added later would be
    // its sibling, and a failure in either would cancel the scope and take the other with it -
    // silently, since nothing awaits them.
    //
    // NOT DisposableProvider, deliberately. This scope does outlive the window that built it -
    // `pluginScope` is not its scope - but in KERNEL mode the instance is also handed to a
    // process-wide ProjectDataServiceBridge, whose watchRecentProjects collects this StateFlow.
    // Cancelling on window close would leave that gRPC stream open and simply never changing:
    // a silent freeze for every out-of-process plugin, which is worse than the leak. Closing it
    // properly means the bridge reading ProjectState directly instead of a per-window provider,
    // which is its own change - see the PR discussion. logDataProvider and gitDataProvider are
    // registered in the same group and want the same look.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    // No ProjectChangeEvent here. This is the plugin-initiated path and it was the ONLY one that
    // published, which is why the startup restore - WorkspaceApplier calling
    // windowProjectState.selectProject directly - announced nothing and panels built before it
    // stayed empty for the session. The publish now happens where every caller ends up, in the
    // ProjectSelectionCallback that WindowProjectStateRegistry installs on the state itself;
    // see ProjectChangeAnnouncer. Publishing here too would double-fire on this path.
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
