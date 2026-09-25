package ai.rever.boss.arcade.rushhour

import ai.rever.boss.arcade.rushhour.model.RushHourBoard
import ai.rever.boss.arcade.rushhour.model.RushHourEngine
import ai.rever.boss.arcade.rushhour.model.RushHourVehicle
import ai.rever.boss.arcade.rushhour.model.ValidMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RushHourEngineTest {
    @Test
    fun `zero and out-of-range steps are rejected`() {
        val board = RushHourBoard.LEVEL_1
        assertTrue(RushHourEngine.move(board, "X", 0).isFailure)
        assertTrue(RushHourEngine.move(board, "X", Int.MIN_VALUE).isFailure)
        assertTrue(RushHourEngine.move(board, "X", Int.MAX_VALUE).isFailure)
    }

    @Test
    fun `non-existent vehicle fails cleanly`() {
        val board = RushHourBoard.LEVEL_1
        val result = RushHourEngine.move(board, "NON_EXISTENT", 1)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("does not exist") == true)
    }

    @Test
    fun `boundary collision prevents vehicle from sliding off board`() {
        // In Level 1, X is at row 2, col 0 (length 2, horizontal).
        // Sliding left (-1) should fail boundary check
        val board = RushHourBoard.LEVEL_1
        val resultLeft = RushHourEngine.move(board, "X", -1)
        assertTrue(resultLeft.isFailure)
        assertTrue(resultLeft.exceptionOrNull()?.message?.contains("boundary") == true)

        // In Level 1, O is at row 0, col 5 (length 3, vertical).
        // Sliding up (-1) should fail boundary check
        val resultUp = RushHourEngine.move(board, "O", -1)
        assertTrue(resultUp.isFailure)
        assertTrue(resultUp.exceptionOrNull()?.message?.contains("boundary") == true)
    }

    @Test
    fun `vehicle collision prevents moving through another vehicle`() {
        // In Level 1, X is at (2,0). (2,2) is occupied by B.
        // Sliding X right by 2 steps would collide with B at (2,2).
        val board = RushHourBoard.LEVEL_1
        val result = RushHourEngine.move(board, "X", 2)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("collision") == true)
    }

    @Test
    fun `legal move updates vehicle coordinate correctly`() {
        // In Level 1:
        // A is at (0, 2, length 2, horizontal). Cells are (0,2) and (0,3).
        // (0, 0) and (0, 1) are empty.
        // A can slide left by 1 or 2 steps!
        val board = RushHourBoard.LEVEL_1
        val moveResult = RushHourEngine.move(board, "A", -1)
        assertTrue(moveResult.isSuccess)

        val updatedBoard = moveResult.getOrThrow()
        val vehicleA = updatedBoard.getVehicle("A")
        assertNotNull(vehicleA)
        assertEquals(0, vehicleA.row)
        assertEquals(1, vehicleA.col)
    }

    @Test
    fun `computeValidMoves includes expected legal directions`() {
        val board = RushHourBoard.LEVEL_1
        val validMoves = RushHourEngine.computeValidMoves(board)

        // A is at (0,2,len 2,H). It can move -1 (to col 1) and -2 (to col 0).
        assertTrue(validMoves.contains(ValidMove("A", -1)))
        assertTrue(validMoves.contains(ValidMove("A", -2)))

        // D is at (3,2,len 2,H). Cols 4 and 5 are empty on row 3!
        assertTrue(validMoves.contains(ValidMove("D", 1)))
        assertTrue(validMoves.contains(ValidMove("D", 2)))

        // X cannot move at start (col -1 is boundary, col +1 is blocked by B)
        assertFalse(validMoves.any { it.vehicleId == "X" })
    }

    @Test
    fun `win detection triggers when X reaches exit coordinate`() {
        // Build a board where X is placed at (2, 4)
        val solvedBoard =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle(id = "X", row = 2, col = 4, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "A", row = 0, col = 0, length = 2, isHorizontal = true),
                    ),
            )
        assertTrue(solvedBoard.isSolved())

        val unsolvedBoard =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle(id = "X", row = 2, col = 0, length = 2, isHorizontal = true),
                        RushHourVehicle(id = "A", row = 0, col = 0, length = 2, isHorizontal = true),
                    ),
            )
        assertFalse(unsolvedBoard.isSolved())
    }
}
