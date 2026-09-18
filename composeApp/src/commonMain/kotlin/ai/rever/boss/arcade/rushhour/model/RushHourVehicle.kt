package ai.rever.boss.arcade.rushhour.model

import kotlinx.serialization.Serializable

/**
 * Represents a vehicle in the Rush Hour puzzle.
 *
 * @param id Unique identifier (e.g., "X" for the target car, "A".."Z" for obstacles).
 * @param row The 0-indexed top-most row occupied by the vehicle (0..5).
 * @param col The 0-indexed left-most column occupied by the vehicle (0..5).
 * @param length The span of the vehicle: 2 cells for cars, 3 cells for trucks.
 * @param isHorizontal True if the vehicle moves horizontally (left/right); false if vertically (up/down).
 */
@Serializable
data class RushHourVehicle(
    val id: String,
    val row: Int,
    val col: Int,
    val length: Int,
    val isHorizontal: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Vehicle id must not be blank" }
        require(length in 2..3) { "Vehicle length must be 2 or 3, got $length for vehicle '$id'" }
        require(row in 0 until RushHourBoard.GRID_SIZE) { "Row $row is out of bounds for vehicle '$id'" }
        require(col in 0 until RushHourBoard.GRID_SIZE) { "Col $col is out of bounds for vehicle '$id'" }
        if (isHorizontal) {
            require(col + length <= RushHourBoard.GRID_SIZE) {
                "Horizontal vehicle '$id' extends beyond grid width: col=$col, length=$length"
            }
        } else {
            require(row + length <= RushHourBoard.GRID_SIZE) {
                "Vertical vehicle '$id' extends beyond grid height: row=$row, length=$length"
            }
        }
    }

    /**
     * Coordinates of all cells occupied by this vehicle on the 6x6 grid.
     */
    val occupiedCells: List<Pair<Int, Int>>
        get() =
            if (isHorizontal) {
                (0 until length).map { Pair(row, col + it) }
            } else {
                (0 until length).map { Pair(row + it, col) }
            }

    /**
     * Checks whether this vehicle occupies the given grid cell.
     */
    fun occupies(
        r: Int,
        c: Int,
    ): Boolean =
        if (isHorizontal) {
            r == row && c >= col && c < col + length
        } else {
            c == col && r >= row && r < row + length
        }
}
