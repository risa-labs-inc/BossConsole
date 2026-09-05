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
 * `ProjectSelectionDialog`, the CLI and deep-link handlers, and the plugin-facing
 * `ProjectDataProviderImpl.selectProject` alike.
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
    initialPath: String,
) : ProjectSelectionCallback {
    /**
     * Seeded from the selection as it stands when the window is built - `""` (no project) in
     * every production path - so the first real selection is announced as a change *from* that,
     * and a window created with a project already in it announces nothing on arrival.
     */
    private var previousPath: String = initialPath

    override fun onProjectSelected(project: Project) {
        val path = project.path
        // Re-selecting the same project is not a change. The old publish site did fire for it -
        // selectProject rewrites lastOpened on every call, so the state emits a fresh Project
        // each time - with previousProjectPath == projectPath. A plugin that used that as a
        // "reload the project" nudge no longer receives one.
        if (path == previousPath) return
        val from = previousPath
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
