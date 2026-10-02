package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginUnloadIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The question asked before a depended-upon plugin is unloaded, and the bookkeeping that
 * restarts its dependents afterwards.
 *
 * Both halves are pure so they can be pinned without a plugin loader, a window or a store.
 * That matters more here than for most of this subsystem: the failure modes are a dialog that
 * never appears and an unload that waits forever, neither of which shows up in a screenshot.
 */
class DependentRestartTest {
    private fun dependent(
        pluginId: String,
        displayName: String = pluginId,
        optional: Boolean = false,
        loadPriority: Int = 100,
    ) = DependentPlugin(
        pluginId = pluginId,
        displayName = displayName,
        optional = optional,
        loadPriority = loadPriority,
    )

    private fun prompt(
        targetPluginId: String = "com.example.gateway",
        intent: PluginUnloadIntent = PluginUnloadIntent.UPDATE,
        dependents: List<DependentPlugin> = listOf(dependent("com.example.flow", "Flow")),
    ) = DependentRestartPrompt(
        targetPluginId = targetPluginId,
        targetDisplayName = "AI Gateway",
        intent = intent,
        dependents = dependents,
    )

    @BeforeTest
    @AfterTest
    fun resetPending() {
        // An object, so its map outlives a test the way it outlives an unload. Two tests
        // sharing a record is the same coupling two windows would have.
        PendingDependentRestarts.clear()
    }

    /**
     * Runs [block] with the coordinator's restart deadline shortened and its restart hook
     * collecting into [restarted], restoring both afterwards.
     *
     * Real time, not virtual: the timer deliberately runs on a scope that outlives its caller,
     * which is the property being tested, so a `TestScope` would test something else. The
     * deadline is shortened instead - a real 60s wait per test is not worth the fidelity.
     */
    private fun withShortDeadline(
        restarted: MutableList<String>,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val previousHook = DependentRestartCoordinator.restartPlugin
        val previousDeadline = DependentRestartCoordinator.restartDeadlineMs
        DependentRestartCoordinator.restartPlugin = { id -> restarted += id }
        DependentRestartCoordinator.restartDeadlineMs = SHORT_DEADLINE_MS
        try {
            runBlocking { block() }
        } finally {
            DependentRestartCoordinator.restartPlugin = previousHook
            DependentRestartCoordinator.restartDeadlineMs = previousDeadline
        }
    }

    private companion object {
        const val SHORT_DEADLINE_MS = 100L

        /** Generous: this waits on real wall-clock work, so it must not be flaky under load. */
        const val TEST_TIMEOUT_MS = 10_000L
    }

    // ---- the bus ----

    @Test
    fun `no dependents means no question`() =
        runTest {
            val bus = DependentRestartBus()

            // The overwhelmingly common unload. It must not wait on a dialog that would never
            // be shown, and it must not consume a buffer slot on the way past.
            assertTrue(bus.ask(prompt(dependents = emptyList())))
        }

    @Test
    fun `the answer the collector gives is what ask returns`() =
        runTest {
            val bus = DependentRestartBus()
            val pending = prompt()

            val asked = async { bus.ask(pending) }
            runCurrent()
            bus.restartPrompts
                .first()
                .answer
                .complete(true)

            assertTrue(asked.await())
        }

    @Test
    fun `a decline comes back as false`() =
        runTest {
            val bus = DependentRestartBus()
            val pending = prompt()

            val asked = async { bus.ask(pending) }
            runCurrent()
            bus.restartPrompts
                .first()
                .answer
                .complete(false)

            assertFalse(asked.await())
        }

    @Test
    fun `a prompt nobody ever answers is refused rather than waited on forever`() =
        runTest {
            val bus = DependentRestartBus()

            // The window closed with the dialog open: the collector is gone and nothing will
            // ever complete the deferred. Without the timeout the caller - the Toolbox's own
            // update coroutine - would wait for the life of the process.
            val asked = async { bus.ask(prompt()) }
            advanceTimeBy(DependentRestartBus.ANSWER_TIMEOUT_MS + 1)
            advanceUntilIdle()

            assertFalse(asked.await())
        }

    @Test
    fun `a second question waits its turn instead of replacing the first`() =
        runTest {
            val bus = DependentRestartBus()
            val first = prompt(targetPluginId = "com.example.gateway")
            val second = prompt(targetPluginId = "com.example.other")

            val firstAsk = async { bus.ask(first) }
            val secondAsk = async { bus.ask(second) }
            runCurrent()

            // Both are buffered; the collector sees them in order and each carries its own
            // answer, so answering one cannot answer the other.
            val delivered = mutableListOf<String>()
            bus.restartPrompts
                .first { received ->
                    delivered += received.targetPluginId
                    received.answer.complete(received.targetPluginId == "com.example.gateway")
                    delivered.size == 2
                }

            assertEquals(listOf("com.example.gateway", "com.example.other"), delivered)
            assertTrue(firstAsk.await())
            assertFalse(secondAsk.await())
        }

    // ---- the deferred restart ----

    @Test
    fun `a recorded set is claimed once`() {
        PendingDependentRestarts.record("com.example.gateway", listOf("com.example.flow"))

        assertEquals(listOf("com.example.flow"), PendingDependentRestarts.take("com.example.gateway"))
        // Claimed, not read: the target can load again later in the session, and a record that
        // survived its flush would restart the dependents on an unrelated reload.
        assertEquals(emptyList(), PendingDependentRestarts.take("com.example.gateway"))
    }

    @Test
    fun `recording an empty list cancels the arrangement`() {
        PendingDependentRestarts.record("com.example.gateway", listOf("com.example.flow"))

        // What the delegate does when the forced unload it just agreed to actually failed.
        PendingDependentRestarts.record("com.example.gateway", emptyList())

        assertEquals(emptyList(), PendingDependentRestarts.take("com.example.gateway"))
    }

    @Test
    fun `a later record replaces an earlier one`() {
        PendingDependentRestarts.record("com.example.gateway", listOf("com.example.flow"))
        PendingDependentRestarts.record("com.example.gateway", listOf("com.example.llmrpa"))

        // The set is recomputed from live manifests each time it is asked, so the newer answer
        // is the accurate one - merging would restart a plugin that no longer depends on this.
        assertEquals(listOf("com.example.llmrpa"), PendingDependentRestarts.take("com.example.gateway"))
    }

    @Test
    fun `the recorded order is preserved`() {
        val byPriority = listOf("com.example.first", "com.example.second", "com.example.third")
        PendingDependentRestarts.record("com.example.gateway", byPriority)

        // The caller sorts by load priority; this must not reorder it.
        assertEquals(byPriority, PendingDependentRestarts.take("com.example.gateway"))
    }

    @Test
    fun `a record whose plugin never came back expires`() {
        PendingDependentRestarts.record("com.example.gateway", listOf("com.example.flow"), nowMs = 0L)

        // A failed download, a rejected jar, a window closed between the unload and the load:
        // the target never reloads, so nothing would ever claim this and the dependent would
        // be left pointing at a closed classloader.
        val expired = PendingDependentRestarts.takeExpired(nowMs = PendingDependentRestarts.EXPIRY_MS)

        assertEquals(mapOf("com.example.gateway" to listOf("com.example.flow")), expired)
        assertEquals(emptyList(), PendingDependentRestarts.take("com.example.gateway"))
    }

    @Test
    fun `a plugin that never reloads still gets its dependents restarted`() {
        // The live failure this test exists for: the AI Gateway's update was confirmed, the
        // gateway unloaded, its download returned HTTP 404, and nothing loaded afterwards - so a
        // sweep that only ran on the next load never ran at all, and three plugins were left
        // holding a handle into a closed classloader.
        val restarted = java.util.Collections.synchronizedList(mutableListOf<String>())
        withShortDeadline(restarted) {
            DependentRestartCoordinator.record(
                "com.example.gateway",
                listOf("com.example.flow", "com.example.llmrpa"),
            )
            withTimeout(TEST_TIMEOUT_MS) { while (restarted.size < 2) delay(10) }
        }
        assertEquals(listOf("com.example.flow", "com.example.llmrpa"), restarted.toList())
    }

    @Test
    fun `a plugin that comes back does not get its dependents restarted twice`() {
        val restarted = java.util.Collections.synchronizedList(mutableListOf<String>())
        withShortDeadline(restarted) {
            DependentRestartCoordinator.record("com.example.gateway", listOf("com.example.flow"))
            // The normal path: the new version loads, which claims the record.
            DependentRestartCoordinator.flushAfterLoad("com.example.gateway")
            withTimeout(TEST_TIMEOUT_MS) { while (restarted.isEmpty()) delay(10) }
            // Well past the deadline the timer would otherwise have fired on. It finds nothing
            // left to claim, so the dependent restarts once, not once per mechanism.
            delay(SHORT_DEADLINE_MS * 4)
        }
        assertEquals(listOf("com.example.flow"), restarted.toList())
    }

    @Test
    fun `a fresh record is not expired out from under the load that will claim it`() {
        PendingDependentRestarts.record("com.example.gateway", listOf("com.example.flow"), nowMs = 0L)

        val expired = PendingDependentRestarts.takeExpired(nowMs = PendingDependentRestarts.EXPIRY_MS - 1)

        assertTrue(expired.isEmpty())
        assertEquals(listOf("com.example.flow"), PendingDependentRestarts.take("com.example.gateway"))
    }

    // ---- the words ----

    @Test
    fun `an update promises the dependents come back`() {
        val message =
            DependentRestartCopy.message(
                intent = PluginUnloadIntent.UPDATE,
                targetDisplayName = "AI Gateway",
                dependents = listOf(dependent("com.example.flow", "Flow")),
            )

        assertContains(message, "AI Gateway")
        assertContains(message, "new version")
        assertEquals("Install and reload plugins", DependentRestartCopy.confirmLabel(PluginUnloadIntent.UPDATE))
        assertEquals("Reload dependent plugins?", DependentRestartCopy.title(PluginUnloadIntent.UPDATE))
    }

    @Test
    fun `a removal says what stops working`() {
        val message =
            DependentRestartCopy.message(
                intent = PluginUnloadIntent.REMOVE,
                targetDisplayName = "AI Gateway",
                dependents = listOf(dependent("com.example.flow", "Flow")),
            )

        // Nothing comes back, so promising a new version here would be a lie.
        assertFalse(message.contains("new version"))
        assertContains(message, "stops working")
        assertEquals("Remove and reload plugins", DependentRestartCopy.confirmLabel(PluginUnloadIntent.REMOVE))
        assertEquals("Remove a plugin others use?", DependentRestartCopy.title(PluginUnloadIntent.REMOVE))
    }

    @Test
    fun `an unstated intent promises neither`() {
        val message =
            DependentRestartCopy.message(
                intent = PluginUnloadIntent.UNSPECIFIED,
                targetDisplayName = "AI Gateway",
                dependents = listOf(dependent("com.example.flow", "Flow")),
            )

        // This is what a pre-1.0.79 Toolbox reaches, and that one method serves both its Update
        // and its Remove - so either promise would be wrong half the time.
        assertFalse(message.contains("new version"))
        assertFalse(message.contains("stops working"))
        assertContains(message, "AI Gateway")
    }

    @Test
    fun `the sentence agrees with the number of dependents`() {
        val one =
            DependentRestartCopy.message(
                intent = PluginUnloadIntent.REMOVE,
                targetDisplayName = "AI Gateway",
                dependents = listOf(dependent("com.example.flow", "Flow")),
            )
        val many =
            DependentRestartCopy.message(
                intent = PluginUnloadIntent.REMOVE,
                targetDisplayName = "AI Gateway",
                dependents =
                    listOf(
                        dependent("com.example.flow", "Flow"),
                        dependent("com.example.llmrpa", "LLM RPA"),
                    ),
            )

        assertContains(one, "needs AI Gateway")
        assertContains(many, "need AI Gateway")
    }

    @Test
    fun `open tab counts are read per dependent`() {
        val flow = dependent("com.example.flow", "Flow")
        val llmrpa = dependent("com.example.llmrpa", "LLM RPA")
        val pending =
            DependentRestartPrompt(
                targetPluginId = "com.example.gateway",
                targetDisplayName = "AI Gateway",
                intent = PluginUnloadIntent.UPDATE,
                dependents = listOf(flow, llmrpa),
                openInstances = mapOf("com.example.flow" to 2),
            )

        assertEquals(2, pending.openInstancesOf(flow))
        // Absent, not zero-recorded: a headless host has no counter wired at all, and the
        // dialog must render the row rather than blaming the dependent for having no tabs.
        assertEquals(0, pending.openInstancesOf(llmrpa))
    }

    @Test
    fun `a prompt that reaches nobody refuses the unload`() =
        runTest {
            val bus = DependentRestartBus()

            // Five prompts against a four-slot buffer with no collector. The fifth cannot be
            // delivered, and "we could not ask" has to answer the same as "the user said no" -
            // the plugin is still loaded either way, which is exactly today's refusal.
            val answers =
                (1..5).map { index ->
                    async { bus.ask(prompt(targetPluginId = "com.example.p$index")) }
                }
            advanceTimeBy(DependentRestartBus.ANSWER_TIMEOUT_MS + 1)
            advanceUntilIdle()

            assertTrue(answers.all { !it.await() })
        }

    @Test
    fun `confirming records the dependents and declining records nothing`() =
        runTest {
            // The shape the delegate relies on: the record has to exist before the unload, and
            // must not exist at all when the answer was no.
            val bus = DependentRestartBus()
            val declined = prompt(targetPluginId = "com.example.gateway")

            val asked = async { bus.ask(declined) }
            runCurrent()
            launch {
                bus.restartPrompts
                    .first()
                    .answer
                    .complete(false)
            }
            advanceUntilIdle()

            assertFalse(asked.await())
            assertEquals(emptyList(), PendingDependentRestarts.take("com.example.gateway"))
        }
}
