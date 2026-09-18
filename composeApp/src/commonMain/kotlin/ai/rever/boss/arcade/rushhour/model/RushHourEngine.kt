@file:Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")

package ai.rever.boss.arcade.rushhour.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Represents an actionable move in Rush Hour.
 *
 * @param vehicleId The identifier of the vehicle to slide.
 * @param steps Number of cells to move. Positive moves forward/right/down; negative moves backward/left/up.
 */
@Serializable
data class ValidMove(
    val vehicleId: String,
    val steps: Int,
)

/**
 * Pure Kotlin immutable state reducer and collision engine for Rush Hour.
 */
object RushHourEngine {
    /**
     * Executes a move on the given board.
     *
     * @return [Result.success] with the updated board, or [Result.failure] if the move is invalid or blocked.
     */
    fun move(
        board: RushHourBoard,
        vehicleId: String,
        steps: Int,
    ): Result<RushHourBoard> {
        if (steps == 0) return Result.success(board)

        val vehicle =
            board.getVehicle(vehicleId)
                ?: return Result.failure(
                    IllegalArgumentException("Vehicle '$vehicleId' does not exist on the board"),
                )

        val dir = if (steps > 0) 1 else -1
        val stepCount = abs(steps)
        val occupied = board.occupiedCells()

        var currentRow = vehicle.row
        var currentCol = vehicle.col

        for (step in 1..stepCount) {
            if (vehicle.isHorizontal) {
                val nextCol = currentCol + dir
                if (nextCol < 0 || nextCol + vehicle.length > RushHourBoard.GRID_SIZE) {
                    return Result.failure(
                        IllegalArgumentException(
                            "Move of vehicle '$vehicleId' by $steps steps exceeds board boundary",
                        ),
                    )
                }
                val checkCol = if (dir > 0) nextCol + vehicle.length - 1 else nextCol
                val checkCell = Pair(currentRow, checkCol)
                val blockingId = occupied[checkCell]
                if (blockingId != null && blockingId != vehicleId) {
                    return Result.failure(
                        IllegalStateException(
                            "Vehicle '$vehicleId' collision at cell $checkCell blocked by '$blockingId'",
                        ),
                    )
                }
                currentCol = nextCol
            } else {
                val nextRow = currentRow + dir
                if (nextRow < 0 || nextRow + vehicle.length > RushHourBoard.GRID_SIZE) {
                    return Result.failure(
                        IllegalArgumentException(
                            "Move of vehicle '$vehicleId' by $steps steps exceeds board boundary",
                        ),
                    )
                }
                val checkRow = if (dir > 0) nextRow + vehicle.length - 1 else nextRow
                val checkCell = Pair(checkRow, currentCol)
                val blockingId = occupied[checkCell]
                if (blockingId != null && blockingId != vehicleId) {
                    return Result.failure(
                        IllegalStateException(
                            "Vehicle '$vehicleId' collision at cell $checkCell blocked by '$blockingId'",
                        ),
                    )
                }
                currentRow = nextRow
            }
        }

        val updatedVehicle =
            vehicle.copy(
                row = currentRow,
                col = currentCol,
            )

        val newVehicles =
            board.vehicles.map {
                if (it.id == vehicleId) updatedVehicle else it
            }

        return Result.success(RushHourBoard(newVehicles))
    }

    /**
     * Computes all currently legal moves for all vehicles on the board.
     */
    fun computeValidMoves(board: RushHourBoard): List<ValidMove> {
        val validMoves = mutableListOf<ValidMove>()

        for (vehicle in board.vehicles) {
            // Check negative steps (backward / left / up)
            for (step in 1 until RushHourBoard.GRID_SIZE) {
                if (move(board, vehicle.id, -step).isSuccess) {
                    validMoves.add(ValidMove(vehicle.id, -step))
                } else {
                    break // Path is blocked further in this direction
                }
            }

            // Check positive steps (forward / right / down)
            for (step in 1 until RushHourBoard.GRID_SIZE) {
                if (move(board, vehicle.id, step).isSuccess) {
                    validMoves.add(ValidMove(vehicle.id, step))
                } else {
                    break // Path is blocked further in this direction
                }
            }
        }

        return validMoves
    }

    /**
     * Convenient check whether a proposed move is valid.
     */
    fun canMove(
        board: RushHourBoard,
        vehicleId: String,
        steps: Int,
    ): Boolean = move(board, vehicleId, steps).isSuccess

    /**
     * Computes the maximum negative steps (backward / left / up) and positive steps
     * (forward / right / down) that a vehicle can slide before collision or board boundary.
     *
     * @return Pair(minSteps, maxSteps) where minSteps <= 0 and maxSteps >= 0.
     */
    fun computeSlidingLimits(
        board: RushHourBoard,
        vehicleId: String,
    ): Pair<Int, Int> {
        var minSteps = 0
        for (step in 1 until RushHourBoard.GRID_SIZE) {
            if (canMove(board, vehicleId, -step)) {
                minSteps = -step
            } else {
                break
            }
        }
        var maxSteps = 0
        for (step in 1 until RushHourBoard.GRID_SIZE) {
            if (canMove(board, vehicleId, step)) {
                maxSteps = step
            } else {
                break
            }
        }
        return Pair(minSteps, maxSteps)
    }
}
