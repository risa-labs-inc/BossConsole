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
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun `NodeSkipped survives the proto round trip with node id and reason intact`() =
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

            val skipped = events.filter { it.hasNodeSkipped() }.map { it.nodeSkipped }
            assertEquals(listOf("delete"), skipped.map { it.nodeId })
            assertEquals(
                "condition 'scan_clean == true' evaluated false",
                skipped.single().reason,
            )
            assertTrue(events.last().hasCompleted())
            assertEquals("completed", service.getMasteryStatus(execution(events)).state)
        }

    @Test
    fun `createMastery rejects a malformed edge condition loudly`() =
        runBlocking {
            val service = MasteryServiceImpl(MasteryExecutor(RecordingResolver(emptyMap())))
            val error =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(conditionalDefinition("scan_clean == true && confirmed == true"))
                }
            assertEquals(Status.INVALID_ARGUMENT.code, error.status.code)
            assertEquals(
                "Edge 'scan' -> 'delete' has an invalid condition: " +
                    "Malformed condition 'scan_clean == true && confirmed == true' (failing closed; " +
                    "supported forms: 'true', 'false', 'key', 'key == literal', 'key != literal'; " +
                    "a condition key is a bare key of the source node's output map, not the " +
                    "SOURCE_NODE.outputKey form used by inputMapping)",
                error.status.description,
            )
        }

    @Test
    fun `createMastery rejects a dotted input-mapping-style condition key`() =
        runBlocking<Unit> {
            // `scan.scan_clean` is the SOURCE_NODE.outputKey form
            // [MasteryNode.inputMapping] uses on this very edge, but a
            // condition reads a bare output key: the dotted token would
            // tokenize as one key and never match one at runtime, so it is
            // rejected up front with the reason that names the rule.
            val service = MasteryServiceImpl(MasteryExecutor(RecordingResolver(emptyMap())))
            val error =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(conditionalDefinition("scan.scan_clean == true"))
                }
            assertEquals(Status.INVALID_ARGUMENT.code, error.status.code)
            assertEquals(
                "Edge 'scan' -> 'delete' has an invalid condition: " +
                    "Malformed condition 'scan.scan_clean == true' (failing closed; " +
                    "supported forms: 'true', 'false', 'key', 'key == literal', 'key != literal'; " +
                    "a condition key is a bare key of the source node's output map, not the " +
                    "SOURCE_NODE.outputKey form used by inputMapping)",
                error.status.description,
            )
        }

    @Test
    fun `createMastery accepts well-formed and blank conditions`() =
        runBlocking {
            val service = MasteryServiceImpl(MasteryExecutor(RecordingResolver(emptyMap())))
            assertEquals("guarded", service.createMastery(conditionalDefinition("scan_clean == true")).id)
            assertEquals("guarded", service.createMastery(conditionalDefinition("scan_clean != dirty")).id)
            assertEquals("guarded", service.createMastery(conditionalDefinition(" ")).id)
        }

    /**
     * scan --(scan_clean == true)--> unlock <-- audit, with unlock declared
     * pure: it must admit on the single followed audit edge.
     */
    private fun pureFanInDefinition() =
        MasteryDefinition
            .newBuilder()
            .setId("fan-in-pure")
            .setName("Fan In Pure")
            .setDescription("unlock node admits on any followed edge")
            .addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("scan")
                    .setPluginId("plugin-a")
                    .setAction("scan"),
            ).addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("audit")
                    .setPluginId("plugin-b")
                    .setAction("audit"),
            ).addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("unlock")
                    .setPluginId("plugin-c")
                    .setAction("unlock")
                    .putInputMapping("cleanliness", "scan.scan_clean")
                    .putInputMapping("auditLog", "audit.audited")
                    .setPure(true),
            ).addEdges(
                MasteryEdge
                    .newBuilder()
                    .setFromNode("scan")
                    .setToNode("unlock")
                    .setOutputKey("scan_clean")
                    .setInputKey("cleanliness")
                    .setCondition("scan_clean == true"),
            ).addEdges(
                MasteryEdge
                    .newBuilder()
                    .setFromNode("audit")
                    .setToNode("unlock")
                    .setOutputKey("audited")
                    .setInputKey("auditLog"),
            ).build()

    @Test
    fun `a pure node declared through the proto keeps any-admit fan-in`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/audit" to mapOf("audited" to "yes"),
                        "plugin-c/unlock" to mapOf("unlocked" to "granted"),
                    ),
                )
            val service = MasteryServiceImpl(MasteryExecutor(resolver))
            service.createMastery(pureFanInDefinition())
            val events =
                service
                    .executeMastery(
                        ExecuteMasteryRequest.newBuilder().setMasteryId("fan-in-pure").build(),
                    ).toList()

            assertEquals(
                setOf("scan", "audit", "unlock"),
                events.filter { it.hasNodeStarted() }.map { it.nodeStarted.nodeId }.toSet(),
            )
            assertTrue(events.none { it.hasNodeSkipped() })
            val unlockInput = resolver.invocations.single { it.second == "unlock" }.third
            assertEquals("yes", unlockInput["auditLog"])
            assertFalse("cleanliness" in unlockInput)
            assertTrue(events.last().hasCompleted())
        }

    private fun execute() = ExecuteMasteryRequest.newBuilder().setMasteryId("guarded").build()

    private fun execution(events: List<MasteryProgress>) =
        MasteryExecutionId
            .newBuilder()
            .setExecutionId(events.first().executionId)
            .build()
}
