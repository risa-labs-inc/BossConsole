package ai.rever.boss.mastery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Adversarial load-seam coverage: persisted definitions are re-read as trusted data, so the
 * executor must refuse hostile-but-schema-valid documents with a [MasteryProgress.Failed]
 * verdict, a surviving stream, and zero capability invocations — instead of killing the stream
 * on an uncaught sort error or silently walking a forged DAG.
 */
class MasteryExecutorPreflightTest {
    /** Records every capability invocation; hostile definitions must leave it empty. */
    private class RecordingResolver : CapabilityResolver {
        val invocations = mutableListOf<Pair<String, String>>()

        override suspend fun invoke(
            pluginId: String,
            action: String,
            input: Map<String, String>,
        ): Map<String, String> {
            invocations.add(pluginId to action)
            return emptyMap()
        }

        override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
    }

    private fun definition(
        id: String,
        nodes: List<MasteryNode>,
        edges: List<MasteryEdge> = emptyList(),
    ): MasteryDefinition = MasteryDefinition(id = id, name = id, description = "", nodes = nodes, edges = edges)

    private suspend fun refuse(
        resolver: RecordingResolver,
        mastery: MasteryDefinition,
    ): MasteryProgress.Failed {
        val executor = MasteryExecutor(resolver)
        val events = executor.execute(mastery, mapOf("url" to "https://example.com")).toList()
        assertEquals(1, events.size, "expected a single Failed verdict, got: $events")
        return assertIs<MasteryProgress.Failed>(events.single())
    }

    @Test
    fun `a cyclic definition is refused with a verdict instead of killing the stream`() =
        runBlocking {
            val resolver = RecordingResolver()
            val mastery =
                definition(
                    "cycle-mastery",
                    listOf(
                        MasteryNode("first", "plugin-a", "act"),
                        MasteryNode("second", "plugin-b", "act"),
                    ),
                    listOf(
                        MasteryEdge("first", "second", "out", "in"),
                        MasteryEdge("second", "first", "out", "in"),
                    ),
                )
            val failed = refuse(resolver, mastery)
            assertTrue("Cycle" in failed.error, "expected a cycle diagnosis, was: ${failed.error}")
            assertEquals("cycle-mastery", failed.failedNodeId)
            assertTrue(resolver.invocations.isEmpty())
        }

    @Test
    fun `duplicate node ids are refused as duplicates rather than misreported as a cycle`() =
        runBlocking {
            val resolver = RecordingResolver()
            val mastery =
                definition(
                    "duplicate-mastery",
                    listOf(
                        MasteryNode("dup", "plugin-a", "act"),
                        MasteryNode("dup", "plugin-b", "act"),
                    ),
                )
            val failed = refuse(resolver, mastery)
            assertTrue("Duplicate node id 'dup'" in failed.error)
            assertFalse("Cycle" in failed.error)
            assertTrue(resolver.invocations.isEmpty())
        }

    @Test
    fun `blank node ids are refused before any capability invocation`() =
        runBlocking {
            val resolver = RecordingResolver()
            val mastery = definition("blank-mastery", listOf(MasteryNode(" ", "plugin-a", "act")))
            val failed = refuse(resolver, mastery)
            assertTrue("blank" in failed.error)
            assertTrue(resolver.invocations.isEmpty())
        }

    @Test
    fun `a node claiming the reserved INPUT id is refused before it can shadow caller input`() =
        runBlocking {
            val resolver = RecordingResolver()
            val mastery =
                definition(
                    "input-mastery",
                    listOf(
                        MasteryNode("INPUT", "plugin-a", "act"),
                        MasteryNode(
                            id = "consumer",
                            pluginId = "plugin-b",
                            action = "act",
                            inputMapping = mapOf("raw" to "INPUT.value"),
                        ),
                    ),
                    listOf(MasteryEdge("INPUT", "consumer", "value", "raw")),
                )
            val failed = refuse(resolver, mastery)
            assertTrue("reserved" in failed.error)
            assertTrue(resolver.invocations.isEmpty())
        }

    @Test
    fun `dangling edge endpoints are refused instead of being silently executed or ignored`() =
        runBlocking {
            val resolver = RecordingResolver()
            val danglingSource =
                definition(
                    "dangling-source",
                    listOf(MasteryNode("only", "plugin-a", "act")),
                    listOf(MasteryEdge("ghost", "only", "out", "in")),
                )
            assertTrue("Edge source 'ghost'" in refuse(resolver, danglingSource).error)
            val danglingTarget =
                definition(
                    "dangling-target",
                    listOf(MasteryNode("only", "plugin-a", "act")),
                    listOf(MasteryEdge("only", "phantom", "out", "in")),
                )
            assertTrue("Edge target 'phantom'" in refuse(resolver, danglingTarget).error)
            assertTrue(resolver.invocations.isEmpty())
        }

    @Test
    fun `node and edge budgets mirror the persistence caps and are enforced before the walk`() =
        runBlocking {
            val resolver = RecordingResolver()
            val oversizedNodes = (1..129).map { MasteryNode("node-$it", "plugin-a", "act") }
            val tooManyNodes = refuse(resolver, definition("too-many-nodes", oversizedNodes))
            assertTrue("128 nodes" in tooManyNodes.error)
            val oversizedEdges = (1..513).map { MasteryEdge("first", "second", "out", "in") }
            val chainedEdges =
                definition(
                    "too-many-edges",
                    listOf(
                        MasteryNode("first", "plugin-a", "act"),
                        MasteryNode("second", "plugin-b", "act"),
                    ),
                    oversizedEdges,
                )
            assertTrue("512 edges" in refuse(resolver, chainedEdges).error)
            assertTrue(resolver.invocations.isEmpty())
        }
}
