package ai.rever.boss.mastery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MasteryCheckpointTest {
    private class MockResolver(
        private val responses: Map<String, Map<String, String>>,
    ) : CapabilityResolver {
        val invocations = mutableListOf<Triple<String, String, Map<String, String>>>()

        override suspend fun invoke(
            pluginId: String,
            action: String,
            input: Map<String, String>,
        ): Map<String, String> {
            invocations.add(Triple(pluginId, action, input))
            return responses["$pluginId/$action"] ?: emptyMap()
        }

        override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
    }

    @Test
    fun `executor saves checkpoints to execution store`() = runBlocking {
        val resolver =
            MockResolver(
                mapOf(
                    "plugin-a/fetch" to mapOf("out1" to "v1"),
                    "plugin-b/process" to mapOf("out2" to "v2"),
                ),
            )

        val store = InMemoryExecutionStore()
        val executor = MasteryExecutor(resolver, store)

        val mastery =
            MasteryDefinition(
                id = "test-checkpoint",
                name = "Test Checkpoint",
                description = "",
                nodes = listOf(
                    MasteryNode(id = "n1", pluginId = "plugin-a", action = "fetch"),
                    MasteryNode(id = "n2", pluginId = "plugin-b", action = "process"),
                ),
                edges = listOf(MasteryEdge(fromNode = "n1", toNode = "n2", outputKey = "out1", inputKey = "in1")),
            )

        val executionId = java.util.UUID.randomUUID().toString()

        val events = executor.execute(mastery, emptyMap(), executionId).toList()

        // Ensure execution completed
        val completed = events.find { it is MasteryProgress.Completed }
        assertNotNull(completed, "Expected execution to complete")

        // Check store for checkpoints
        val checkpoints = store.getCheckpoints(executionId)
        assertEquals(2, checkpoints.size, "Expected two checkpoints for two nodes")

        val cp1 = checkpoints.find { it.nodeId == "n1" }
        val cp2 = checkpoints.find { it.nodeId == "n2" }

        assertNotNull(cp1)
        assertNotNull(cp2)

        // timestamps and attempt numbers
        assert(cp1.startedAt > 0L && cp1.completedAt >= cp1.startedAt)
        assert(cp2.startedAt > 0L && cp2.completedAt >= cp2.startedAt)
        assertEquals(1, cp1.attempt)
        assertEquals(1, cp2.attempt)
    }
}
