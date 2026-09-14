package ai.rever.boss.components.workspaces

import ai.rever.boss.app.spaceIsUnsaved
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What the unsaved mark on a menu row is called to anything that reads the screen.
 *
 * States the fact, not the remedy: the row is a switch, and the button that answers the mark is in
 * the vertical bar, not here. The bar's own affordance is the one that names the action.
 */
internal const val SPACE_UNSAVED_ROW_DESCRIPTION = "Unsaved changes"

/**
 * Where a Space is running, as the Space menu reports it.
 *
 * Three values rather than two: [Current] is the Space on screen in THIS window, [Running] is one
 * whose tabs are alive somewhere else - behind this one, or in another window - and [Idle] is one
 * that is not running at all. The middle value used to have no mark, so a Space whose tabs were
 * live looked exactly like one that had never been opened.
 */
internal enum class SpaceRunState {
    Current,
    Running,
    Idle,
}

/**
 * Everything one row of the Space menu says about its Space, as two INDEPENDENT facts.
 *
 * Deliberately not one four-valued mark. A Space can be on screen *and* hold unsaved changes, so a
 * single mark would have to drop one of the two answers - and the one it dropped would be the
 * actionable one. Two marks, two questions: "where is this running" and "is there work in it that
 * is not on disk".
 */
internal data class SpaceRowMarks(
    val run: SpaceRunState,
    val unsaved: Boolean,
)

/**
 * The marks for one row.
 *
 * @param unsavedWorkspaceIds the Spaces THIS window holds unsaved changes to. Per window because
 *   the live layout only exists in a window's own `SplitViewState`; one flat set would mark a Space
 *   here because it was edited over there. Read through [spaceIsUnsaved] rather than by plain
 *   containment, so this menu and the vertical bar answer with one rule - which is what puts a mark
 *   on Last Session, a slot that is never a saved document.
 */
internal fun spaceRowMarks(
    workspaceId: String,
    currentWorkspaceId: String?,
    runningWorkspaceIds: Set<String>,
    unsavedWorkspaceIds: Set<String>,
): SpaceRowMarks {
    val run =
        when {
            workspaceId == currentWorkspaceId -> SpaceRunState.Current
            workspaceId in runningWorkspaceIds -> SpaceRunState.Running
            else -> SpaceRunState.Idle
        }
    return SpaceRowMarks(run = run, unsaved = spaceIsUnsaved(workspaceId, unsavedWorkspaceIds))
}

/**
 * The running dot's glyph.
 *
 * Filled for this window, outlined for another's: the same mark at two strengths says "running"
 * once and "yours" only on the one that is.
 */
internal fun SpaceRunState.dotIcon(): ImageVector? =
    when (this) {
        SpaceRunState.Current -> Icons.Filled.Circle
        SpaceRunState.Running -> Icons.Outlined.Circle
        SpaceRunState.Idle -> null
    }
