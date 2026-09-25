package ai.rever.boss.arcade.rushhour

import ai.rever.boss.arcade.rushhour.mcp.RushHourMcpTools
import ai.rever.boss.arcade.rushhour.model.RushHourGameState
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpPolicyEngine
import ai.rever.boss.mcp.McpToolRegistryCore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RushHourMcpTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var registryCore: McpToolRegistryCore

    @BeforeTest
    fun setUp() {
        runBlocking {
            val policy = McpPolicyEngine()
            policy.setToolPolicy(RushHourMcpTools.TOOL_MOVE, McpPolicyAction.ALLOW)
            policy.setToolPolicy(RushHourMcpTools.TOOL_RESET, McpPolicyAction.ALLOW)
            registryCore = McpToolRegistryCore(disabledFile = null, policyEngine = policy)
            registryCore.registerProvider(RushHourMcpTools)
            RushHourGameState.reset(1)
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            registryCore.unregisterProvider(RushHourMcpTools.providerId)
            RushHourGameState.reset(1)
        }
    }

    @Test
    fun `tools are correctly registered in MCP registry`() {
        val registered = registryCore.tools.value.map { it.definition.name }
        assertTrue(registered.contains(RushHourMcpTools.TOOL_STATE))
        assertTrue(registered.contains(RushHourMcpTools.TOOL_MOVE))
        assertTrue(registered.contains(RushHourMcpTools.TOOL_RESET))
        assertEquals(3, registered.size)
        val definitions = registryCore.tools.value.associateBy { it.definition.name }
        assertTrue(definitions.getValue(RushHourMcpTools.TOOL_STATE).definition.readOnly)
        assertFalse(definitions.getValue(RushHourMcpTools.TOOL_MOVE).definition.readOnly)
        assertFalse(definitions.getValue(RushHourMcpTools.TOOL_RESET).definition.readOnly)
    }

    @Test
    fun `state tool returns compact structured JSON schema`() =
        runBlocking {
            val result = registryCore.invoke(RushHourMcpTools.TOOL_STATE, "{}")
            assertFalse(result.isError, "State tool should succeed")

            val obj = json.parseToJsonElement(result.text).jsonObject
            assertEquals(6, obj["gridSize"]?.jsonPrimitive?.int)
            assertEquals("X", obj["targetVehicle"]?.jsonPrimitive?.content)

            val exit = obj["exit"]?.jsonObject
            assertNotNull(exit)
            assertEquals(2, exit["row"]?.jsonPrimitive?.int)
            assertEquals(5, exit["col"]?.jsonPrimitive?.int)

            assertEquals(1, obj["level"]?.jsonPrimitive?.int)
            assertEquals(0, obj["stepsTaken"]?.jsonPrimitive?.int)
            assertEquals(8, obj["optimalDistanceRemaining"]?.jsonPrimitive?.int)
            assertEquals(false, obj["isSolved"]?.jsonPrimitive?.boolean)

            val vehicles = obj["vehicles"]?.jsonArray
            assertNotNull(vehicles)
            assertTrue(vehicles.isNotEmpty())

            val validMoves = obj["validMoves"]?.jsonArray
            assertNotNull(validMoves)
            assertTrue(validMoves.isNotEmpty())
        }

    @Test
    fun `move tool succeeds on legal step and updates distance`() =
        runBlocking {
            // A is at (0, 2) in Level 1 and can slide left by -1 step
            val result = registryCore.invoke(RushHourMcpTools.TOOL_MOVE, """{"vehicleId": "A", "steps": -1}""")
            assertFalse(result.isError, "Legal move should succeed: ${result.text}")

            val obj = json.parseToJsonElement(result.text).jsonObject
            assertTrue(obj["success"]?.jsonPrimitive?.boolean == true)
            assertEquals("A", obj["vehicleId"]?.jsonPrimitive?.content)
            assertEquals(-1, obj["steps"]?.jsonPrimitive?.int)
            assertEquals(1, obj["stepsTaken"]?.jsonPrimitive?.int)
            assertEquals(7, obj["optimalDistanceRemaining"]?.jsonPrimitive?.int)
        }

    @Test
    fun `move tool fails gracefully on missing arguments or invalid move`() =
        runBlocking {
            // Missing vehicleId
            val res1 = registryCore.invoke(RushHourMcpTools.TOOL_MOVE, """{"steps": 1}""")
            assertTrue(res1.isError)

            // Missing steps
            val res2 = registryCore.invoke(RushHourMcpTools.TOOL_MOVE, """{"vehicleId": "A"}""")
            assertTrue(res2.isError)

            // Blocked collision move (X moving 2 steps right into B)
            val res3 = registryCore.invoke(RushHourMcpTools.TOOL_MOVE, """{"vehicleId": "X", "steps": 2}""")
            assertTrue(res3.isError)
            val errObj = json.parseToJsonElement(res3.text).jsonObject
            assertFalse(errObj["success"]?.jsonPrimitive?.boolean == true)

            val zero = registryCore.invoke(RushHourMcpTools.TOOL_MOVE, """{"vehicleId":"A","steps":0}""")
            val overflow =
                registryCore.invoke(RushHourMcpTools.TOOL_MOVE, """{"vehicleId":"A","steps":-2147483648}""")
            assertTrue(zero.isError)
            assertTrue(overflow.isError)
            assertEquals(0, RushHourGameState.state.value.stepsTaken)
        }

    @Test
    fun `reset tool restores initial board and level`() =
        runBlocking {
            // Move A first
            registryCore.invoke(RushHourMcpTools.TOOL_MOVE, """{"vehicleId": "A", "steps": -1}""")

            // Reset to level 2
            val resetRes = registryCore.invoke(RushHourMcpTools.TOOL_RESET, """{"level": 2}""")
            assertFalse(resetRes.isError)

            val stateRes = registryCore.invoke(RushHourMcpTools.TOOL_STATE, "{}")
            val stateObj = json.parseToJsonElement(stateRes.text).jsonObject
            assertEquals(2, stateObj["level"]?.jsonPrimitive?.int)
            assertEquals(0, stateObj["stepsTaken"]?.jsonPrimitive?.int)
            assertEquals(11, stateObj["optimalDistanceRemaining"]?.jsonPrimitive?.int)
        }

    @Test
    fun `reset tool rejects invalid level number`() =
        runBlocking {
            val res = registryCore.invoke(RushHourMcpTools.TOOL_RESET, """{"level": 99}""")
            assertTrue(res.isError)
        }

    @Test
    fun `perfect solve reports the same efficiency in state and victory summary`() =
        runBlocking {
            val solution =
                listOf(
                    "A" to -2,
                    "D" to 2,
                    "B" to 2,
                    "E" to -4,
                    "D" to -1,
                    "F" to -1,
                    "O" to 3,
                    "X" to 4,
                )
            var victory = registryCore.invoke(RushHourMcpTools.TOOL_STATE, "{}")
            for ((vehicleId, steps) in solution) {
                victory =
                    registryCore.invoke(
                        RushHourMcpTools.TOOL_MOVE,
                        """{"vehicleId":"$vehicleId","steps":$steps}""",
                    )
                assertFalse(victory.isError, victory.text)
            }

            val victoryJson = json.parseToJsonElement(victory.text).jsonObject
            assertTrue(victoryJson["isSolved"]?.jsonPrimitive?.boolean == true)
            assertEquals(100.0, victoryJson["optimalityScore"]?.jsonPrimitive?.content?.toDouble())
            assertEquals(
                100.0,
                victoryJson["summary"]
                    ?.jsonObject
                    ?.get("efficiencyPercentage")
                    ?.jsonPrimitive
                    ?.content
                    ?.toDouble(),
            )

            val state = registryCore.invoke(RushHourMcpTools.TOOL_STATE, "{}")
            val stateJson = json.parseToJsonElement(state.text).jsonObject
            assertEquals(100.0, stateJson["optimalityScore"]?.jsonPrimitive?.content?.toDouble())
        }
}
