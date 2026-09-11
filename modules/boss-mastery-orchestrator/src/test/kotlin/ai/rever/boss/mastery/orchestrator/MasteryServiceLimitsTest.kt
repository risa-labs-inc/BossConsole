package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.ExecuteMasteryRequest
import ai.rever.boss.ipc.proto.MasteryDefinition
import ai.rever.boss.ipc.proto.MasteryExecutionId
import ai.rever.boss.ipc.proto.MasteryId
import ai.rever.boss.ipc.proto.MasteryNode
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.mastery.MasteryExecutor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MasteryServiceLimitsTest {
    @Test
    fun `node timeout is a failure and releases execution capacity`() =
        runTest {
            val service = service { awaitCancellation() }
            val timed = definition("one").toBuilder()
            timed.setNodes(0, timed.getNodes(0).toBuilder().setTimeoutMs(10))
            service.createMastery(timed.build())
            var executionId = ""
            assertFailsWith<TimeoutCancellationException> {
                service.executeMastery(execute()).collect { executionId = it.executionId }
            }
            assertEquals("failed", service.getMasteryStatus(execution(executionId)).state)
            service.createMastery(MasteryDefinition.newBuilder().setId("one").build())
            assertTrue(
                service
                    .executeMastery(execute())
                    .toList()
                    .last()
                    .hasCompleted(),
            )
        }

    @Test
    fun `definitions refuse new entries at capacity while replacement and deletion work`() =
        runTest {
            val service = service(definitions = 1)
            service.createMastery(definition("one"))
            service.createMastery(definition("one"))
            val failure = assertFailsWith<StatusRuntimeException> { service.createMastery(definition("two")) }
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, failure.status.code)
            service.deleteMastery(MasteryId.newBuilder().setId("one").build())
            service.createMastery(definition("two"))
        }

    @Test
    fun `completed history is bounded and legitimate executions still finish`() =
        runTest {
            val service = service()
            service.createMastery(definition("one"))
            val ids =
                (1..4).map {
                    service
                        .executeMastery(execute())
                        .toList()
                        .first()
                        .executionId
                }
            assertEquals("unknown", service.getMasteryStatus(execution(ids.first())).state)
            assertEquals("completed", service.getMasteryStatus(execution(ids.last())).state)
        }

    @Test
    fun `cancellation holds capacity until the running operation settles`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val service =
                service {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cancelling.complete(Unit)
                            release.await()
                        }
                    }
                }
            service.createMastery(definition("one"))
            val id = CompletableDeferred<String>()
            val running = launch { service.executeMastery(execute()).collect { id.complete(it.executionId) } }
            try {
                entered.await()
                assertTrue(service.cancelMastery(execution(id.await())).success)
                cancelling.await()
                val failure = assertFailsWith<StatusRuntimeException> { service.executeMastery(execute()).collect() }
                assertEquals(Status.Code.RESOURCE_EXHAUSTED, failure.status.code)
            } finally {
                release.complete(Unit)
                running.join()
            }
            assertEquals("cancelled", service.getMasteryStatus(execution(id.await())).state)
            service.createMastery(MasteryDefinition.newBuilder().setId("empty").build())
            assertTrue(
                service
                    .executeMastery(execute().toBuilder().setMasteryId("empty").build())
                    .toList()
                    .last()
                    .hasCompleted(),
            )
        }

    @Test
    fun `oversized definitions and excessive retry counts are refused`() =
        runTest {
            val service = service()
            assertFailsWith<StatusRuntimeException> {
                service.createMastery(definition("one").toBuilder().setInputSchemaJson("x".repeat(65_537)).build())
            }
            assertFailsWith<IllegalArgumentException> {
                service.createMastery(
                    definition("one")
                        .toBuilder()
                        .setNodes(0, MasteryNode.newBuilder().setMaxRetries(Int.MAX_VALUE))
                        .build(),
                )
            }
        }

    private fun service(
        definitions: Int = 3,
        invoke: suspend () -> Map<String, String> = { mapOf("result" to "ok") },
    ): MasteryServiceImpl {
        val resolver =
            object : CapabilityResolver {
                override suspend fun invoke(
                    pluginId: String,
                    action: String,
                    input: Map<String, String>,
                ) = invoke()

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

    private fun execute() = ExecuteMasteryRequest.newBuilder().setMasteryId("one").build()

    private fun execution(id: String) = MasteryExecutionId.newBuilder().setExecutionId(id).build()
}
