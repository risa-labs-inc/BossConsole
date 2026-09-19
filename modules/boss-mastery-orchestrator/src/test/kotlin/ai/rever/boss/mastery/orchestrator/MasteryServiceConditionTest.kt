package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.ExecuteMasteryRequest
import ai.rever.boss.ipc.proto.MasteryDefinition
import ai.rever.boss.ipc.proto.MasteryEdge
import ai.rever.boss.ipc.proto.MasteryExecutionId
import ai.rever.boss.ipc.proto.MasteryNode
import ai.rever.boss.ipc.proto.MasteryProgress
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.mastery.MasteryExecutor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins #1060 end to end: a condition submitted through the proto wire survives
 * [MasteryServiceImpl]'s conversion into the SDK definition and is evaluated
 * by the executor with the values the definition carried. Before the fix the
 * field was transported but never read, so the guarded node always executed.
 */
class MasteryServiceConditionTest {
    /** Records every invocation and returns canned responses keyed by "pluginId/action". */
    private class RecordingResolver(
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

    private fun conditionalDefinition(condition: String) =
        MasteryDefinition
            .newBuilder()
            .setId("guarded")
            .setName("Guarded")
            .setDescription("scan then conditionally delete")
            .addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("scan")
                    .setPluginId("plugin-a")
                    .setAction("scan"),
            ).addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("delete")
                    .setPluginId("plugin-b")
                    .setAction("delete")
                    .putInputMapping("target", "scan.scan_clean"),
            ).addEdges(
                MasteryEdge
                    .newBuilder()
                    .setFromNode("scan")
                    .setToNode("delete")
                    .setOutputKey("scan_clean")
                    .setInputKey("target")
                    .setCondition(condition),
            ).build()

    @Test
    fun `false condition carried through the proto blocks the guarded node`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/delete" to mapOf("deleted" to "quarantine"),
                    ),
                )
            val service = MasteryServiceImpl(MasteryExecutor(resolver))
            service.createMastery(conditionalDefinition("scan_clean == true"))
            val events = service.executeMastery(execute()).toList()

            assertEquals(
                listOf("scan"),
                events.filter { it.hasNodeStarted() }.map { it.nodeStarted.nodeId },
            )
            assertEquals(1, resolver.invocations.size)
            assertTrue(events.last().hasCompleted())
            assertEquals("completed", service.getMasteryStatus(execution(events)).state)
        }

    @Test
    fun `true condition carried through the proto allows the guarded node`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "true"),
                        "plugin-b/delete" to mapOf("deleted" to "quarantine"),
                    ),
                )
            val service = MasteryServiceImpl(MasteryExecutor(resolver))
            service.createMastery(conditionalDefinition("scan_clean == true"))
            val events = service.executeMastery(execute()).toList()

            assertEquals(
                listOf("scan", "delete"),
                events.filter { it.hasNodeStarted() }.map { it.nodeStarted.nodeId },
            )
            assertEquals(
                "true",
                resolver.invocations.single { it.second == "delete" }.third["target"],
            )
            assertTrue(events.last().hasCompleted())
            assertEquals("quarantine", events.last().completed.outputMap["deleted"])
        }

    private fun execute() = ExecuteMasteryRequest.newBuilder().setMasteryId("guarded").build()

    private fun execution(events: List<MasteryProgress>) =
        MasteryExecutionId
            .newBuilder()
            .setExecutionId(events.first().executionId)
            .build()
}
