package ai.rever.boss.arcade.rushhour.model

import ai.rever.boss.arcade.rushhour.eval.RushHourTrajectoryLogger
import ai.rever.boss.arcade.rushhour.eval.TrajectorySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of the Rush Hour Gym state.
 */
@Serializable
data class RushHourSnapshot(
    val board: RushHourBoard,
    val level: Int,
    val stepsTaken: Int,
    val optimalDistanceRemaining: Int?,
    val isSolved: Boolean,
    val isDeadlocked: Boolean,
    val lastMoveResult: String?,
    val trajectorySummary: TrajectorySummary?,
    val selectedVehicleId: String?,
)

/**
 * Thread-safe singleton controller managing the Rush Hour gym state across UI and MCP perceptions/actions.
 */
object RushHourGameState {
    private val logger = RushHourTrajectoryLogger()

    private val _state: MutableStateFlow<RushHourSnapshot> =
        run {
            val initialLevel = 1
            val initialBoard = RushHourBoard.level(initialLevel)
            val initialOptimal = RushHourSolver.findOptimalDistance(initialBoard)
            logger.reset(initialBoard, initialOptimal ?: 0)
            MutableStateFlow(
                RushHourSnapshot(
                    board = initialBoard,
                    level = initialLevel,
                    stepsTaken = 0,
                    optimalDistanceRemaining = initialOptimal,
                    isSolved = initialBoard.isSolved(),
                    isDeadlocked = initialOptimal == null,
                    lastMoveResult = null,
                    trajectorySummary = null,
                    selectedVehicleId = null,
                ),
            )
        }

    val state: StateFlow<RushHourSnapshot> = _state.asStateFlow()

    /**
     * Resets the game to the designated level configuration.
     * Enforces clean coroutine hopping to [Dispatchers.Main] for UI updates.
     */
    suspend fun reset(level: Int): RushHourSnapshot {
        val board = RushHourBoard.level(level)
        val optimal = RushHourSolver.findOptimalDistance(board)

        logger.reset(board, optimal ?: 0)

        val newSnapshot =
            RushHourSnapshot(
                board = board,
                level = level,
                stepsTaken = 0,
                optimalDistanceRemaining = optimal,
                isSolved = board.isSolved(),
                isDeadlocked = optimal == null,
                lastMoveResult = "Reset to level $level",
                trajectorySummary = null,
                selectedVehicleId = null,
            )

        try {
            withContext(Dispatchers.Main) {
                _state.value = newSnapshot
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // In headless/test environments where Dispatchers.Main is not initialized, update directly
            _state.value = newSnapshot
        }

        return newSnapshot
    }

    /**
     * Executes a move on the board, updating trajectory history and evaluating optimality.
     * Enforces clean coroutine hopping to [Dispatchers.Main] for UI updates.
     */
    suspend fun move(
        vehicleId: String,
        steps: Int,
    ): Result<RushHourSnapshot> {
        val current = _state.value
        val startTime = System.currentTimeMillis()

        val moveResult = RushHourEngine.move(current.board, vehicleId, steps)
        if (moveResult.isFailure) {
            return Result.failure(moveResult.exceptionOrNull() ?: IllegalStateException("Invalid move"))
        }

        val newBoard = moveResult.getOrThrow()
        val latency = System.currentTimeMillis() - startTime
        val newOptimal = RushHourSolver.findOptimalDistance(newBoard)
        val isSolved = newBoard.isSolved()
        val isDeadlocked = newOptimal == null && !isSolved
        val newStepsTaken = current.stepsTaken + 1

        logger.logStep(
            action = "$vehicleId:$steps",
            board = newBoard,
            latencyMs = latency,
            optimalRemaining = newOptimal ?: -1,
        )

        val summary = if (isSolved) logger.computeSummary() else null

        val updatedSnapshot =
            RushHourSnapshot(
                board = newBoard,
                level = current.level,
                stepsTaken = newStepsTaken,
                optimalDistanceRemaining = newOptimal,
                isSolved = isSolved,
                isDeadlocked = isDeadlocked,
                lastMoveResult = "Moved '$vehicleId' by $steps step(s)",
                trajectorySummary = summary,
                selectedVehicleId = current.selectedVehicleId,
            )

        try {
            withContext(Dispatchers.Main) {
                _state.value = updatedSnapshot
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            _state.value = updatedSnapshot
        }

        return Result.success(updatedSnapshot)
    }

    /**
     * Updates the selected vehicle for human interaction in the UI.
     */
    suspend fun selectVehicle(vehicleId: String?) {
        val updated = _state.value.copy(selectedVehicleId = vehicleId)
        try {
            withContext(Dispatchers.Main) {
                _state.value = updated
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            _state.value = updated
        }
    }
}
