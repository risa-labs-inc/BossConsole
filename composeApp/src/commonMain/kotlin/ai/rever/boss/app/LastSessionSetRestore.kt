package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.restoreOrder
import ai.rever.boss.components.workspaces.withKnownNames
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowProjectState
import kotlinx.coroutines.CancellationException

private val logger = BossLogger.forComponent("LastSessionSetRestore")

/**
 * Bring back every Space a window was running, and show the one that was on screen.
 *
 * A window runs several Spaces at once - `SplitViewState.preserveCurrentState` keeps the whole
 * split tree of each - and the old session record was one `LayoutWorkspace`, so a restart brought
 * back whichever Space happened to be showing and dropped the rest. This applies them in
 * [restoreOrder]: everything else first, the one that was active LAST, so the last apply is what
 * is left on screen.
 *
 * **No second mechanism.** Each Space is brought back by the ordinary [applyWorkspace] and then
 * preserved by the ordinary `preserveCurrentState`, which is exactly the pair a workspace switch
 * performs - so a restored Space is a running Space in every sense, and switching between them
 * afterwards restores trees rather than rebuilding layouts.
 *
 * Three things about the sequence:
 *
 * - **A Space is preserved BEFORE the next one is applied, never after.** `preserveCurrentState`
 *   records whatever is on screen under the id it is currently holding; applying first would build
 *   the next tree over the top of the previous one and lose it, which is the failure the switch
 *   path's ordering comment already records.
 * - **Only the FIRST apply restores the project.** All the entries carry the same path - a window
 *   has one project (see `extractRunningWorkspaces`) - and the project has to be selected before
 *   any tabs are built, because `applyWorkspace` resolves the directory its terminals open in from
 *   the window's selection. Repeating it per Space would re-select the same project several times
 *   on every launch.
 * - **One failure does not abort the rest.** Each apply is guarded: a Space whose layout cannot be
 *   rebuilt (a plugin tab type that never registered, a corrupt entry) must not take the Spaces
 *   after it - and above all must not take the ACTIVE one, which is applied last.
 *
 * @param onLoad called with each Space just before it is applied, so the caller can set
 *   `currentWorkspace` and record the restored project path. Called for every Space in turn, which
 *   leaves the manager holding the active one, because that is the last call.
 * @return the Spaces that were applied without throwing.
 */
internal suspend fun restoreLastSessionSet(
    set: LastSessionSet,
    splitViewState: SplitViewState,
    windowProjectState: WindowProjectState,
    onLoad: (LayoutWorkspace) -> Unit = { workspaceManager.loadWorkspace(it) },
    knownSpaces: List<LayoutWorkspace> = workspaceManager.workspaces.value,
): List<LayoutWorkspace> {
    // Names come from the Space list, not from the set. See `withKnownNames`: an adopted Space's
    // name is derived at load, so a set written before that derivation changed would otherwise
    // show the old name for ever - and so would a Space the user has since renamed.
    val order = withKnownNames(restoreOrder(set), knownSpaces)
    val applied = mutableListOf<LayoutWorkspace>()

    order.forEachIndexed { index, space ->
        // Preserve whatever is on screen under its OWN id, so it stays a live Space this window is
        // running rather than being replaced by the next apply. Read off the split state rather
        // than off the previous iteration deliberately: `preserveCurrentState` stores under the id
        // it is CURRENTLY holding and the argument pair describes the workspace being left, so an
        // apply that threw halfway - which leaves the split state holding the id it had reached -
        // would otherwise have its partial tree filed under the previous Space's name. Null on the
        // first pass, a fresh window having no current workspace, so nothing is preserved.
        splitViewState.currentWorkspaceId?.let { leavingId ->
            splitViewState.preserveCurrentState(
                workspaceId = leavingId,
                workspaceName = order.firstOrNull { it.id == leavingId }?.name.orEmpty(),
            )
        }

        onLoad(space)
        try {
            applyWorkspace(
                workspace = space,
                splitViewState = splitViewState,
                windowProjectState = windowProjectState,
                restoreProject = index == 0,
            )
            applied += space
        } catch (e: CancellationException) {
            throw e
        } catch (
            // The point is that ANY failure in one Space is survivable: what a plugin's tab
            // factory throws is not knowable here, and the Space applied last is the one the user
            // is about to look at.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.error(
                LogCategory.WORKSPACE,
                "A Space in the last session could not be restored - continuing with the rest",
                mapOf("workspace" to space.name, "id" to space.id),
                error = e,
            )
        }
    }

    logger.info(
        LogCategory.WORKSPACE,
        "Restored the last session",
        mapOf(
            "requested" to order.size.toString(),
            "restored" to applied.size.toString(),
            "active" to set.activeWorkspaceId,
        ),
    )
    return applied
}
