package ai.rever.boss.window

import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.plugin.api.ProjectChangeEvent

/**
 * Announces every project selection in one window onto the application event bus.
 *
 * Installed as half of the window's [ProjectSelectionCallback] by [WindowProjectStateRegistry],
 * which is the only production path that builds a [WindowProjectState]. That placement is the
 * whole point: `WindowProjectState.selectProject` is the *sole* mutator of the selection (the
 * backing flow is private) and it invokes the callback synchronously, so every caller is
 * announced - `WorkspaceApplier.applyWorkspace` on startup restore, the top bar picker,
 * `ProjectSelectionDialog`, the CLI and deep-link handlers, the KERNEL-mode
 * `ProjectDataServiceBridge`, and the plugin-facing `ProjectDataProviderImpl.selectProject`
 * alike.
 *
 * This used to live in `ProjectDataProviderImpl.selectProject`, so only PLUGIN-initiated
 * selections were announced and the startup restore - the case that matters - published nothing
 * at all. The bus is `replay = 0`: a publish that never happens cannot be recovered by a later
 * subscriber, which is why the announcement has to be unconditional rather than per-caller, and
 * why it must not sit behind a `by lazy` provider that nothing is guaranteed to touch before the
 * restore lands.
 *
 * Synchronous by construction - no coroutine, no scope, nothing to dispose. A collector over
 * `selectedProject` would have had to be started by someone, and "someone" was the lazy provider.
 */
internal class ProjectChangeAnnouncer(
    private val windowId: String,
    initialPath: String = "",
) : ProjectSelectionCallback {
    /**
     * Guards [previousPath] *and* the publish, so the event order subscribers see matches the
     * previous-path chain they carry. One caller is not main-confined:
     * `ProjectDataServiceBridge.selectProject` is a gRPC handler that runs on a gRPC executor
     * thread in KERNEL mode with no hop to Main, so two selections really can arrive at once.
     * Uncontended in every other case.
     *
     * What this does NOT settle, and cannot from here: agreement between the last event and
     * `WindowProjectState.selectedProject`. That class writes `_selectedProject.value` and then
     * calls this callback with no atomicity of its own, so under concurrent selection the state
     * can already end up disagreeing with the last event. Closing that means locking upstream.
     */
    private val lock = Any()

    /**
     * The last announced path, seeded so that the selection already standing when the announcer
     * is installed is not itself announced. In production that seed is always `""`:
     * [WindowProjectState] constructs itself with no project and the registry installs this on
     * the next line. The parameter is defensive - and reachable from tests - rather than a
     * production scenario, so do not read a non-empty seed as something a call site arranges.
     */
    private var previousPath: String = initialPath

    override fun onProjectSelected(project: Project) {
        val path = project.path
        synchronized(lock) {
            val from = previousPath
            // Re-selecting the same project is not a change. The old publish site did fire for
            // it - selectProject rewrites lastOpened on every call, so the state emits a fresh
            // Project each time - with previousProjectPath == projectPath. A plugin that used
            // that as a "reload the project" nudge no longer receives one. (Checked: the only
            // consumers in boss_plugins are the two fluck-agent panels, and both assign
            // `_bossProject.value` and re-sweep, which is idempotent on a repeat.)
            if (path == from) return
            previousPath = path
            publishSystemEvent(
                ProjectChangeEvent(
                    projectPath = path,
                    previousProjectPath = from,
                    windowId = windowId,
                ),
            )
        }
    }
}
