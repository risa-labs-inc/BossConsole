package ai.rever.boss.components.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DependencyPromptClaimRoutingTest {
    private fun installer() =
        object : MissingDependencyInstaller {
            override fun isInstalled(pluginId: String) = false

            override suspend fun displayNameFor(pluginId: String): String? = null

            override suspend fun install(pluginId: String) = Result.success(Unit)
        }

    private fun prompt(id: String) =
        MissingDependencyPrompt(
            MissingPluginDependency("dependent", "Dependent", id, optional = false),
            installer(),
            windowId = "a",
        )

    @Test
    fun `busy target retains later prompts while another window makes progress`() =
        runTest {
            val bus = PluginDependencyBus()
            val dismiss = CompletableDeferred<Unit>()
            val shownA = mutableListOf<String>()
            val shownB = mutableListOf<String>()
            backgroundScope.launch {
                bus.missingDependencies.collect { prompt ->
                    if (shouldClaimMissingDependencyPrompt(prompt, "a", true) && bus.claim(prompt)) {
                        shownA += prompt.missing.missingPluginId
                        if (shownA.size == 1) dismiss.await()
                    }
                }
            }
            backgroundScope.launch {
                bus.missingDependencies.collect { prompt ->
                    if (shouldClaimMissingDependencyPrompt(prompt, "b", true) && bus.claim(prompt)) {
                        shownB += prompt.missing.missingPluginId
                    }
                }
            }
            bus.report(prompt("first"))
            runCurrent()
            bus.report(prompt("second"))
            bus.report(prompt("other-window").copy(windowId = "b"))
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(listOf("first"), shownA)
            assertEquals(listOf("other-window"), shownB)

            dismiss.complete(Unit)
            runCurrent()
            assertEquals(listOf("first", "second"), shownA)
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(listOf("first", "second"), shownA)
        }

    @Test
    fun `target closure wakes fallback without a new report and claims only once`() =
        runTest {
            val bus = PluginDependencyBus()
            var targetOpen = true
            val shown = mutableListOf<MissingDependencyPrompt>()
            for (window in listOf("b", "c")) {
                backgroundScope.launch {
                    bus.missingDependencies.collect { prompt ->
                        if (shouldClaimMissingDependencyPrompt(prompt, window, targetOpen) && bus.claim(prompt)) {
                            shown += prompt
                        }
                    }
                }
            }
            val original = prompt("gateway")
            bus.report(original)
            runCurrent()
            assertTrue(shown.isEmpty())
            targetOpen = false
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(listOf(original), shown)
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(listOf(original), shown)
        }

    @Test
    fun `cancelling an active rejecting collector preserves the original installer under duplicate reports`() =
        runTest {
            val bus = PluginDependencyBus()
            val original = prompt("gateway")
            bus.report(original)
            val seen = CompletableDeferred<Unit>()
            val wrongWindow =
                launch {
                    bus.missingDependencies.collect { prompt ->
                        assertFalse(shouldClaimMissingDependencyPrompt(prompt, "b", true))
                        seen.complete(Unit)
                        awaitCancellation()
                    }
                }
            seen.await()
            val replacement = original.copy(installer = installer(), windowId = "b")
            bus.report(replacement)
            repeat(8) { bus.report(prompt("other-$it")) }
            wrongWindow.cancelAndJoin()

            val retained = bus.missingDependencies.first { bus.claim(it) }
            assertSame(original, retained)
            assertSame(original.installer, retained.installer)
            assertFalse(bus.claim(replacement))
        }

    @Test
    fun `suppressed prompts release their keys for explicit retries`() =
        runTest {
            for (present in listOf(false, true)) {
                val bus = PluginDependencyBus()
                val original = prompt("gateway")
                bus.report(original)
                if (!present) bus.decline(original.missing)

                assertNull(bus.claimMissingDependencyForWindow(original, "a", true) { present })
                val retry = original.copy(userInitiated = true)
                bus.report(retry)
                assertSame(retry, bus.missingDependencies.first())
                assertSame(retry, bus.claimMissingDependencyForWindow(retry, "a", true) { false })
            }
        }

    @Test
    fun `cancellation inside the production presence check preserves the pending entry`() =
        runTest {
            val bus = PluginDependencyBus()
            val original = prompt("gateway")
            bus.report(original)
            val checking = CompletableDeferred<Unit>()
            val collector =
                launch {
                    bus.claimMissingDependencyForWindow(original, "a", true) {
                        checking.complete(Unit)
                        awaitCancellation()
                    }
                }
            checking.await()
            collector.cancelAndJoin()
            assertSame(original, bus.claimMissingDependencyForWindow(original, "b", false) { false })
            assertNull(bus.claimMissingDependencyForWindow(original, "b", false) { false })
        }

    @Test
    fun `competing threads cannot both claim a pending prompt`() {
        val bus = PluginDependencyBus()
        val original = prompt("gateway")
        bus.report(original)
        val barrier = CyclicBarrier(2)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val claims =
                List(2) {
                    workers.submit<Boolean> {
                        barrier.await(5, TimeUnit.SECONDS)
                        bus.claim(original)
                    }
                }
            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            workers.shutdownNow()
        }
    }
}
