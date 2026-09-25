@file:Suppress("LongMethod", "ReturnCount", "MaxLineLength")

package ai.rever.boss.arcade.rushhour.mcp

import ai.rever.boss.arcade.rushhour.model.RushHourBoard
import ai.rever.boss.arcade.rushhour.model.RushHourEngine
import ai.rever.boss.arcade.rushhour.model.RushHourGameState
import ai.rever.boss.arcade.rushhour.model.RushHourSolver
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Perception & Action MCP tools for Boss Arcade: Rush Hour Gym.
 * Exposes inspection, movement, and reset operations to external LLM agents.
 */
object RushHourMcpTools : McpToolProvider {
    const val PROVIDER_ID = "arcade-rushhour"

    const val TOOL_STATE = "arcade_rushhour_state"

    const val TOOL_MOVE = "arcade_rushhour_move"

    const val TOOL_RESET = "arcade_rushhour_reset"

    const val ARG_VEHICLE_ID = "vehicleId"
    const val ARG_STEPS = "steps"
    const val ARG_LEVEL = "level"

    override val providerId: String = PROVIDER_ID

    override fun tools(): List<McpToolDefinition> {
        val stateHandler =
            McpToolHandler { _ ->
                handleState()
            }

        val moveHandler =
            McpToolHandler { args ->
                handleMove(args)
            }

        val resetHandler =
            McpToolHandler { args ->
                handleReset(args)
            }

        val stateDescription =
            "Inspects the current Rush Hour 6x6 puzzle board, vehicle positions, valid moves, optimal steps remaining, and game status."
        val moveDescription =
            "Moves a vehicle along its orientation axis by a given number of steps (positive=forward/right/down, negative=backward/left/up)."
        val resetDescription =
            "Resets the Rush Hour puzzle board to a designated level (1 to 4, default 1) and clears trajectory history."

        return listOf(
            McpToolDefinition(
                name = TOOL_STATE,
                description = stateDescription,
                inputSchema = """{"type":"object","properties":{}}""",
                handler = stateHandler,
            ),
            McpToolDefinition(
                name = TOOL_MOVE,
                description = moveDescription,
                inputSchema =
                    """{"type":"object","properties":{"vehicleId":{"type":"string"},"steps":{"type":"integer","minimum":-5,"maximum":5}},"required":["vehicleId","steps"]}""",
                handler = moveHandler,
                readOnly = false,
            ),
            McpToolDefinition(
                name = TOOL_RESET,
                description = resetDescription,
                inputSchema =
                    """{"type":"object","properties":{"level":{"type":"integer","minimum":1,"maximum":4}}}""",
                handler = resetHandler,
                readOnly = false,
            ),
        )
    }

    /**
     * Registers this provider with the host's [McpToolRegistryImpl].
     */
    fun register(registry: McpToolRegistryImpl = McpToolRegistryImpl) {
        registry.registerProvider(this)
    }

    /**
     * Unregisters this provider from the host's [McpToolRegistryImpl].
     */
    fun unregister(registry: McpToolRegistryImpl = McpToolRegistryImpl) {
        registry.unregisterProvider(PROVIDER_ID)
    }

    suspend fun handleState(): McpToolResult {
        val snapshot = RushHourGameState.state.value
        val validMoves = RushHourEngine.computeValidMoves(snapshot.board)
        val score =
            snapshot.trajectorySummary?.efficiencyPercentage
                ?: RushHourSolver.calculateOptimalityScore(
                    initialOptimalDistance = snapshot.initialOptimalDistance,
                    stepsTaken = snapshot.stepsTaken,
                )

        val json =
            buildJsonObject {
                put("gridSize", RushHourBoard.GRID_SIZE)
                put("targetVehicle", RushHourBoard.TARGET_VEHICLE_ID)
                putJsonObject("exit") {
                    put("row", RushHourBoard.EXIT_ROW)
                    put("col", RushHourBoard.EXIT_COL)
                }
                put("level", snapshot.level)
                put(
                    "vehicles",
                    buildJsonArray {
                        for (v in snapshot.board.vehicles) {
                            add(
                                buildJsonObject {
                                    put("id", v.id)
                                    put("row", v.row)
                                    put("col", v.col)
                                    put("length", v.length)
                                    put("isHorizontal", v.isHorizontal)
                                },
                            )
                        }
                    },
                )
                put(
                    "validMoves",
                    buildJsonArray {
                        for (m in validMoves) {
                            add(
                                buildJsonObject {
                                    put("vehicleId", m.vehicleId)
                                    put("steps", m.steps)
                                },
                            )
                        }
                    },
                )
                if (snapshot.optimalDistanceRemaining != null) {
                    put("optimalDistanceRemaining", snapshot.optimalDistanceRemaining)
                } else {
                    put("optimalDistanceRemaining", -1)
                }
                put("stepsTaken", snapshot.stepsTaken)
                put("optimalityScore", score)
                put("isSolved", snapshot.isSolved)
                put("isDeadlocked", snapshot.isDeadlocked)
            }

        return McpToolResult(json.toString())
    }

    suspend fun handleMove(args: McpToolArgs): McpToolResult {
        val vehicleId = args.string(ARG_VEHICLE_ID)
        val steps = args.int(ARG_STEPS)

        if (vehicleId.isNullOrBlank()) {
            val error =
                buildJsonObject {
                    put("success", false)
                    put("error", "Missing required string argument '$ARG_VEHICLE_ID'")
                }
            return McpToolResult(error.toString(), isError = true)
        }

        if (steps == null) {
            val error =
                buildJsonObject {
                    put("success", false)
                    put("error", "Missing required integer argument '$ARG_STEPS'")
                }
            return McpToolResult(error.toString(), isError = true)
        }

        val moveResult = RushHourGameState.move(vehicleId, steps)
        if (moveResult.isFailure) {
            val ex = moveResult.exceptionOrNull()
            val error =
                buildJsonObject {
                    put("success", false)
                    put("vehicleId", vehicleId)
                    put("steps", steps)
                    put("error", ex?.message ?: "Move rejected")
                }
            return McpToolResult(error.toString(), isError = true)
        }

        val snapshot = moveResult.getOrThrow()
        val score =
            snapshot.trajectorySummary?.efficiencyPercentage
                ?: RushHourSolver.calculateOptimalityScore(
                    initialOptimalDistance = snapshot.initialOptimalDistance,
                    stepsTaken = snapshot.stepsTaken,
                )

        val response =
            buildJsonObject {
                put("success", true)
                put("vehicleId", vehicleId)
                put("steps", steps)
                put("stepsTaken", snapshot.stepsTaken)
                if (snapshot.optimalDistanceRemaining != null) {
                    put("optimalDistanceRemaining", snapshot.optimalDistanceRemaining)
                } else {
                    put("optimalDistanceRemaining", -1)
                }
                put("optimalityScore", score)
                put("isSolved", snapshot.isSolved)
                put("isDeadlocked", snapshot.isDeadlocked)
                put("message", snapshot.lastMoveResult ?: "Move succeeded")
                if (snapshot.trajectorySummary != null) {
                    putJsonObject("summary") {
                        put("totalMoves", snapshot.trajectorySummary.totalMoves)
                        put("optimalMovesNeeded", snapshot.trajectorySummary.optimalMovesNeeded)
                        put("efficiencyPercentage", snapshot.trajectorySummary.efficiencyPercentage)
                        put("cycleCount", snapshot.trajectorySummary.cycleCount)
                    }
                }
            }

        return McpToolResult(response.toString())
    }

    suspend fun handleReset(args: McpToolArgs): McpToolResult {
        val level = args.int(ARG_LEVEL) ?: 1
        if (level !in 1..4) {
            val error =
                buildJsonObject {
                    put("success", false)
                    put("error", "Level must be an integer between 1 and 4, got $level")
                }
            return McpToolResult(error.toString(), isError = true)
        }

        val snapshot = RushHourGameState.reset(level)
        val response =
            buildJsonObject {
                put("success", true)
                put("level", snapshot.level)
                if (snapshot.optimalDistanceRemaining != null) {
                    put("optimalDistance", snapshot.optimalDistanceRemaining)
                } else {
                    put("optimalDistance", -1)
                }
                put("message", "Rush Hour Gym reset to Level $level")
            }

        return McpToolResult(response.toString())
    }
}
