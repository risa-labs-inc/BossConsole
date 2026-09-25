@file:Suppress("MaxLineLength")

package ai.rever.boss.arcade.rushhour

import ai.rever.boss.arcade.rushhour.model.RushHourBoard
import ai.rever.boss.arcade.rushhour.model.RushHourEngine
import ai.rever.boss.arcade.rushhour.model.RushHourSolver
import ai.rever.boss.arcade.rushhour.model.RushHourVehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RushHourSolverTest {
    @Test
    fun `level 1 requires exactly 8 moves`() {
        val board = RushHourBoard.LEVEL_1
        val distance = RushHourSolver.findOptimalDistance(board)
        assertEquals(8, distance, "Level 1 must have an optimal path of exactly 8 moves")
    }

    @Test
    fun `optimal path solves level 1 step by step`() {
        val board = RushHourBoard.LEVEL_1
        val path = RushHourSolver.findOptimalPath(board)
        assertNotNull(path, "Optimal path must exist for Level 1")
        assertEquals(8, path.size, "Optimal path length must be exactly 8")

        var currentBoard = board
        for (move in path) {
            val stepResult = RushHourEngine.move(currentBoard, move.vehicleId, move.steps)
            assertTrue(stepResult.isSuccess, "Move $move must be legal on board: ${stepResult.exceptionOrNull()?.message}")
            currentBoard = stepResult.getOrThrow()
        }

        assertTrue(currentBoard.isSolved(), "Board must be solved after executing the optimal path")
    }

    @Test
    fun `levels 2 through 4 have solvable optimal paths`() {
        val l2Dist = RushHourSolver.findOptimalDistance(RushHourBoard.LEVEL_2)
        assertEquals(11, l2Dist)

        val l3Dist = RushHourSolver.findOptimalDistance(RushHourBoard.LEVEL_3)
        assertEquals(12, l3Dist)

        val l4Dist = RushHourSolver.findOptimalDistance(RushHourBoard.LEVEL_4)
        assertEquals(14, l4Dist)
    }

    @Test
    fun `already solved board returns distance 0`() {
        val solvedBoard =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle("X", row = 2, col = 4, length = 2, isHorizontal = true),
                        RushHourVehicle("A", row = 0, col = 0, length = 2, isHorizontal = true),
                    ),
            )
        val distance = RushHourSolver.findOptimalDistance(solvedBoard)
        assertEquals(0, distance)
        assertEquals(emptyList(), RushHourSolver.findOptimalPath(solvedBoard))
    }

    @Test
    fun `deadlocked unsolvable board returns null and detects deadlock`() {
        // Create an impossible board: exit row 2 col 5 is blocked by a vertical truck O at (0, 5, len 3)
        // and below it is another vertical truck at (3, 5, len 3).
        // Since both O and P are vertical on col 5 and together occupy rows 0..5, neither can ever move,
        // so row 2 col 5 can never be reached by X!
        val impossibleBoard =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle("X", row = 2, col = 0, length = 2, isHorizontal = true),
                        RushHourVehicle("O", row = 0, col = 5, length = 3, isHorizontal = false),
                        RushHourVehicle("P", row = 3, col = 5, length = 3, isHorizontal = false),
                    ),
            )
        assertNull(RushHourSolver.findOptimalDistance(impossibleBoard))
        assertTrue(RushHourSolver.isDeadlocked(impossibleBoard))
    }

    @Test
    fun `planning optimality score calculation`() {
        // 0 steps taken on solvable board
        val score0 = RushHourSolver.calculateOptimalityScore(initialOptimalDistance = 8, stepsTaken = 0)
        assertEquals(100.0, score0)

        // 8 steps taken with an initial optimal distance of 8 = 100%, including on victory.
        val score1 = RushHourSolver.calculateOptimalityScore(initialOptimalDistance = 8, stepsTaken = 8)
        assertEquals(100.0, score1)

        // 16 steps taken with an initial optimal distance of 8 = 50%.
        val score2 = RushHourSolver.calculateOptimalityScore(initialOptimalDistance = 8, stepsTaken = 16)
        assertEquals(50.0, score2)

        // Deadlocked board
        val scoreDeadlock = RushHourSolver.calculateOptimalityScore(initialOptimalDistance = null, stepsTaken = 5)
        assertEquals(0.0, scoreDeadlock)
    }
}
