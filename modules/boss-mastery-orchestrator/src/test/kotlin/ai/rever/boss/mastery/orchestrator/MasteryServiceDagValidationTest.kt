package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.ExecuteMasteryRequest
import ai.rever.boss.ipc.proto.MasteryDefinition
import ai.rever.boss.ipc.proto.MasteryEdge
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

class MasteryServiceDagValidationTest {
    @Test
    fun `createMastery rejects edges that reference nonexistent endpoints`() =
        runTest {
            val service = service()
            val badTarget =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(
                        definition(nodes = listOf(node("a")), edges = listOf(edge("a", "ghost"))),
                    )
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, badTarget.status.code)
            assertTrue(
                badTarget.status.description
                    .orEmpty()
                    .contains("ghost"),
            )

            val badSource =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(
                        definition(nodes = listOf(node("a")), edges = listOf(edge("ghost", "a"))),
                    )
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, badSource.status.code)
            assertTrue(
                badSource.status.description
                    .orEmpty()
                    .contains("ghost"),
            )
        }

    @Test
    fun `createMastery rejects definitions that contain cycles`() =
        runTest {
            val service = service()
            val failure =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(
                        definition(
                            nodes = listOf(node("a"), node("b")),
                            edges = listOf(edge("a", "b"), edge("b", "a")),
                        ),
                    )
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
            assertTrue(
                failure.status.description
                    .orEmpty()
                    .contains("Cycle detected"),
            )
        }

    @Test
    fun `createMastery rejects duplicate node ids without reporting a cycle`() =
        runTest {
            val service = service()
            val failure =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(definition(nodes = listOf(node("dup"), node("dup"))))
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
            val description =
                failure.status.description
                    .orEmpty()
            assertTrue(description.contains("Duplicate"), description)
            assertTrue(!description.contains("Cycle"), description)
        }

    @Test
    fun `createMastery rejection text stays small when offender ids are huge`() =
        runTest {
            val service = service()
            // Node ids are caller-controlled and ride the percent-encoded grpc-message
            // trailer, so the description must stay far under the transport's header cap.
            val huge = "x".repeat(190)
            val failure =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(definition(nodes = (1..40).map { node(huge) }))
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
            val description = failure.status.description.orEmpty()
            assertTrue(description.contains("Duplicate"), description)
            assertTrue(description.length <= 260, "description was ${description.length} chars")
        }

    @Test
    fun `createMastery rejects node ids containing a dot`() =
        runTest {
            val service = service()
            val failure =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(definition(nodes = listOf(node("a.b"))))
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
            assertTrue(
                failure.status.description
                    .orEmpty()
                    .contains("'.'"),
            )
        }

    @Test
    fun `createMastery rejects a node named INPUT`() =
        runTest {
            val service = service()
            val failure =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(definition(nodes = listOf(node("INPUT"))))
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
            val description =
                failure.status.description
                    .orEmpty()
            assertTrue(description.contains("reserved"), description)
            assertTrue(description.contains("INPUT"), description)
        }

    @Test
    fun `createMastery rejects blank node ids`() =
        runTest {
            val service = service()
            val failure =
                assertFailsWith<StatusRuntimeException> {
                    service.createMastery(definition(nodes = listOf(node(""))))
                }
            assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
            assertTrue(
                failure.status.description
                    .orEmpty()
                    .contains("blank"),
            )
        }

    @Test
    fun `createMastery accepts a valid DAG including edges sourced from INPUT`() =
        runTest {
            val service = service()
            val created =
                service.createMastery(
                    definition(
                        nodes = listOf(node("a"), node("b")),
                        edges = listOf(edge("INPUT", "a"), edge("a", "b")),
                    ),
                )
            assertEquals("dag-test", created.id)
            val events =
                service
                    .executeMastery(
                        ExecuteMasteryRequest.newBuilder().setMasteryId("dag-test").build(),
                    ).toList()
            assertTrue(
                events
                    .last()
                    .hasCompleted(),
            )
        }

    private fun service(): MasteryServiceImpl {
        val resolver =
            object : CapabilityResolver {
                override suspend fun invoke(
                    pluginId: String,
                    action: String,
                    input: Map<String, String>,
                ): Map<String, String> = mapOf("result" to "ok")

                override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
            }
        return MasteryServiceImpl(MasteryExecutor(resolver))
    }

    private fun node(id: String): MasteryNode =
        MasteryNode
            .newBuilder()
            .setId(id)
            .setPluginId("plugin")
            .setAction("test")
            .build()

    private fun edge(
        from: String,
        to: String,
    ): MasteryEdge =
        MasteryEdge
            .newBuilder()
            .setFromNode(from)
            .setToNode(to)
            .build()

    private fun definition(
        nodes: List<MasteryNode>,
        edges: List<MasteryEdge> = emptyList(),
    ): MasteryDefinition =
        MasteryDefinition
            .newBuilder()
            .setId("dag-test")
            .addAllNodes(nodes)
            .addAllEdges(edges)
            .build()
}
