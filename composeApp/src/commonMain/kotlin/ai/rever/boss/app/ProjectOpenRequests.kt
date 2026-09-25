package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update

/** A person asked to open [project] from somewhere in window [windowId]. */
internal data class ProjectOpenRequest(
    val windowId: String,
    val project: Project,
)

/**
 * Every "open this project" a person asks for in the host UI, routed to ONE dialog per window.
 *
 * A Space carries its own project, so opening a project is a question about which Space it goes
 * in: this one, a new one, or a new window. The top bar, Home's recent projects, File > Open
 * Project, Clone and New Project each used to answer part of that themselves - some asked
 * "current window or new window" only when a project was already open, some selected straight
 * away - and the project-selection effect then raised a second prompt asking for a layout. They
 * all post here now, and `BossAppDialogs` asks the one question.
 *
 * Deliberately NOT routed here, because no person is choosing: a plugin selecting a project
 * through `ProjectDataProvider`, a `boss://` deep link (`DeepLinkHandler`) and the `boss` CLI
 * (`CLICommandHandler`). Those select directly and keep the configured default-Space behaviour.
 */
internal object ProjectOpenRequests {
    private val logger = BossLogger.forComponent("ProjectOpenRequests")

    private val _requests = MutableSharedFlow<ProjectOpenRequest>(extraBufferCapacity = 8)

    /**
     * Windows with a collector on [requestsFor] right now.
     *
     * The reason [ask] can say no. A SharedFlow with no subscriber DROPS an emission and
     * `tryEmit` still returns true, so without this a request to a window that is not listening -
     * not composed yet, mid-teardown - would be a click that opens nothing and says nothing.
     */
    private val listening = MutableStateFlow<Set<String>>(emptySet())

    /**
     * This window's requests. Registered from `onSubscription`, which runs once the collector is
     * actually subscribed, so a window is never reported as listening while an emission to it
     * could still be dropped.
     */
    fun requestsFor(windowId: String): Flow<Project> =
        _requests
            .onSubscription { listening.update { it + windowId } }
            .onCompletion { listening.update { it - windowId } }
            .filter { it.windowId == windowId }
            .map { it.project }

    /**
     * Ask where [project] should open. False when there is no window listening to ask in, and the
     * caller should then select the project directly, the way it did before this existed.
     */
    fun ask(
        windowId: String?,
        project: Project,
    ): Boolean {
        if (windowId == null || windowId !in listening.value) {
            logger.warn(
                LogCategory.WORKSPACE,
                "No window listening for a project open, selecting directly",
                mapOf("windowId" to windowId.orEmpty()),
            )
            return false
        }
        return _requests.tryEmit(ProjectOpenRequest(windowId, project))
    }
}

/**
 * "Which Space?" for [project].
 *
 * Two ways in: the default-Space setting's Ask, for a project a plugin already selected
 * ([placeOnPick] false), and "New Space" in the project-open dialog, where the project is placed
 * only once a Space is picked ([placeOnPick] true) - so dismissing the list opens nothing, rather
 * than quietly meaning "This Space". [showCodebase] carries File > Open Project's folder picker's
 * promise to show the CodeBase panel, kept until the project actually lands in this window.
 */
internal data class SpacePrompt(
    val project: Project,
    val placeOnPick: Boolean,
    val showCodebase: Boolean = false,
)

/**
 * The Spaces "New Space" offers for a project at [projectPath].
 *
 * Applying a saved Space also selects the project it was saved with (`applyWorkspace` with
 * `restoreProject`), so offering a Space of ANOTHER project would open it and quietly swap out the
 * project the user just chose. Left in: the templates, which become a Space for this project;
 * saved Spaces with no project; and saved Spaces of this same project. Last Session is left out -
 * it is the autosave slot, not a Space anyone picks, and that holds for the plugin-driven prompt
 * as well as the dialog.
 */
internal fun spacesForProject(
    workspaces: List<LayoutWorkspace>,
    projectPath: String,
): List<LayoutWorkspace> {
    // Once, not per row: `allIds` is a getter that maps and collects the whole built-in list.
    val builtIns = PredefinedWorkspaces.allIds
    return workspaces.filter { workspace ->
        val ownPath = workspace.projectPath.orEmpty()
        workspace.id != LAST_SESSION_ID &&
            (workspace.id in builtIns || ownPath.isEmpty() || samePath(ownPath, projectPath))
    }
}

/**
 * The same path string, ignoring a trailing separator. A string comparison, not a filesystem one:
 * no case folding, `..` or symlink resolution - both sides come from the same kind of selection.
 */
private fun samePath(
    a: String,
    b: String,
): Boolean = a.trimEnd('/', '\\') == b.trimEnd('/', '\\')
