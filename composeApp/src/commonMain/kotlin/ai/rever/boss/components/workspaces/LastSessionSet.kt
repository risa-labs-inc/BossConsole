package ai.rever.boss.components.workspaces

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The name of the multi-Space session record, in the workspace directory beside the Spaces.
 *
 * **`WorkspaceManager.loadAllWorkspaces` skips it by name.** `WorkspaceFileManager.listWorkspaces`
 * lists every `*.json` in that directory and the manager tries to read each one as a
 * `LayoutWorkspace`, so without the skip this file would be deserialized as a Space on every
 * launch, fail, and log a warning for ever.
 */
const val LAST_SESSION_SET_FILE = "Last_Session_Set.json"

/**
 * Every Space a window was running, and which one was showing.
 *
 * A window RUNS several Spaces at once and shows one of them - `SplitViewState.preserveCurrentState`
 * keeps the whole split tree of each, with live browsers and terminals in it - and the old session
 * record was ONE `LayoutWorkspace` stamped `last-session`, so a restart brought back the Space that
 * happened to be on screen and silently dropped the rest.
 *
 * **Its own file, deliberately, rather than a change to `LayoutWorkspace` or to
 * `Last_Session.json`.** `LayoutWorkspace` is the plugin api type, member-checked against 33 plugin
 * repos; a field on it is a hard break. And an installed build has a single-Space
 * `Last_Session.json` on disk right now, which must keep restoring - so this is written BESIDE it
 * and read in preference to it, and the old file goes on being written as well
 * (`WorkspaceManager.saveLastSessionBlocking`, unchanged). Two consequences worth stating:
 * a downgrade still restores the Space that was showing, and the "Last Session" entry the Space
 * list has always had is still there.
 *
 * **Still ONE writer, still app-level.** `LastSessionCoordinator` allows exactly one window to
 * produce a session record per session (Issue #19: every window's dispose used to write its own
 * layout into the one record, so closing a secondary window overwrote the primary's). That has not
 * changed - the same claim now covers both files, written together.
 */
@Serializable
data class LastSessionSet(
    /** Which of [spaces] was on screen. Always the id of one of them - see [sessionSetOf]. */
    val activeWorkspaceId: String,
    /**
     * The Spaces, each carrying the live split tree it was running.
     *
     * Order is not the restore order; [restoreOrder] derives that, because what has to come last
     * is whichever one was showing.
     */
    val spaces: List<LayoutWorkspace>,
)

/** JSON for [LastSessionSet], with the same forward-compatible settings as [WorkspaceSerializer]. */
object LastSessionSetSerializer {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    fun serialize(set: LastSessionSet): String = json.encodeToString(set)

    fun deserialize(jsonString: String): LastSessionSet = json.decodeFromString(jsonString)
}

/**
 * The set worth writing for a window running [spaces] and showing [activeWorkspaceId], or null.
 *
 * Null means "write no set file, and delete any that is there". Two reasons, and the second is why
 * the deletion matters:
 *
 * - **Fewer than two Spaces needs no set.** One running Space is exactly what
 *   `Last_Session.json` already records, and a second file saying the same thing is a second thing
 *   that can disagree. So a single-Space session behaves precisely as it did before this existed.
 * - **A stale set file would win.** Restore reads the set in preference to the single-Space file,
 *   so a set left behind from a three-Space session would come back after a one-Space session and
 *   reopen two Spaces the user had closed.
 *
 * [activeWorkspaceId] must name one of [spaces]. If it does not there is nothing to show on
 * restore, and guessing - the first, the last - would put a Space on screen the user was not
 * looking at; falling back to the single-Space file is the honest answer, and that file records
 * the layout that WAS on screen.
 */
internal fun sessionSetOf(
    spaces: List<LayoutWorkspace>,
    activeWorkspaceId: String?,
): LastSessionSet? =
    activeWorkspaceId
        ?.takeIf { spaces.size >= MINIMUM_SET_SIZE && spaces.any { space -> space.id == it } }
        ?.let { LastSessionSet(activeWorkspaceId = it, spaces = spaces) }

/**
 * The order to bring the Spaces back in: everything else first, the one that was showing LAST.
 *
 * Restoring a Space means applying it, and applying replaces what is on screen - so the last one
 * applied is the one left showing. The others are preserved on the way past, which is what makes
 * them live Spaces this window is running rather than entries in a list.
 */
internal fun restoreOrder(set: LastSessionSet): List<LayoutWorkspace> =
    set.spaces.filter { it.id != set.activeWorkspaceId } + set.spaces.filter { it.id == set.activeWorkspaceId }

/**
 * Whether [set] is worth restoring, rather than falling back to the single-Space file.
 *
 * The same floor [sessionSetOf] writes at, asked on the way back in: a file with one Space in it
 * (hand-edited, or written by some future version) says nothing `Last_Session.json` does not, and
 * that file is the one the rest of the app agrees about.
 */
internal fun isRestorable(set: LastSessionSet?): Boolean =
    set != null && set.spaces.size >= MINIMUM_SET_SIZE && set.spaces.any { it.id == set.activeWorkspaceId }

/** Below this a session is a single Space, which `Last_Session.json` already records. */
private const val MINIMUM_SET_SIZE = 2
