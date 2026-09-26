package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The layout watcher's write, once its settle delay has passed: keep the crash-recovery files
 * current, from the one window that owns them.
 *
 * Only [LastSessionCoordinator.ownsSessionRecord]'s window writes, and it writes BOTH files, the
 * same pair the coordinator writes at shutdown. [set] is built before anything is written, on the
 * caller's dispatcher, because it reads the window's live Compose state; it is null for a window
 * running fewer than two Spaces, which removes a set an earlier session left rather than letting
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
 * Neither building the set nor writing the pair can throw out of here. The caller is the layout
 * watcher, and an exception escaping into it would end the watcher for the rest of the window's
 * life; a failure is logged and answered false instead.
 *
 * Returns whether this window wrote the record. Any other window writes nothing: its layout was
 * never what a restart brings back, and writing it replaced the owner's recovery copy.
 */
internal suspend fun writeInSessionRecovery(
    windowId: String,
    record: LayoutWorkspace,
    set: () -> LastSessionSet?,
    coordinator: LastSessionCoordinator = LastSessionCoordinator.instance,
    manager: WorkspaceManager = workspaceManager,
): Boolean {
    if (!coordinator.ownsSessionRecord(windowId)) return false
    // Built here, on the caller's dispatcher, because it reads live Compose state; a null result is a
    // real answer (remove the set), so a failure is kept apart from it rather than mapped to null.
    val liveSet = runCatching(set)
    liveSet.exceptionOrNull()?.let {
        logger.warn(LogCategory.WORKSPACE, "Could not build the Space set for recovery", error = it)
    }
    val written =
        liveSet.isSuccess &&
            withContext(Dispatchers.IO + NonCancellable) {
                coordinator.writeInSession(windowId, record, liveSet.getOrNull())
            }
    if (written) manager.noteLastSessionRecordWritten(record)
    return written
}

private val logger = BossLogger.forComponent("InSessionRecovery")
