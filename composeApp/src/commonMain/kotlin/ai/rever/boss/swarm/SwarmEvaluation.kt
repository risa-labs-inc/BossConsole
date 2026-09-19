package ai.rever.boss.swarm

import kotlinx.serialization.Serializable

/**
 * Outcome of running a worktree's tests, or the reason there is no outcome yet.
 *
 * [NOT_RUN] is deliberately distinct from [FAILED]. A worktree whose tests never executed has not
 * earned a verdict, and a ranking that treats "no evidence" as "bad evidence" would quietly punish a
 * worktree for the swarm layer's own failure to run anything.
 */
@Serializable
enum class SwarmTestOutcome {
    NOT_RUN,
    PASSED,
    FAILED,
    ERRORED,
}

/**
 * What the operation ledger recorded for one worktree, counted rather than judged.
 *
 * Derived from that worktree's `McpOperationLedger` records and nothing else, so a swarm run is
 * summarised by the same governed history every other session writes to. This is a report, not a
 * score: the swarm layer states what governance saw and leaves the verdict to the operator.
 */
@Serializable
data class SwarmRiskSummary(
    val totalCalls: Int = 0,
    /** Calls the operator or policy refused, so the tool never ran. */
    val denied: Int = 0,
    /** Calls withheld because approval could not be obtained or could not be persisted. */
    val withheld: Int = 0,
    /** Calls that ran and reported an error. */
    val failed: Int = 0,
)

/**
 * The evidence gathered for one worktree after its agent stops.
 *
 * [score] is a ranking aid for the operator and nothing more. It never triggers a merge: landing a
 * branch goes through the governed `swarm_merge` tool and a real approval prompt whatever the score
 * says. An automatic merge path is absent on purpose, not for lack of time, because a heuristic
 * that can land code without an approval would be a governance regression wearing a feature's
 * clothes.
 */
@Serializable
data class SwarmEvaluation(
    val worktreeId: String,
    val testOutcome: SwarmTestOutcome = SwarmTestOutcome.NOT_RUN,
    /** A line or two of the test run's output, or null when tests did not run. */
    val testSummary: String? = null,
    val filesChanged: Int = 0,
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0,
    val riskSummary: SwarmRiskSummary = SwarmRiskSummary(),
    /** Ranking aid only, never an automatic merge trigger. */
    val score: Double? = null,
)
