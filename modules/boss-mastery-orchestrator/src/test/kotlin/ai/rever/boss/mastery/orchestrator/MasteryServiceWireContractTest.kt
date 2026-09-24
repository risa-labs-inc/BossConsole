package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.ExecuteMasteryRequest
import ai.rever.boss.ipc.proto.ListMasteriesRequest
import ai.rever.boss.ipc.proto.MasteryDefinition
import ai.rever.boss.ipc.proto.MasteryEdge
import ai.rever.boss.ipc.proto.MasteryExecutionId
import ai.rever.boss.ipc.proto.MasteryId
import ai.rever.boss.ipc.proto.MasteryNode
import ai.rever.boss.ipc.proto.MasteryProgress
import ai.rever.boss.ipc.proto.MasteryStatus
import ai.rever.boss.ipc.proto.MasterySummary
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.mastery.MasteryExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-contract tests for the mastery RPC surface (#1154): the fields declared in
 * boss/ipc/v1/mastery.proto must carry real values end to end. Progress and status
 * messages are decoded again with parseFrom so the assertions cover the actual wire
 * representation, not just the in-memory builders.
 */
class MasteryServiceWireContractTest {
    @Test
    fun `progress events carry mastery name plugin action and node count on the wire`() =
        runTest {
            val service = service { mapOf("result" to "ok") }
            service.createMastery(definition("wire-mastery", "Wire Mastery"))

            val events = service.executeMastery(execute("wire-mastery")).toList()
            assertEquals(6, events.size)

            val started = MasteryProgress.parseFrom(events.first().toByteArray())
            assertTrue(started.hasStarted(), "first progress event must be MasteryStarted")
            assertEquals("Wire Mastery", started.started.masteryName)
            assertEquals("wire-mastery", started.started.masteryId)
            assertEquals(2, started.started.totalNodes)

            val nodeStarts = events.filter { it.hasNodeStarted() }.map { it.nodeStarted }
            assertEquals(listOf("node-a", "node-b"), nodeStarts.map { it.nodeId })
            assertEquals(listOf("plugin-a", "plugin-b"), nodeStarts.map { it.pluginId })
            assertEquals(listOf("fetch", "process"), nodeStarts.map { it.action })

            val completed = events.last()
            assertTrue(completed.hasCompleted())
            assertEquals(2, completed.completed.nodesExecuted)
            assertTrue(completed.completed.totalDurationMs >= 0)

            val decodedCompleted = MasteryProgress.parseFrom(completed.toByteArray())
            assertEquals(2, decodedCompleted.completed.nodesExecuted)
        }

    @Test
    fun `a recovered node reports its retry attempt on the wire`() =
        runTest {
            val calls = AtomicInteger()
            val service =
                service {
                    if (calls.incrementAndGet() == 1) {
                        error("first attempt fails")
                    }
                    mapOf("value" to "recovered")
                }
            service.createMastery(singleNodeDefinition("retry-mastery", 1))

            val events = service.executeMastery(execute("retry-mastery")).toList()

            val failure = events.single { it.hasNodeFailed() }
            assertEquals("only", failure.nodeFailed.nodeId)
            assertTrue(failure.nodeFailed.willRetry)
            assertEquals(1, failure.nodeFailed.retryAttempt)

            val decoded = MasteryProgress.parseFrom(failure.toByteArray())
            assertEquals(1, decoded.nodeFailed.retryAttempt)
            assertTrue(decoded.nodeFailed.willRetry)
            assertTrue(events.last().hasCompleted())
        }

    @Test
    fun `a failed mastery reports the failing node and total duration on the wire`() =
        runTest {
            val service = service { error("always fails") }
            service.createMastery(singleNodeDefinition("doomed", 1))

            val events = service.executeMastery(execute("doomed")).toList()

            val failures = events.filter { it.hasNodeFailed() }.map { it.nodeFailed }
            assertEquals(listOf(1, 2), failures.map { it.retryAttempt })
            assertEquals(listOf(true, false), failures.map { it.willRetry })

            val failed = events.last()
            assertTrue(failed.hasFailed())
            assertEquals("only", failed.failed.failedNodeId)
            assertEquals("always fails", failed.failed.errorMessage)
            assertTrue(failed.failed.totalDurationMs >= 0)

            val decoded = MasteryProgress.parseFrom(failed.toByteArray())
            assertEquals("only", decoded.failed.failedNodeId)
        }

    @Test
    fun `getMasteryStatus exposes per node states during and after the run`() =
        runBlocking {
            withTimeout(10_000) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val service =
                    service {
                        entered.complete(Unit)
                        release.await()
                        mapOf("value" to "done")
                    }
                service.createMastery(definition("status-mastery", "Status Mastery"))
                val executionId = CompletableDeferred<String>()
                val job =
                    launch {
                        service.executeMastery(execute("status-mastery")).collect { event ->
                            if (event.hasStarted()) executionId.complete(event.executionId)
                        }
                    }
                try {
                    entered.await() // node-a is mid-invoke, node-b has not started
                    val midRun =
                        service.getMasteryStatus(
                            MasteryExecutionId
                                .newBuilder()
                                .setExecutionId(executionId.await())
                                .build(),
                        )
                    assertEquals("running", midRun.state)
                    val running = midRun.nodeStatusesList.single { it.nodeId == "node-a" }
                    assertEquals("running", running.state)
                    assertTrue(running.startedAt > 0)
                    assertEquals(
                        "pending",
                        midRun.nodeStatusesList.single { it.nodeId == "node-b" }.state,
                    )
                } finally {
                    release.complete(Unit)
                    job.join()
                }
                val done =
                    service.getMasteryStatus(
                        MasteryExecutionId
                            .newBuilder()
                            .setExecutionId(executionId.await())
                            .build(),
                    )
                assertEquals("completed", done.state)
                assertEquals(
                    listOf("completed", "completed"),
                    done.nodeStatusesList.sortedBy { it.nodeId }.map { it.state },
                )
                val finishedNode = done.nodeStatusesList.single { it.nodeId == "node-a" }
                assertTrue(finishedNode.completedAt >= finishedNode.startedAt)
                assertEquals(2, MasteryStatus.parseFrom(done.toByteArray()).nodeStatusesCount)
            }
        }

    @Test
    fun `getMasteryStatus reports the failing node after a failed mastery`() =
        runTest {
            val service = service { error("node broke") }
            service.createMastery(singleNodeDefinition("broken", 0))
            val events = service.executeMastery(execute("broken")).toList()

            val status =
                service.getMasteryStatus(
                    MasteryExecutionId
                        .newBuilder()
                        .setExecutionId(events.first().executionId)
                        .build(),
                )
            assertEquals("failed", status.state)
            val node = status.nodeStatusesList.single()
            assertEquals("only", node.nodeId)
            assertEquals("failed", node.state)
            assertEquals("node broke", node.errorMessage)
        }

    @Test
    fun `listMasteries reports execution tallies per mastery`() =
        runTest {
            val service = service { mapOf("result" to "ok") }
            service.createMastery(definition("counted", "Counted"))
            service.createMastery(definition("fresh", "Fresh"))
            service.executeMastery(execute("counted")).toList()
            service.executeMastery(execute("counted")).toList()

            val summaries =
                service
                    .listMasteries(ListMasteriesRequest.newBuilder().build())
                    .masteriesList
                    .associateBy { it.id }
            val counted = summaries.getValue("counted")
            assertEquals(2, counted.executionCount)
            assertTrue(counted.lastExecutedAt > 0)
            val fresh = summaries.getValue("fresh")
            assertEquals(0, fresh.executionCount)
            assertEquals(0L, fresh.lastExecutedAt)

            val decoded = MasterySummary.parseFrom(counted.toByteArray())
            assertEquals(2, decoded.executionCount)
            assertTrue(decoded.lastExecutedAt > 0)

            service.deleteMastery(MasteryId.newBuilder().setId("counted").build())
            val remaining = service.listMasteries(ListMasteriesRequest.newBuilder().build())
            assertEquals(listOf("fresh"), remaining.masteriesList.map { it.id })
        }

    private fun service(invoke: suspend () -> Map<String, String>): MasteryServiceImpl {
        val resolver =
            object : CapabilityResolver {
                override suspend fun invoke(
                    pluginId: String,
                    action: String,
                    input: Map<String, String>,
                ) = invoke()

                override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
            }
        return MasteryServiceImpl(MasteryExecutor(resolver))
    }

    private fun definition(
        id: String,
        name: String,
    ): MasteryDefinition =
        MasteryDefinition
            .newBuilder()
            .setId(id)
            .setName(name)
            .addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("node-a")
                    .setPluginId("plugin-a")
                    .setAction("fetch")
                    .setDisplayName("Fetch"),
            ).addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("node-b")
                    .setPluginId("plugin-b")
                    .setAction("process"),
            ).addEdges(
                MasteryEdge
                    .newBuilder()
                    .setFromNode("node-a")
                    .setToNode("node-b")
                    .setOutputKey("result")
                    .setInputKey("content"),
            ).build()

    private fun singleNodeDefinition(
        id: String,
        maxRetries: Int,
    ): MasteryDefinition =
        MasteryDefinition
            .newBuilder()
            .setId(id)
            .setName("Single Node Mastery")
            .addNodes(
                MasteryNode
                    .newBuilder()
                    .setId("only")
                    .setPluginId("plugin")
                    .setAction("act")
                    .setMaxRetries(maxRetries),
            ).build()

    private fun execute(masteryId: String) = ExecuteMasteryRequest.newBuilder().setMasteryId(masteryId).build()
}
