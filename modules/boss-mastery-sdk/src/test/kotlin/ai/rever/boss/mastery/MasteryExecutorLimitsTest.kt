package ai.rever.boss.mastery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MasteryExecutorLimitsTest {
    @Test
    fun `parallel level admits at most eight nodes and resumes waiting work`() =
        runBlocking {
            withTimeout(10_000) {
                val entered = AtomicInteger()
                val active = AtomicInteger()
                val peak = AtomicInteger()
                val release = CompletableDeferred<Unit>()
                val executor =
                    executor {
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        entered.incrementAndGet()
                        try {
                            release.await()
                            mapOf("ok" to "yes")
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                val running = async(Dispatchers.Default) { executor.execute(definition(20), emptyMap()).toList() }
                try {
                    while (entered.get() < 8) delay(10)
                    delay(50)
                    assertEquals(8, entered.get())
                } finally {
                    release.complete(Unit)
                }
                assertIs<MasteryProgress.Completed>(running.await().last())
                assertEquals(20, entered.get())
                assertTrue(peak.get() <= 8)
            }
        }

    @Test
    fun `oversized output is refused before progress retains it`() =
        runBlocking {
            val calls = AtomicInteger()
            val executor =
                executor {
                    calls.incrementAndGet()
                    mapOf("value" to "x".repeat(262_145))
                }
            val definition = definition(1).let { it.copy(nodes = it.nodes.map { node -> node.copy(maxRetries = 5) }) }
            val events = executor.execute(definition, emptyMap()).toList()
            assertIs<MasteryProgress.Failed>(events.last())
            assertFalse(events.any { it is MasteryProgress.NodeCompleted })
            assertEquals(1, calls.get())
        }

    @Test
    fun `many individually valid outputs cannot exceed the execution retention budget`() =
        runBlocking {
            val executor = executor { mapOf("value" to "x".repeat(250_000)) }
            val events = executor.execute(definition(20), emptyMap()).toList()
            assertIs<MasteryProgress.Failed>(events.last())
            val retained =
                events
                    .filterIsInstance<MasteryProgress.NodeCompleted>()
                    .sumOf { event ->
                        event.output.entries.sumOf { (key, value) -> key.length.toLong() + value.length }
                    }
            assertTrue(retained <= 2_097_152)
        }

    private fun executor(invoke: suspend () -> Map<String, String>) =
        MasteryExecutor(
            object : CapabilityResolver {
                override suspend fun invoke(
                    pluginId: String,
                    action: String,
                    input: Map<String, String>,
                ) = invoke()

                override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
            },
        )

    private fun definition(count: Int) =
        MasteryDefinition(
            id = "limits",
            name = "Limits",
            description = "",
            nodes = (1..count).map { MasteryNode("node-$it", "plugin", "action") },
            edges = emptyList(),
        )
}
