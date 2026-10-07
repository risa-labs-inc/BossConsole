package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.components.workspaces.workspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The layout watcher's write, once its settle delay has passed: keep the crash-recovery files
 * current, from the one window that owns them.
 *
 * Only [LastSessionCoordinator.ownsSessionRecord]'s window writes, and it writes BOTH files, the
 * same pair the coordinator writes at shutdown. [set] is built before anything is written, on the
 * caller's dispatcher, because it reads the window's live Compose state. Even a single Space
 * retains its identity in the set; a null set removes a stale recovery set instead of letting
 * it outrank the record (see `sessionSetOf`).
 *
 * The pair is [LastSessionCoordinator.writeInSession]: one blocking, non-suspending call, set
 * first, under the lock the shutdown write takes. Being one non-suspending call is what keeps the
 * pair whole: cancellation is only delivered at a suspension point, so once it has started, closing
 * the window cannot leave a fresh record beside the stale set restore prefers, which is the bug this
 * exists to fix. [NonCancellable] covers the moment before that: Compose cancels a closing window's
 * watcher before the coordinator hears of the close, and without it a cancel that landed before the
 * IO dispatch would drop the window's last change unwritten.
 *
 * No [Exception] escapes from here: not from the ownership check (it runs every live window's
 * `canSave`), not from the set builder, not from the pair or the bookkeeping after it. The caller is
 * the layout watcher, and an exception escaping into it would end the watcher for the rest of the
 * window's life. A failure is logged through [LastSessionCoordinator.reportInSessionFailure] - at
 * warn once per run of failures, at debug after that, each step under a message of its own - and
 * answered false. A run ends only when a whole write goes through, bookkeeping included. An [Error]
 * still escapes, as does cancellation.
 *
 * Returns whether this window wrote the record and noted it written. Any other window writes
 * nothing: its layout was never what a restart brings back, and writing it replaced the owner's
 * recovery copy. False is also the answer in the one case where the record did land: the note
 * after it threw. No caller reads the difference.
 */
// A throw from the ownership check (another window's state), the set builder, the pair or the
// note after it must never end the watcher; cancellation still propagates.
@Suppress("TooGenericExceptionCaught")
internal suspend fun writeInSessionRecovery(
    windowId: String,
    record: LayoutWorkspace,
    set: () -> LastSessionSet?,
    coordinator: LastSessionCoordinator = LastSessionCoordinator.instance,
    manager: WorkspaceManager = workspaceManager,
): Boolean {
    // The step under way, so each failure is logged under a message of its own.
    var step = "In-session recovery ownership check failed"
    return try {
        if (!coordinator.ownsSessionRecord(windowId)) return false
        step = "Could not build the Space set for recovery"
        // Built here, on the caller's dispatcher, because it reads live Compose state. A null
        // result is a real answer (remove the set); a throw writes nothing.
        val liveSet = set()
        step = "In-session recovery write failed"
        val written =
            withContext(Dispatchers.IO + NonCancellable) {
                coordinator.writeInSession(windowId, record, liveSet)
            }
        if (written) {
            step = "In-session recovery write landed, but noting it failed"
            manager.noteLastSessionRecordWritten(record)
            coordinator.endInSessionFailureRun()
        }
        written
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        coordinator.reportInSessionFailure(windowId, step, e)
        false
    }
}
