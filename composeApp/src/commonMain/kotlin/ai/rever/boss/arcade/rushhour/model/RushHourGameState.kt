package ai.rever.boss.arcade.rushhour.model

import ai.rever.boss.arcade.rushhour.eval.RushHourTrajectoryLogger
import ai.rever.boss.arcade.rushhour.eval.TrajectorySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

/**
 * Immutable snapshot of the Rush Hour Gym state.
 */
@Serializable
data class RushHourSnapshot(
    val board: RushHourBoard,
    val level: Int,
    val stepsTaken: Int,
    val initialOptimalDistance: Int?,
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
    private val stateMutex = Mutex()
    private val logger = RushHourTrajectoryLogger()

    private val _state: MutableStateFlow<RushHourSnapshot> =
        run {
            val initialLevel = 1
            val initialBoard = RushHourBoard.level(initialLevel)
            // Level 1 is fixed and its shortest solution is covered by RushHourSolverTest.
            // Avoid running a full graph search during first UI/MCP access.
            val initialOptimal: Int? = 8
            logger.reset(initialBoard, initialOptimal ?: 0)
            MutableStateFlow(
                RushHourSnapshot(
                    board = initialBoard,
                    level = initialLevel,
                    stepsTaken = 0,
                    initialOptimalDistance = initialOptimal,
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
     * Serializes resets and moves so the snapshot and trajectory always describe the same game.
     */
    suspend fun reset(level: Int): RushHourSnapshot =
        stateMutex.withLock {
            val board = RushHourBoard.level(level)
            val optimal = withContext(Dispatchers.Default) { RushHourSolver.findOptimalDistance(board) }

            logger.reset(board, optimal ?: 0)

            val newSnapshot =
                RushHourSnapshot(
                    board = board,
                    level = level,
                    stepsTaken = 0,
                    initialOptimalDistance = optimal,
                    optimalDistanceRemaining = optimal,
                    isSolved = board.isSolved(),
                    isDeadlocked = optimal == null,
                    lastMoveResult = "Reset to level $level",
                    trajectorySummary = null,
                    selectedVehicleId = null,
                )

            _state.value = newSnapshot
            newSnapshot
        }

    /**
     * Executes a move on the board, updating trajectory history and evaluating optimality.
     * Runs graph search off the caller thread while holding the game-state lock.
     */
    suspend fun move(
        vehicleId: String,
        steps: Int,
    ): Result<RushHourSnapshot> =
        stateMutex.withLock {
            if (steps == 0 || steps == Int.MIN_VALUE || steps !in -5..5) {
                return@withLock Result.failure(
                    IllegalArgumentException("Steps must be between -5 and 5, excluding zero"),
                )
            }
            val current = _state.value
            val startTime = TimeSource.Monotonic.markNow()

            val moveResult = RushHourEngine.move(current.board, vehicleId, steps)
            if (moveResult.isFailure) {
                return@withLock Result.failure(moveResult.exceptionOrNull() ?: IllegalStateException("Invalid move"))
            }

            val newBoard = moveResult.getOrThrow()
            val latency = startTime.elapsedNow().inWholeMilliseconds
            val newOptimal = withContext(Dispatchers.Default) { RushHourSolver.findOptimalDistance(newBoard) }
            val isSolved = newBoard.isSolved()
            val isDeadlocked = newOptimal == null && !isSolved
            val newStepsTaken = current.stepsTaken + 1

            logger.logStep(
                action = "$vehicleId:$steps",
                board = newBoard,
                latencyMs = latency,
                optimalRemaining = newOptimal,
            )

            val summary = if (isSolved) logger.computeSummary() else null

            val updatedSnapshot =
                RushHourSnapshot(
                    board = newBoard,
                    level = current.level,
                    stepsTaken = newStepsTaken,
                    initialOptimalDistance = current.initialOptimalDistance,
                    optimalDistanceRemaining = newOptimal,
                    isSolved = isSolved,
                    isDeadlocked = isDeadlocked,
                    lastMoveResult = "Moved '$vehicleId' by $steps step(s)",
                    trajectorySummary = summary,
                    selectedVehicleId = current.selectedVehicleId,
                )

            _state.value = updatedSnapshot
            Result.success(updatedSnapshot)
        }

    /**
     * Updates the selected vehicle for human interaction in the UI.
     */
    suspend fun selectVehicle(vehicleId: String?) {
        stateMutex.withLock {
            _state.value = _state.value.copy(selectedVehicleId = vehicleId)
        }
    }
}
