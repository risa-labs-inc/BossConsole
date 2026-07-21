package ai.rever.boss.mastery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class MasteryExecutorTest {

    /** Simple mock that returns predefined responses keyed by "pluginId/action". */
    private class MockResolver(
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

    @Test
    fun `simple two-node sequential mastery executes both nodes in order`() = runBlocking {
        val resolver = MockResolver(
            mapOf(
                "plugin-a/fetch" to mapOf("url_content" to "hello"),
                "plugin-b/process" to mapOf("result" to "world"),
            )
        )
        val executor = MasteryExecutor(resolver)
        val mastery = MasteryDefinition(
            id = "seq-mastery",
            name = "Sequential",
            description = "",
            nodes = listOf(
                MasteryNode("node1", "plugin-a", "fetch"),
                MasteryNode("node2", "plugin-b", "process"),
            ),
            edges = listOf(
                MasteryEdge("node1", "node2", "url_content", "raw_content"),
            ),
        )

        val events = executor.execute(mastery, mapOf("url" to "http://example.com")).toList()

        assertEquals(6, events.size)
        assertIs<MasteryProgress.Started>(events[0])
        assertIs<MasteryProgress.NodeStarted>(events[1])
        assertIs<MasteryProgress.NodeCompleted>(events[2])
        assertIs<MasteryProgress.NodeStarted>(events[3])
        assertIs<MasteryProgress.NodeCompleted>(events[4])
        assertIs<MasteryProgress.Completed>(events[5])
        assertEquals(2, resolver.invocations.size)
    }

    @Test
    fun `data passes between nodes via inputMapping`() = runBlocking {
        val resolver = MockResolver(
            mapOf(
                "plugin-a/generate" to mapOf("text" to "generated text"),
                "plugin-b/summarize" to mapOf("summary" to "short"),
            )
        )
        val executor = MasteryExecutor(resolver)
        val mastery = MasteryDefinition(
            id = "data-passing",
            name = "Data Passing",
            description = "",
            nodes = listOf(
                MasteryNode("gen", "plugin-a", "generate"),
                MasteryNode(
                    id = "sum",
                    pluginId = "plugin-b",
                    action = "summarize",
                    inputMapping = mapOf("content" to "gen.text"),
                ),
            ),
            edges = listOf(
                MasteryEdge("gen", "sum", "text", "content"),
            ),
        )

        executor.execute(mastery, emptyMap()).toList()

        val sumCall = resolver.invocations.find { it.first == "plugin-b" && it.second == "summarize" }
        assertNotNull(sumCall, "plugin-b/summarize was not invoked")
        assertEquals("generated text", sumCall.third["content"],
            "Expected 'content' input to be forwarded from gen.text")
    }

    @Test
    fun `progress events emitted in correct order for single node`() = runBlocking {
        val resolver = MockResolver(
            mapOf("plugin/action" to mapOf("out" to "value"))
        )
        val executor = MasteryExecutor(resolver)
        val mastery = MasteryDefinition(
            id = "single",
            name = "Single Node",
            description = "",
            nodes = listOf(
                MasteryNode("n1", "plugin", "action", displayName = "My Node"),
            ),
            edges = emptyList(),
        )

        val events = executor.execute(mastery, emptyMap()).toList()

        assertEquals(4, events.size)

        val started = events[0] as MasteryProgress.Started
        assertEquals("single", started.masteryId)
        assertEquals(1, started.totalNodes)

        val nodeStarted = events[1] as MasteryProgress.NodeStarted
        assertEquals("n1", nodeStarted.nodeId)
        assertEquals("My Node", nodeStarted.displayName)

        val nodeCompleted = events[2] as MasteryProgress.NodeCompleted
        assertEquals("n1", nodeCompleted.nodeId)

        val completed = events[3] as MasteryProgress.Completed
        assertEquals("value", completed.output["out"])
    }

    @Test
    fun `INPUT data available to first node via inputMapping`() = runBlocking {
        val resolver = MockResolver(
            mapOf("fetcher/run" to mapOf("body" to "ok"))
        )
        val executor = MasteryExecutor(resolver)
        val mastery = MasteryDefinition(
            id = "input-test",
            name = "Input Test",
            description = "",
            nodes = listOf(
                MasteryNode(
                    id = "n1",
                    pluginId = "fetcher",
                    action = "run",
                    inputMapping = mapOf("target_url" to "INPUT.url"),
                ),
            ),
            edges = emptyList(),
        )

        executor.execute(mastery, mapOf("url" to "https://boss.ai")).toList()

        val call = resolver.invocations.first()
        assertEquals("https://boss.ai", call.third["target_url"],
            "Expected INPUT.url to be forwarded as target_url")
    }
}
