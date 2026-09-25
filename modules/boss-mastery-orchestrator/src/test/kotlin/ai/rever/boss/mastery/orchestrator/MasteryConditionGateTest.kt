package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.MasteryDefinition
import ai.rever.boss.ipc.proto.MasteryEdge
import ai.rever.boss.ipc.proto.MasteryNode
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.mastery.MasteryExecutor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #1060: MasteryEdge.condition is documented as "must be true for this edge to
 * be followed". The executor evaluates it (fail closed) and createMastery
 * validates the condition syntax instead of refusing the whole definition -
 * the blanket refusal was the earlier stopgap while no evaluator existed.
 * Malformed conditions are still refused loudly at creation time; that gate
 * is pinned in MasteryServiceConditionTest.
 */
class MasteryConditionGateTest {
    @Test
    fun `a definition with a conditioned edge passes the create-time condition gate`() =
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

            val created = service.createMastery(guarded)
            assertEquals(true, created.id.isNotBlank())
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
