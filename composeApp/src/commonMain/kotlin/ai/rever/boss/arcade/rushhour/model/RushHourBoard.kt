package ai.rever.boss.arcade.rushhour.model

import kotlinx.serialization.Serializable

/**
 * Immutable representation of a 6x6 Rush Hour board.
 *
 * @param vehicles All vehicles currently situated on the board.
 */
@Serializable
data class RushHourBoard(
    val vehicles: List<RushHourVehicle>,
) {
    init {
        val target = vehicles.find { it.id == TARGET_VEHICLE_ID }
        requireNotNull(target) { "Board must contain target vehicle '$TARGET_VEHICLE_ID'" }
        require(target.isHorizontal) { "Target vehicle '$TARGET_VEHICLE_ID' must be horizontal" }
        require(target.row == TARGET_ROW) { "Target vehicle '$TARGET_VEHICLE_ID' must be on row $TARGET_ROW" }
        require(target.length == TARGET_LENGTH) {
            "Target vehicle '$TARGET_VEHICLE_ID' must have length $TARGET_LENGTH"
        }

        // Validate no vehicle overlap
        val occupied = mutableMapOf<Pair<Int, Int>, String>()
        for (v in vehicles) {
            for (cell in v.occupiedCells) {
                val existing = occupied[cell]
                require(existing == null) {
                    "Vehicle overlap detected at cell $cell between '${v.id}' and '$existing'"
                }
                occupied[cell] = v.id
            }
        }
    }

    /**
     * Looks up a vehicle by its unique identifier.
     */
    fun getVehicle(id: String): RushHourVehicle? = vehicles.firstOrNull { it.id == id }

    /**
     * Maps each occupied (row, col) cell to the vehicle id occupying it.
     */
    fun occupiedCells(): Map<Pair<Int, Int>, String> =
        buildMap {
            for (v in vehicles) {
                for (cell in v.occupiedCells) {
                    put(cell, v.id)
                }
            }
        }

    /**
     * Checks if the win condition is achieved (target vehicle "X" occupies exit cell (2, 5)).
     */
    fun isSolved(): Boolean {
        val target = getVehicle(TARGET_VEHICLE_ID) ?: return false
        return target.occupies(EXIT_ROW, EXIT_COL)
    }

    /**
     * Computes a canonical, deterministic hash string for cycle detection in BFS graph search.
     */
    fun canonicalHash(): String =
        vehicles
            .sortedBy { it.id }
            .joinToString(separator = ";") { "${it.id}:${it.row},${it.col}" }

    companion object {
        const val GRID_SIZE = 6
        const val TARGET_VEHICLE_ID = "X"
        const val TARGET_ROW = 2
        const val TARGET_LENGTH = 2
        const val EXIT_ROW = 2
        const val EXIT_COL = 5
        val EXIT_COORDINATE = Pair(EXIT_ROW, EXIT_COL)

        /**
         * Level 1: Beginner (Optimal shortest path: exactly 8 moves).
         */
        val LEVEL_1 =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle(id = "X", row = 2, col = 0, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "A", row = 0, col = 2, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "B", row = 1, col = 2, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "C", row = 0, col = 4, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "D", row = 3, col = 2, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "E", row = 4, col = 3, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "F", row = 5, col = 4, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "O", row = 0, col = 5, length = 3, isHorizontal = false),
                        RushHourVehicle(id = "P", row = 3, col = 0, length = 3, isHorizontal = false),
                        RushHourVehicle(id = "Q", row = 3, col = 1, length = 3, isHorizontal = false),
                    ),
            )

        /**
         * Level 2: Intermediate (Optimal shortest path: 11 moves).
         */
        val LEVEL_2 =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle(id = "X", row = 2, col = 0, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "A", row = 0, col = 0, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "B", row = 1, col = 2, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "C", row = 0, col = 3, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "D", row = 3, col = 2, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "E", row = 4, col = 3, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "F", row = 5, col = 4, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "G", row = 1, col = 4, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "O", row = 0, col = 5, length = 3, isHorizontal = false),
                        RushHourVehicle(id = "P", row = 3, col = 0, length = 3, isHorizontal = false),
                        RushHourVehicle(id = "Q", row = 3, col = 1, length = 3, isHorizontal = false),
                    ),
            )

        /**
         * Level 3: Advanced (Optimal shortest path: 12 moves).
         */
        val LEVEL_3 =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle(id = "X", row = 2, col = 0, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "A", row = 0, col = 0, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "B", row = 0, col = 1, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "C", row = 1, col = 2, length = 3, isHorizontal = false),
                        RushHourVehicle(id = "D", row = 3, col = 0, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "E", row = 3, col = 3, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "F", row = 4, col = 2, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "G", row = 4, col = 4, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "H", row = 5, col = 2, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "O", row = 0, col = 5, length = 3, isHorizontal = false),
                    ),
            )

        /**
         * Level 4: Expert (Optimal shortest path: 14 moves).
         */
        val LEVEL_4 =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle(id = "X", row = 2, col = 3, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "A", row = 0, col = 1, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "B", row = 1, col = 2, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "C", row = 0, col = 3, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "D", row = 3, col = 2, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "E", row = 4, col = 3, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "F", row = 5, col = 4, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "G", row = 3, col = 4, length = 2, isHorizontal = false),
                        RushHourVehicle(id = "O", row = 1, col = 5, length = 3, isHorizontal = false),
                        RushHourVehicle(id = "P", row = 0, col = 0, length = 3, isHorizontal = false),
                        RushHourVehicle(id = "Q", row = 3, col = 1, length = 3, isHorizontal = false),
                    ),
            )

        /**
         * Retrieves the designated level board.
         */
        fun level(number: Int): RushHourBoard =
            when (number) {
                1 -> LEVEL_1
                2 -> LEVEL_2
                3 -> LEVEL_3
                4 -> LEVEL_4
                else -> throw IllegalArgumentException("Unsupported level: $number (expected 1 to 4)")
            }
    }
}
