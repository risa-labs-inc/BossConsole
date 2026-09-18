@file:Suppress("LongMethod", "MaxLineLength")

package ai.rever.boss.arcade.rushhour

import ai.rever.boss.arcade.rushhour.model.RushHourBoard
import ai.rever.boss.arcade.rushhour.model.RushHourDragMath
import ai.rever.boss.arcade.rushhour.model.RushHourEngine
import ai.rever.boss.arcade.rushhour.model.RushHourGameState
import ai.rever.boss.arcade.rushhour.model.RushHourVehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Automated quality and interaction benchmark test suite for Rush Hour.
 * Verifies 1D constrained drag physics, boundary and collision clamping,
 * snap thresholds, and rapid alternating gesture concurrency.
 */
class RushHourInteractionTest {
    @BeforeTest
    fun setUp() {
        runBlocking {
            RushHourGameState.reset(1)
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            RushHourGameState.reset(1)
        }
    }

    @Test
    fun testPerpendicularDragOffsetIsZero() {
        val cellSize = 60f

        // Horizontal vehicle (e.g., X) subjected to pure vertical drag
        val horizontalOffset =
            RushHourDragMath.calculateConstrainedOffset(
                totalDragX = 0f,
                totalDragY = 120f, // Perpendicular drag
                isHorizontal = true,
                cellSizePx = cellSize,
                minSteps = -2,
                maxSteps = 2,
            )
        assertEquals(0f, horizontalOffset, "Horizontal vehicle must completely ignore vertical drag")

        // Vertical vehicle (e.g., B) subjected to pure horizontal drag
        val verticalOffset =
            RushHourDragMath.calculateConstrainedOffset(
                totalDragX = 120f, // Perpendicular drag
                totalDragY = 0f,
                isHorizontal = false,
                cellSizePx = cellSize,
                minSteps = -2,
                maxSteps = 2,
            )
        assertEquals(0f, verticalOffset, "Vertical vehicle must completely ignore horizontal drag")
    }

    @Test
    fun testBoundaryAndCollisionClamping() {
        val board = RushHourBoard.LEVEL_1
        val cellSize = 60f

        // In Level 1, X is at (row 2, col 0), length 2 (cols 0, 1).
        // Cell to the left is x < 0 (board edge).
        // Cell to the right (row 2, col 2) is occupied by vehicle B.
        val (minStepsX, maxStepsX) = RushHourEngine.computeSlidingLimits(board, "X")
        assertEquals(0, minStepsX, "X cannot move left beyond board edge")
        assertEquals(0, maxStepsX, "X cannot move right because cell (2, 2) is blocked by B")

        // Dragging left by 100px must be clamped to 0
        val clampedLeft =
            RushHourDragMath.calculateConstrainedOffset(
                totalDragX = -100f,
                totalDragY = 0f,
                isHorizontal = true,
                cellSizePx = cellSize,
                minSteps = minStepsX,
                maxSteps = maxStepsX,
            )
        assertEquals(0f, clampedLeft)

        // Dragging right by 200px must be clamped to 0
        val clampedRight =
            RushHourDragMath.calculateConstrainedOffset(
                totalDragX = 200f,
                totalDragY = 0f,
                isHorizontal = true,
                cellSizePx = cellSize,
                minSteps = minStepsX,
                maxSteps = maxStepsX,
            )
        assertEquals(0f, clampedRight)
    }

    @Test
    fun testSlidingLimitsWithFreeSpace() {
        // Create an open board with vehicle X at (2, 1) and no obstacles on row 2
        val openBoard =
            RushHourBoard(
                vehicles =
                    listOf(
                        RushHourVehicle("X", row = 2, col = 1, length = 2, isHorizontal = true),
                    ),
            )
        val (minSteps, maxSteps) = RushHourEngine.computeSlidingLimits(openBoard, "X")
        assertEquals(-1, minSteps, "X at col 1 can move 1 step left to col 0")
        assertEquals(3, maxSteps, "X at col 1 (length 2) occupies cols 1..2, so it can move 3 steps right to col 4 (cols 4..5)")

        val cellSize = 50f
        // Over-dragging left by -500px should clamp to -1 * 50 = -50px
        val overDragLeft =
            RushHourDragMath.calculateConstrainedOffset(
                totalDragX = -500f,
                totalDragY = 0f,
                isHorizontal = true,
                cellSizePx = cellSize,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(-50f, overDragLeft)

        // Over-dragging right by +500px should clamp to 3 * 50 = 150px
        val overDragRight =
            RushHourDragMath.calculateConstrainedOffset(
                totalDragX = 500f,
                totalDragY = 0f,
                isHorizontal = true,
                cellSizePx = cellSize,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(150f, overDragRight)
    }

    @Test
    fun testSnapThresholdDecision() {
        val cellSize = 100f
        val minSteps = -3
        val maxSteps = 3

        // Drag 35px (< 40%) -> snap back to 0
        val snapBackPos =
            RushHourDragMath.computeSnapStep(
                clampedOffset = 35f,
                cellSizePx = cellSize,
                thresholdFraction = 0.40f,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(0, snapBackPos, "Under 40% drag must snap back to 0")

        // Drag 45px (>= 40%) -> commit 1 step forward
        val commitPos =
            RushHourDragMath.computeSnapStep(
                clampedOffset = 45f,
                cellSizePx = cellSize,
                thresholdFraction = 0.40f,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(1, commitPos, "45% drag must commit 1 step forward")

        // Drag -35px (< 40%) -> snap back to 0
        val snapBackNeg =
            RushHourDragMath.computeSnapStep(
                clampedOffset = -35f,
                cellSizePx = cellSize,
                thresholdFraction = 0.40f,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(0, snapBackNeg, "Under 40% backward drag must snap back to 0")

        // Drag -45px (>= 40%) -> commit -1 step backward
        val commitNeg =
            RushHourDragMath.computeSnapStep(
                clampedOffset = -45f,
                cellSizePx = cellSize,
                thresholdFraction = 0.40f,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(-1, commitNeg, "45% backward drag must commit 1 step backward")

        // Multi-cell drag: 150px with maxSteps = 3 -> 2 steps
        val multiCell =
            RushHourDragMath.computeSnapStep(
                clampedOffset = 150f,
                cellSizePx = cellSize,
                thresholdFraction = 0.40f,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(2, multiCell, "150px drag across 100px cells should commit 2 steps")
    }

    @Test
    fun testRapidAlternatingMovesConcurrency() {
        runBlocking(Dispatchers.Default) {
            RushHourGameState.reset(1)

            // Vehicle A in Level 1 is at (row 0, col 2), length 2 (cols 2, 3).
            // Free spaces: cols 0, 1 are free! So A can move left -1 or -2.
            // Perform rapid alternating moves
            val jobs =
                (1..10).map { i ->
                    async {
                        if (i % 2 == 0) {
                            RushHourGameState.move("A", -1)
                        } else {
                            RushHourGameState.move("A", 1)
                        }
                    }
                }
            jobs.awaitAll()

            // After all jobs, board must remain valid and non-overlapping
            val currentBoard = RushHourGameState.state.value.board
            val vehicleA = currentBoard.getVehicle("A")
            assertTrue(vehicleA != null, "Vehicle A must exist")
            assertTrue(vehicleA.col in 0..4, "Vehicle A col must be in legal range")
            assertEquals(10, currentBoard.vehicles.size, "All 10 vehicles must remain intact")
        }
    }

    @Test
    fun testDestinationProjectionCoordinatesAndCollisionSafety() {
        val cellSize = 60f

        // Vehicle X at row 2, col 1, length 2 (horizontal)
        // With an obstacle at col 4, maxSteps should be 1 (cols 1..2 -> cols 2..3, col 4 blocked)
        val testVehicle = RushHourVehicle("X", row = 2, col = 1, length = 2, isHorizontal = true)
        val obstacle = RushHourVehicle("O", row = 0, col = 4, length = 3, isHorizontal = false)
        val board = RushHourBoard(vehicles = listOf(testVehicle, obstacle))

        val (minSteps, maxSteps) = RushHourEngine.computeSlidingLimits(board, "X")
        assertEquals(-1, minSteps, "X can slide left 1 step to col 0")
        assertEquals(1, maxSteps, "X can slide right 1 step to col 2 (cols 2..3), because col 4 is blocked by O")

        // 1. Dragging +1 step forward (60px)
        val projectedStepForward =
            RushHourDragMath.computeProjectedStep(
                clampedOffset = 60f,
                cellSizePx = cellSize,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(1, projectedStepForward, "60px drag must resolve to +1 step")

        val (projRowForward, projColForward) =
            RushHourDragMath.computeProjectedCoordinates(testVehicle, projectedStepForward)
        assertEquals(2, projRowForward, "Row remains unchanged for horizontal vehicle")
        assertEquals(2, projColForward, "Col shifts from 1 to 2 (+1 step)")

        // 2. Dragging -1 step backward (-60px)
        val projectedStepBackward =
            RushHourDragMath.computeProjectedStep(
                clampedOffset = -60f,
                cellSizePx = cellSize,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(-1, projectedStepBackward, "-60px drag must resolve to -1 step")

        val (projRowBackward, projColBackward) =
            RushHourDragMath.computeProjectedCoordinates(testVehicle, projectedStepBackward)
        assertEquals(2, projRowBackward)
        assertEquals(0, projColBackward, "Col shifts from 1 to 0 (-1 step)")

        // 3. Collision safety: over-dragging beyond obstacle (+300px) must be clamped to maxSteps (1)
        val constrainedOffset =
            RushHourDragMath.calculateConstrainedOffset(
                totalDragX = 300f,
                totalDragY = 0f,
                isHorizontal = true,
                cellSizePx = cellSize,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(60f, constrainedOffset, "Offset must clamp to maxSteps * cellSize = 60px")

        val overDragProjected =
            RushHourDragMath.computeProjectedStep(
                clampedOffset = constrainedOffset,
                cellSizePx = cellSize,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(1, overDragProjected, "Projected step cannot exceed maxSteps (1)")

        // 4. Zero drag offset (or under half cell) should project to 0 (no preview)
        val zeroProjected =
            RushHourDragMath.computeProjectedStep(
                clampedOffset = 15f,
                cellSizePx = cellSize,
                minSteps = minSteps,
                maxSteps = maxSteps,
            )
        assertEquals(0, zeroProjected, "Drag under half cell must project to 0")

        // 5. Vertical vehicle projection safety
        val vertVehicle = RushHourVehicle("A", row = 1, col = 0, length = 2, isHorizontal = false)
        val (vertRow, vertCol) = RushHourDragMath.computeProjectedCoordinates(vertVehicle, 1)
        assertEquals(2, vertRow, "Vertical vehicle row shifts by +1")
        assertEquals(0, vertCol, "Vertical vehicle col remains unchanged")
    }
}
