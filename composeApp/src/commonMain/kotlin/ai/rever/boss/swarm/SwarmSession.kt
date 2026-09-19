package ai.rever.boss.swarm

import kotlinx.serialization.Serializable

/**
 * Aggregate lifecycle of a swarm session, from planning through to a landed or abandoned result.
 *
 * [PARTIAL] is the honest ending for a session where some worktrees landed and others did not. That
 * is the ordinary outcome rather than an error state, so it is modelled as its own status instead of
 * being folded into [MERGED] or [DISCARDED].
 */
@Serializable
enum class SwarmSessionStatus {
    PLANNING,
    RUNNING,
    EVALUATING,
    MERGED,
    DISCARDED,
    PARTIAL,
}

/**
 * One swarm: a task, the worktrees that attempted it, and how far the session got.
 *
 * A session is a record of what was tried, not a scheduler. It holds no process handles and owns no
 * coroutines, which is what lets it survive a restart as plain data while the worktrees it describes
 * are re-adopted, or not, by the window that spawned them.
 */
@Serializable
data class SwarmSession(
    val id: String,
    /** The operator's task description, passed verbatim to every attached agent. */
    val task: String,
    val createdAt: Long,
    val worktrees: List<SwarmWorktree> = emptyList(),
    val status: SwarmSessionStatus = SwarmSessionStatus.PLANNING,
    /** Window that spawned this session. See [SwarmWorktree.ownerWindowId]. */
    val ownerWindowId: String? = null,
) {
    /** Worktrees still doing something, which is what the status bar counts. */
    val activeWorktreeCount: Int
        get() =
            worktrees.count {
                it.status == SwarmWorktreeStatus.SPAWNING || it.status == SwarmWorktreeStatus.RUNNING
            }
}
