package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.window.selectProjectInWindow
import kotlinx.coroutines.CoroutineDispatcher
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
 *
 * Built per window ([DefaultPlugin]'s `projectDataProviderDelegate`), but [scope]'s collector
 * subscribes to [ProjectState.recentProjects] - a process-wide singleton, not this window's own
 * state - so it outlives the window unless [dispose] cancels it (BossConsole#520).
 *
 * [dispose] cannot freeze a KERNEL client's watch stream, even though in KERNEL mode the
 * instance is also handed to the process-wide
 * [ai.rever.boss.kernel.services.ProjectDataServiceBridge]: that bridge reads
 * [ProjectState.recentProjects] directly rather than this per-window [recentProjects]
 * mirror, so cancelling the mirror at window close leaves the watched source untouched.
 *
 * What [dispose] does not cover (pre-existing, not introduced here): the bridge's write
 * path still routes [selectProject] through this per-window instance, so a select that
 * arrives after the owning window closes is a no-op. Closing that gap means giving the
 * write path its own window-affinity rule - a separate change.
 */
class ProjectDataProviderImpl(
    private val windowProjectState: WindowProjectState?,
    // Injectable purely for tests. Dispatchers.Main has no implementation in a plain test JVM, so
    // a hard-coded one forces every test that builds this to install a global Main dispatcher.
    // Passing Dispatchers.Unconfined keeps any test-built collector inert and local.
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ProjectDataProvider,
    DisposableProvider {
    // SupervisorJob, matching DefaultPlugin.pluginScope. There is one collector today, so this
    // is future-proofing rather than a fix: with a plain Job a second one added later would be
    // its sibling, and a failure in either would cancel the scope and take the other with it -
    // silently, since nothing awaits them.
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

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

    /** Stops mirroring [ProjectState.recentProjects] into [recentProjects]. See the class KDoc. */
    override fun dispose() {
        scope.cancel()
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
