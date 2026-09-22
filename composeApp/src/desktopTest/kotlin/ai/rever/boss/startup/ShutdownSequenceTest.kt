package ai.rever.boss.startup

import ai.rever.boss.plugin.browser.BrowserCleanupDrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShutdownSequenceTest {
    @Test
    fun executeRunsAllStepsInOrderEvenWhenExceptionsOccur() {
        val executionLog = mutableListOf<String>()
        var lockReleased = false

        val steps =
            listOf(
                ShutdownStep("step 1") {
                    executionLog.add("step1")
                },
                ShutdownStep("failing step 2") {
                    executionLog.add("step2")
                    throw LinkageError("Simulated linkage failure in step 2")
                },
                ShutdownStep("step 3") {
                    executionLog.add("step3")
                },
            )

        ShutdownSequence.execute(
            steps = steps,
            releaseLock = {
                lockReleased = true
            },
        )

        assertEquals(listOf("step1", "step2", "step3"), executionLog)
        assertTrue(lockReleased)
    }

    @Test
    fun defaultStepsContainsExpectedNamedSteps() {
        val steps = ShutdownSequence.defaultSteps()
        val stepNames = steps.map { it.name }

        assertEquals(
            listOf(
                "saving Last Session on exit",
                "flushing debounced recent-files and user-data saves on exit",
                "stopping performance monitor",
                "draining browser native cleanup",
                "closing browser engine",
                "closing favicon HTTP client",
                "uninstalling keyboard interceptor",
                "stopping app update realtime",
                "shutting down updater",
                "shutting down plugin store",
                "shutting down logger",
                "shutting down kernel",
            ),
            stepNames,
        )
    }

    @Test
    fun constructionFailureStillReleasesLock() {
        var released = false
        kotlin.test.assertFailsWith<LinkageError> {
            ShutdownSequence.executePrepared(
                prepareSteps = { throw LinkageError("construction") },
                releaseLock = { released = true },
            )
        }
        assertTrue(released)
        ShutdownSequence.execute(emptyList(), releaseLock = { throw LinkageError("release") })
    }

    /**
     * The drain is only worth having ahead of the engine close: what it waits for is a browser close
     * and a profile release, and both call into the engine. Once that step has run they have nothing
     * left to run against, so this order is the fix rather than an implementation detail.
     */
    @Test
    fun `browser native cleanup drains before the engine closes`() {
        val names = ShutdownSequence.defaultSteps().map { it.name }
        val drain = names.indexOf("draining browser native cleanup")
        val engine = names.indexOf("closing browser engine")

        assertTrue(drain >= 0, "the shutdown sequence must drain browser native cleanup")
        assertTrue(engine >= 0, "the shutdown sequence must close the browser engine")
        assertTrue(drain < engine, "the drain must run before the engine closes")
    }

    /**
     * The real step's action, not the drain object in isolation: if the step is wired to something
     * that does not wait, the ordering above buys nothing.
     */
    @Test
    fun `the shutdown drain step waits for cleanup that is already running`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        CoroutineScope(BrowserCleanupDrain.job + Dispatchers.IO).launch {
            entered.countDown()
            gate.await()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the tracked cleanup never started")

        val step = ShutdownSequence.defaultSteps().single { it.name == "draining browser native cleanup" }
        val returned = CountDownLatch(1)
        val hook =
            Thread(
                {
                    step.action()
                    returned.countDown()
                },
                "test-shutdown-drain",
            ).apply { isDaemon = true }
        try {
            hook.start()
            assertFalse(returned.await(250, TimeUnit.MILLISECONDS), "the drain returned with cleanup outstanding")
        } finally {
            gate.countDown()
        }
        assertTrue(returned.await(5, TimeUnit.SECONDS), "the drain never returned after the cleanup finished")
    }
}
