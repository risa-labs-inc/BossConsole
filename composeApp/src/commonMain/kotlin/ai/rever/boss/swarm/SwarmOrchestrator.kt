package ai.rever.boss.swarm

import kotlinx.coroutines.flow.StateFlow

/**
 * The outcome of asking the orchestrator to change a session.
 *
 * Every operation that touches git or a process can fail for reasons the swarm layer does not
 * control, so the contract returns a failure rather than throwing. A caller that ignores a [Failed]
 * would be dropping a git error on the floor, and git errors are exactly what an operator needs to
 * see when a worktree does not appear.
 */
sealed interface SwarmCommandResult {
    /** The session as it now stands, which is the authoritative post-change state. */
    data class Ok(
        val session: SwarmSession,
    ) : SwarmCommandResult

    /** A human-readable reason the change did not happen, already fit to show in a dialog. */
    data class Failed(
        val message: String,
    ) : SwarmCommandResult
}

/**
 * Owns the lifecycle of the worktrees in a swarm session.
 *
 * Implemented per platform because every operation here is a real side effect on the filesystem and
 * on processes: `git worktree add`, a terminal tab, an attached CLI agent. The interface is kept
 * free of any of that so the lifecycle can be tested against a fake backend, which is the only way
 * the spawn, kill and discard paths get exercised without spawning real agents.
 *
 * Two properties the implementation must preserve:
 *
 * 1. **Nothing here decides to merge.** [SwarmCommandResult] reports worktree state; landing a
 *    branch is a governed `swarm_merge` call that goes through `McpPolicyEngine` and a real
 *    approval. The orchestrator has no merge method on purpose.
 * 2. **State is owned by the spawning window.** Tab and Space registries in BOSS are per window, so
 *    a worktree is not reconciled into another window. See [SwarmWorktree.ownerWindowId].
 *
 * Every method is `suspend` and must do its work off the UI thread, because all of it is blocking
 * process and filesystem I/O. See `docs/THREADING.md`.
 */
interface SwarmOrchestrator {
    /**
     * Creates [count] worktrees for [task] and attaches one agent to each.
     *
     * [agentKinds] must have at least [count] entries; the first [count] are used, in order. A
     * mismatch is a caller error rather than something to paper over, because silently reusing an
     * agent kind the operator did not choose would make the grid lie about what is running.
     *
     * [ownerWindowId] records which window owns the resulting worktrees.
     */
    suspend fun spawn(
        task: String,
        count: Int,
        agentKinds: List<SwarmAgentKind>,
        ownerWindowId: String? = null,
    ): SwarmCommandResult

    /** Sends [prompt] to the agent attached to [worktreeId]. */
    suspend fun sendTask(
        worktreeId: String,
        prompt: String,
    ): SwarmCommandResult

    /** Stops the agent in [worktreeId] and leaves the worktree on disk. */
    suspend fun kill(worktreeId: String): SwarmCommandResult

    /** Stops every agent in [sessionId] and leaves the worktrees on disk. */
    suspend fun killAll(sessionId: String): SwarmCommandResult

    /** Stops the agent in [worktreeId], removes the worktree and deletes its branch. */
    suspend fun discard(worktreeId: String): SwarmCommandResult

    /**
     * The sessions this orchestrator is tracking, newest first.
     *
     * Backed by the journal rather than by process state, so the grid and the status bar read the
     * same list the operator would see after a restart.
     */
    val sessions: StateFlow<List<SwarmSession>>

    companion object {
        /** Lowest worktree count the launcher offers. One worktree is not a swarm. */
        const val MIN_WORKTREES: Int = 2

        /** Highest worktree count the launcher offers, to keep the grid legible. */
        const val MAX_WORKTREES: Int = 8

        /** Branch prefix for spawned worktrees: `swarm/<sessionId>-<n>`. */
        const val BRANCH_PREFIX: String = "swarm/"
    }
}
