package ai.rever.boss.arcade.rushhour.eval

import ai.rever.boss.arcade.rushhour.model.RushHourBoard
import kotlinx.serialization.Serializable

/**
 * Detailed metrics captured for a single step in an agent or human trajectory.
 */
@Serializable
data class StepTrajectory(
    val stepIndex: Int,
    val action: String,
    val boardHash: String,
    val latencyMs: Long,
    val optimalRemaining: Int,
)

/**
 * End-of-game benchmark and evaluation summary.
 */
@Serializable
data class TrajectorySummary(
    val totalMoves: Int,
    val optimalMovesNeeded: Int,
    val efficiencyPercentage: Double,
    val cycleCount: Int,
)

/**
 * Records step-by-step evaluation metrics and evaluates planning efficiency and cycle count.
 */
class RushHourTrajectoryLogger(
    private var initialOptimalMoves: Int = 0,
) {
    private val steps = mutableListOf<StepTrajectory>()
    private val visitedHashes = mutableListOf<String>()

    /**
     * Initializes or resets the trajectory logger with the initial board state.
     */
    fun reset(
        initialBoard: RushHourBoard,
        initialOptimalDistance: Int,
    ) {
        steps.clear()
        visitedHashes.clear()
        initialOptimalMoves = initialOptimalDistance
        visitedHashes.add(initialBoard.canonicalHash())
    }

    /**
     * Records an executed move step.
     */
    fun logStep(
        action: String,
        board: RushHourBoard,
        latencyMs: Long,
        optimalRemaining: Int,
    ) {
        val hash = board.canonicalHash()
        val entry =
            StepTrajectory(
                stepIndex = steps.size + 1,
                action = action,
                boardHash = hash,
                latencyMs = latencyMs,
                optimalRemaining = optimalRemaining,
            )
        steps.add(entry)
        visitedHashes.add(hash)
    }

    /**
     * Returns an unmodifiable snapshot of logged steps.
     */
    fun getTrajectories(): List<StepTrajectory> = steps.toList()

    /**
     * Computes the final evaluation summary.
     */
    fun computeSummary(): TrajectorySummary {
        val totalMoves = steps.size
        val optimalNeeded = initialOptimalMoves

        // Efficiency = (optimal / total) * 100%
        val efficiency =
            if (totalMoves > 0 && optimalNeeded > 0) {
                ((optimalNeeded.toDouble() / totalMoves.toDouble()) * 100.0).coerceIn(0.0, 100.0)
            } else if (totalMoves == 0 && optimalNeeded == 0) {
                100.0
            } else {
                0.0
            }

        // Cycle count: count how many times a board state is revisited
        val counts = visitedHashes.groupingBy { it }.eachCount()
        val cycleCount = counts.values.sumOf { if (it > 1) it - 1 else 0 }

        return TrajectorySummary(
            totalMoves = totalMoves,
            optimalMovesNeeded = optimalNeeded,
            efficiencyPercentage = efficiency,
            cycleCount = cycleCount,
        )
    }
}
