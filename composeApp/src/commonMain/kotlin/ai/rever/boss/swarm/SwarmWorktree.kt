package ai.rever.boss.swarm

import kotlinx.serialization.Serializable

/**
 * Which CLI agent is attached to a swarm worktree.
 *
 * BOSS is agent-agnostic, so the swarm layer never assumes a particular one. This is recorded per
 * worktree rather than per session, because a session is allowed to mix agents and the operator
 * chooses which one runs where.
 */
@Serializable
enum class SwarmAgentKind {
    CLAUDE_CODE,
    CODEX,
    GEMINI,
    OPENCODE,
}

/**
 * Lifecycle of one worktree inside a swarm session.
 *
 * [MERGED] and [DISCARDED] are terminal and are only ever reached through an approved `swarm_merge`
 * or an explicit discard. Nothing in the swarm layer promotes a worktree to [MERGED] on its own,
 * not even a winning evaluation score, because landing a branch is a governed action rather than a
 * consequence of a heuristic.
 */
@Serializable
enum class SwarmWorktreeStatus {
    SPAWNING,
    RUNNING,
    DONE,
    FAILED,
    MERGED,
    DISCARDED,
}

/**
 * Cheap progress counters for one worktree.
 *
 * Read from git rather than from the attached agent, so the numbers stay meaningful for an agent
 * that reports nothing at all, which is most of them.
 */
@Serializable
data class SwarmWorktreeStats(
    val filesChanged: Int = 0,
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0,
    /** Epoch millis of the last observed change, or null when none has been observed yet. */
    val lastActivityAt: Long? = null,
)

/**
 * One isolated git worktree running one agent, plus the BOSS surface it is attached to.
 *
 * [ownerWindowId] is load bearing rather than bookkeeping. Tab and Space registries in BOSS are per
 * window, so worktree state cannot be assumed to follow a tab into another window. The swarm layer
 * therefore treats a worktree as owned by the window that spawned it and does not try to reconcile
 * it anywhere else. This is a stated limitation of the first cut, not an oversight; see the PR
 * description.
 *
 * [terminalTabId] and [spaceId] are nullable because a worktree exists on disk from the moment
 * `git worktree add` succeeds, which is before any tab or Space has been created for it. A worktree
 * with no tab is still a real worktree and still shows in the grid.
 */
@Serializable
data class SwarmWorktree(
    val id: String,
    /** Branch created for this worktree, conventionally `swarm/<sessionId>-<n>`. */
    val branchName: String,
    /** Absolute path of the worktree on disk, as reported by `git worktree add`. */
    val path: String,
    val agentKind: SwarmAgentKind,
    /** Terminal tab the agent runs in, or null before that tab exists. */
    val terminalTabId: String? = null,
    /** Space wrapping that terminal, or null before that Space exists. */
    val spaceId: String? = null,
    val status: SwarmWorktreeStatus = SwarmWorktreeStatus.SPAWNING,
    val stats: SwarmWorktreeStats = SwarmWorktreeStats(),
    /** Window that spawned this worktree. See the class note on per-window registries. */
    val ownerWindowId: String? = null,
)
