package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.ExecuteMasteryRequest
import ai.rever.boss.ipc.proto.GenerateMasteryRequest
import ai.rever.boss.ipc.proto.ListMasteriesRequest
import ai.rever.boss.ipc.proto.MasteryDefinition
import ai.rever.boss.ipc.proto.MasteryId
import ai.rever.boss.ipc.proto.MasteryNode
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.mastery.MasteryExecutor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenerateMasteryTest {
    @Test
    fun `generate mastery is unimplemented instead of minting a dead id`() =
        runTest {
            val service = service()
            val failure =
                assertFailsWith<StatusRuntimeException> {
                    service.generateMastery(request("Deploy the marketing site"))
                }
            assertEquals(Status.Code.UNIMPLEMENTED, failure.status.code)
            assertTrue(
                failure.status.description
                    .orEmpty()
                    .contains("CreateMastery"),
            )
        }

    @Test
    fun `a rejected generation never occupies definition storage`() =
        runTest {
            val service = service(definitions = 1)
            service.createMastery(definition("one"))
            val rejected =
                assertFailsWith<StatusRuntimeException> {
                    service.generateMastery(request("Automate anything"))
                }
            assertEquals(Status.Code.UNIMPLEMENTED, rejected.status.code)
            val listing = service.listMasteries(ListMasteriesRequest.newBuilder().build())
            assertEquals(1, listing.totalCount)
            assertEquals("one", listing.getMasteries(0).id)
            val full =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(definition("two"))
                }
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, full.status.code)
            service.deleteMastery(MasteryId.newBuilder().setId("one").build())
            service.createMastery(definition("two"))
        }

    @Test
    fun `the create then execute round-trip still resolves`() =
        runTest {
            val service = service()
            val id = service.createMastery(definition("authored")).id
            val events =
                service
                    .executeMastery(execute(id))
                    .toList()
            assertEquals(id, events.first().getStarted().masteryId)
            assertTrue(events.last().hasCompleted())
        }

    private fun service(definitions: Int = 3): MasteryServiceImpl {
        val resolver =
            object : CapabilityResolver {
                override suspend fun invoke(
                    pluginId: String,
                    action: String,
                    input: Map<String, String>,
                ): Map<String, String> = mapOf("result" to "ok")

                override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
            }
        return MasteryServiceImpl(MasteryExecutor(resolver), definitions, 1, 2)
    }

    private fun definition(id: String) =
        MasteryDefinition
            .newBuilder()
            .setId(id)
            .addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("node")
                    .setPluginId("plugin")
                    .setAction("test"),
            ).build()

    private fun request(task: String) =
        GenerateMasteryRequest
            .newBuilder()
            .setTaskDescription(task)
            .build()

    private fun execute(id: String) = ExecuteMasteryRequest.newBuilder().setMasteryId(id).build()
}
