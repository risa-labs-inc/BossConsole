package ai.rever.boss.arcade.rushhour.eval

import ai.rever.boss.arcade.rushhour.model.RushHourBoard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
    val optimalRemaining: Int?,
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
    initialOptimalMoves: Int = 0,
) {
    private data class LogState(
        val steps: List<StepTrajectory>,
        val visitedHashes: List<String>,
        val initialOptimalMoves: Int,
    )

    private val state = MutableStateFlow(LogState(emptyList(), emptyList(), initialOptimalMoves))

    /**
     * Initializes or resets the trajectory logger with the initial board state.
     */
    fun reset(
        initialBoard: RushHourBoard,
        initialOptimalDistance: Int,
    ) {
        val initialHash = initialBoard.canonicalHash()
        state.update { LogState(emptyList(), listOf(initialHash), initialOptimalDistance) }
    }

    /**
     * Records an executed move step.
     */
    fun logStep(
        action: String,
        board: RushHourBoard,
        latencyMs: Long,
        optimalRemaining: Int?,
    ) {
        val hash = board.canonicalHash()
        state.update { current ->
            val entry =
                StepTrajectory(
                    stepIndex = current.steps.size + 1,
                    action = action,
                    boardHash = hash,
                    latencyMs = latencyMs,
                    optimalRemaining = optimalRemaining,
                )
            current.copy(steps = current.steps + entry, visitedHashes = current.visitedHashes + hash)
        }
    }

    /**
     * Returns an unmodifiable snapshot of logged steps.
     */
    fun getTrajectories(): List<StepTrajectory> = state.value.steps.toList()

    /**
     * Computes the final evaluation summary.
     */
    fun computeSummary(): TrajectorySummary {
        val current = state.value
        val totalMoves = current.steps.size
        val optimalNeeded = current.initialOptimalMoves

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
        val counts = current.visitedHashes.groupingBy { it }.eachCount()
        val cycleCount = counts.values.sumOf { if (it > 1) it - 1 else 0 }

        return TrajectorySummary(
            totalMoves = totalMoves,
            optimalMovesNeeded = optimalNeeded,
            efficiencyPercentage = efficiency,
            cycleCount = cycleCount,
        )
    }
}
