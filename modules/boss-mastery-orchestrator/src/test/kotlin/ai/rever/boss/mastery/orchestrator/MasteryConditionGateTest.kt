package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.MasteryDefinition
import ai.rever.boss.ipc.proto.MasteryEdge
import ai.rever.boss.ipc.proto.MasteryNode
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.mastery.MasteryExecutor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #1060: MasteryEdge.condition is documented as "must be true for this edge to
 * be followed" but the executor resolves dependencies purely topologically and
 * never evaluates it - a conditioned edge would run its guarded node
 * unconditionally. Until an expression language exists, createMastery refuses
 * definitions carrying a condition instead of silently violating the schema
 * contract.
 */
class MasteryConditionGateTest {
    @Test
    fun `a definition with a conditioned edge is refused at create time`() =
        runBlocking {
            val guarded =
                MasteryDefinition
                    .newBuilder()
                    .setId("guarded")
                    .addNodes(
                        MasteryNode
                            .newBuilder()
                            .setId("scan")
                            .setPluginId("p")
                            .setAction("scan"),
                    ).addNodes(
                        MasteryNode
                            .newBuilder()
                            .setId("delete")
                            .setPluginId("p")
                            .setAction("delete"),
                    ).addEdges(
                        MasteryEdge
                            .newBuilder()
                            .setFromNode("scan")
                            .setToNode("delete")
                            .setCondition("ok"),
                    ).build()

            val service = MasteryServiceImpl(MasteryExecutor(RecordingResolver()))

            val ex = assertFailsWith<StatusRuntimeException> { service.createMastery(guarded) }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
            assertEquals(true, ex.status.description!!.contains("condition"))
        }

    @Test
    fun `a definition whose edges carry no condition still passes the gate`() =
        runBlocking {
            val plain =
                MasteryDefinition
                    .newBuilder()
                    .setId("plain")
                    .addNodes(
                        MasteryNode
                            .newBuilder()
                            .setId("node")
                            .setPluginId("p")
                            .setAction("test"),
                    ).addEdges(MasteryEdge.newBuilder().setFromNode("INPUT").setToNode("node"))
                    .build()

            val service = MasteryServiceImpl(MasteryExecutor(RecordingResolver()))
            val id = service.createMastery(plain)
            assertEquals(true, id.id.isNotBlank())
        }

    private class RecordingResolver : CapabilityResolver {
        override suspend fun invoke(
            pluginId: String,
            action: String,
            input: Map<String, String>,
        ): Map<String, String> = emptyMap()

        override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
    }
}
