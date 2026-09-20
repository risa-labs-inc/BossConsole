package ai.rever.boss.mastery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Adversarial runtime documents for the mastery walk.
 *
 * The executor is the last line of defense for whatever a persistence seam
 * hands it: a document that was valid when it was saved, or is merely valid
 * per schema but hostile in shape, must be refused fail-closed at load time
 * with a MasteryProgress.Failed verdict and zero capability invocations.
 * Before the gate existed, cyclic documents killed the progress stream with
 * an uncaught exception, duplicate ids were misreported as cycles, dangling
 * target edges were silently ignored, and a node claiming the reserved
 * "INPUT" namespace executed and overwrote the caller's input map.
 */
class MasteryExecutorHostileDefinitionTest {
    /** Records every invocation so refusal tests can prove none happened. */
    private class RecordingResolver : CapabilityResolver {
        val invocations = mutableListOf<Triple<String, String, Map<String, String>>>()

        override suspend fun invoke(
            pluginId: String,
            action: String,
            input: Map<String, String>,
        ): Map<String, String> {
            invocations.add(Triple(pluginId, action, input))
            return mapOf("result" to "ok")
        }

        override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
    }

    @Test
    fun `a cyclic persisted definition fails with a verdict instead of killing the stream`() =
        runBlocking {
            val resolver = RecordingResolver()

            val events =
                MasteryExecutor(resolver)
                    .execute(
                        definition(
                            nodes = listOf(node("a"), node("b")),
                            edges = listOf(edge("a", "b"), edge("b", "a")),
                        ),
                        emptyMap(),
                    ).toList()

            assertEquals(2, events.size)
            assertIs<MasteryProgress.Started>(events[0])
            val failed = assertIs<MasteryProgress.Failed>(events[1])
            assertTrue(failed.error.contains("Cycle detected"), failed.error)
            assertEquals("", failed.failedNodeId)
            assertTrue(resolver.invocations.isEmpty(), "No node may run once a cycle is refused")
        }

    @Test
    fun `a node claiming the reserved INPUT namespace is refused before it can shadow input`() =
        runBlocking {
            val resolver = RecordingResolver()
            val hostile =
                definition(
                    nodes =
                        listOf(
                            node("INPUT"),
                            node("mid"),
                            node("last", inputMapping = mapOf("x" to "INPUT.secret")),
                        ),
                    edges = listOf(edge("mid", "last")),
                )

            val events =
                MasteryExecutor(resolver)
                    .execute(hostile, mapOf("secret" to "user-supplied"))
                    .toList()

            assertEquals(2, events.size)
            assertIs<MasteryProgress.Started>(events[0])
            val failed = assertIs<MasteryProgress.Failed>(events[1])
            assertTrue(failed.error.contains("reserved"), failed.error)
            assertTrue(failed.error.contains("INPUT"), failed.error)
            assertTrue(resolver.invocations.isEmpty(), "The forged INPUT node must never run")
        }

    @Test
    fun `duplicate node ids are refused precisely instead of being misreported as a cycle`() =
        runBlocking {
            val resolver = RecordingResolver()

            val events =
                MasteryExecutor(resolver)
                    .execute(
                        definition(nodes = listOf(node("dup"), node("dup"))),
                        emptyMap(),
                    ).toList()

            assertEquals(2, events.size)
            val failed = assertIs<MasteryProgress.Failed>(events[1])
            assertTrue(failed.error.contains("Duplicate"), failed.error)
            assertTrue(!failed.error.contains("Cycle"), failed.error)
            assertTrue(resolver.invocations.isEmpty(), "No node may run once duplicates are refused")
        }

    @Test
    fun `dangling edge endpoints are refused before any node runs`() =
        runBlocking {
            val resolver = RecordingResolver()

            val danglingTarget =
                MasteryExecutor(resolver)
                    .execute(
                        definition(nodes = listOf(node("a")), edges = listOf(edge("a", "ghost"))),
                        emptyMap(),
                    ).toList()
            val failedTarget = assertIs<MasteryProgress.Failed>(danglingTarget[1])
            assertTrue(failedTarget.error.contains("ghost"), failedTarget.error)
            assertTrue(
                failedTarget.error.contains("target"),
                failedTarget.error,
            )

            val danglingSource =
                MasteryExecutor(resolver)
                    .execute(
                        definition(nodes = listOf(node("a")), edges = listOf(edge("ghost", "a"))),
                        emptyMap(),
                    ).toList()
            val failedSource = assertIs<MasteryProgress.Failed>(danglingSource[1])
            assertTrue(failedSource.error.contains("ghost"), failedSource.error)
            assertTrue(
                failedSource.error.contains("source"),
                failedSource.error,
            )

            assertTrue(resolver.invocations.isEmpty(), "No node may run once endpoints are refused")
        }

    @Test
    fun `over-budget documents are refused at load time`() =
        runBlocking {
            val resolver = RecordingResolver()

            val tooManyNodes =
                MasteryExecutor(resolver)
                    .execute(
                        definition(nodes = (0..128).map { node("n$it") }),
                        emptyMap(),
                    ).toList()
            val nodeRefusal = assertIs<MasteryProgress.Failed>(tooManyNodes[1])
            assertTrue(nodeRefusal.error.contains("node budget"), nodeRefusal.error)

            val tooManyEdges =
                MasteryExecutor(resolver)
                    .execute(
                        definition(
                            nodes = listOf(node("a"), node("b"), node("c")),
                            edges = List(513) { edge("a", "b") },
                        ),
                        emptyMap(),
                    ).toList()
            val edgeRefusal = assertIs<MasteryProgress.Failed>(tooManyEdges[1])
            assertTrue(edgeRefusal.error.contains("edge budget"), edgeRefusal.error)

            assertTrue(resolver.invocations.isEmpty(), "No node may run once a budget is exceeded")
        }

    @Test
    fun `blank node ids are refused before any node runs`() =
        runBlocking {
            val resolver = RecordingResolver()

            val events =
                MasteryExecutor(resolver)
                    .execute(definition(nodes = listOf(node(""))), emptyMap())
                    .toList()

            assertEquals(2, events.size)
            val failed = assertIs<MasteryProgress.Failed>(events[1])
            assertTrue(failed.error.contains("blank"), failed.error)
            assertTrue(resolver.invocations.isEmpty(), "No node may run once a blank id is refused")
        }

    @Test
    fun `a well-formed definition with an INPUT-sourced edge still walks to completion`() =
        runBlocking {
            val resolver = RecordingResolver()

            val events =
                MasteryExecutor(resolver)
                    .execute(
                        definition(
                            nodes =
                                listOf(
                                    node("a", inputMapping = mapOf("k" to "INPUT.key")),
                                    node("b"),
                                ),
                            edges = listOf(edge("INPUT", "a"), edge("a", "b")),
                        ),
                        mapOf("key" to "value"),
                    ).toList()

            assertIs<MasteryProgress.Completed>(events.last())
            assertEquals(2, resolver.invocations.size)
        }

    private fun node(
        id: String,
        inputMapping: Map<String, String> = emptyMap(),
    ): MasteryNode =
        MasteryNode(
            id = id,
            pluginId = "plugin",
            action = "run",
            inputMapping = inputMapping,
        )

    private fun edge(
        from: String,
        to: String,
    ): MasteryEdge = MasteryEdge(fromNode = from, toNode = to, outputKey = "result", inputKey = "next")

    private fun definition(
        nodes: List<MasteryNode>,
        edges: List<MasteryEdge> = emptyList(),
    ): MasteryDefinition =
        MasteryDefinition(
            id = "hostile",
            name = "Hostile",
            description = "",
            nodes = nodes,
            edges = edges,
        )
}
