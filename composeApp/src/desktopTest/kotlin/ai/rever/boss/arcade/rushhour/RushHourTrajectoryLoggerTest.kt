package ai.rever.boss.arcade.rushhour

import ai.rever.boss.arcade.rushhour.eval.RushHourTrajectoryLogger
import ai.rever.boss.arcade.rushhour.model.RushHourBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RushHourTrajectoryLoggerTest {
    @Test
    fun `concurrent logging keeps every step and an internally consistent summary`() =
        runBlocking {
            val logger = RushHourTrajectoryLogger()
            val board = RushHourBoard.LEVEL_1
            logger.reset(board, 8)

            coroutineScope {
                repeat(100) {
                    launch(Dispatchers.Default) {
                        logger.logStep("A:-1", board, latencyMs = 0, optimalRemaining = 7)
                    }
                }
            }

            assertEquals((1..100).toList(), logger.getTrajectories().map { it.stepIndex })
            val summary = logger.computeSummary()
            assertEquals(100, summary.totalMoves)
            assertEquals(8, summary.optimalMovesNeeded)
            assertEquals(8.0, summary.efficiencyPercentage)
            assertEquals(100, summary.cycleCount)
        }

    @Test
    fun `reset clears prior trajectory and preserves zero-move summary`() {
        val logger = RushHourTrajectoryLogger()
        val board = RushHourBoard.LEVEL_1
        logger.reset(board, 8)
        logger.logStep("A:-1", board, latencyMs = 0, optimalRemaining = 7)
        logger.reset(board, 0)

        assertEquals(emptyList(), logger.getTrajectories())
        assertEquals(0, logger.computeSummary().totalMoves)
        assertEquals(100.0, logger.computeSummary().efficiencyPercentage)
        assertEquals(0, logger.computeSummary().cycleCount)
    }
}
