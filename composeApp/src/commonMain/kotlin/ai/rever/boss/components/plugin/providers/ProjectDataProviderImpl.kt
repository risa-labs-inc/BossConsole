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
import kotlinx.coroutines.cancel
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
) : ProjectDataProvider,
    DisposableProvider {
    // SupervisorJob, matching DefaultPlugin.pluginScope. There is one collector today, so this
    // is future-proofing rather than a fix: with a plain Job a second one added later would be
    // its sibling, and a failure in either would cancel the scope and take the other with it -
    // silently, since nothing awaits them.
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

    /**
     * This provider is per-window and owns a coroutine, so its collector outlives the window
     * unless the plugin says otherwise - `pluginScope` is not its scope. See [DisposableProvider].
     *
     * In KERNEL mode this instance is also handed to a process-wide `ProjectDataServiceBridge`
     * (`KernelBootstrap`), so closing the window that built it freezes [recentProjects] for
     * out-of-process plugins watching it. That is how `logDataProvider` and `gitDataProvider`
     * already behave - they are registered in the same group and disposed the same way - so this
     * is consistent rather than new, but it is not pure cleanup either.
     */
    override fun dispose() {
        scope.cancel()
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
