package ai.rever.boss.utils

import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanupRunnerTest {
    @Test
    fun failedReleaseAllowsRemainingReleasesAndReportsFailure() {
        val events = mutableListOf<String>()
        val cleanup = CleanupRunner("CleanupRunnerTest")
        assertFalse(
            cleanup.run("closed plugin") {
                events.add("failing")
                throw NoClassDefFoundError("plugin classloader closed")
            },
        )
        assertTrue(cleanup.run("remaining resource") { events.add("remaining") })
        assertEquals(listOf("failing", "remaining"), events)
    }

    @Test
    fun suspendedCancellationPropagatesWhileFinallyReleasesContinue(): Unit =
        runBlocking {
            val events = mutableListOf<String>()
            val cleanup = CleanupRunner("CleanupRunnerTest")
            val cancelledRelease: () -> Unit = {
                events.add("failing release")
                throw CancellationException("plugin cleanup cancelled")
            }
            val operation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        assertFalse(
                            cleanup.run("cancelled release callback", cancelledRelease),
                        )
                        assertTrue(cleanup.run("remaining release") { events.add("remaining release") })
                    }
                }
            operation.cancelAndJoin()
            assertTrue(operation.isCancelled, "suspending operation cancellation must propagate")
            assertEquals(listOf("failing release", "remaining release"), events)
        }

    @Test
    fun cancellationFromLifecycleCallbackStillReachesFinalDestructionOnce() {
        val events = mutableListOf<String>()
        val lifecycle = LifecycleRegistry()
        lifecycle.subscribe(
            object : Lifecycle.Callbacks {
                override fun onPause() {
                    events.add("pause")
                    throw CancellationException("plugin callback cancelled")
                }

                override fun onDestroy() {
                    events.add("destroy")
                }
            },
        )
        lifecycle.resume()
        val cleanup = CleanupRunner("CleanupRunnerTest")
        cleanup.destroyLifecycle("plugin lifecycle", lifecycle)
        cleanup.destroyLifecycle("plugin lifecycle", lifecycle)
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.state)
        assertEquals(listOf("pause", "destroy"), events)
    }

    @Test
    fun lifecycleFailuresStillReachDestroyWithoutRedeliveringCallbacks() {
        listOf("pause", "stop", "destroy").forEach { failingStage ->
            val events = mutableListOf<String>()
            val lifecycle = LifecycleRegistry()
            lifecycle.subscribe(
                object : Lifecycle.Callbacks {
                    private fun record(stage: String) {
                        events.add(stage)
                        if (stage == failingStage) error("failed $stage")
                    }

                    override fun onPause() = record("pause")

                    override fun onStop() = record("stop")

                    override fun onDestroy() = record("destroy")
                },
            )
            lifecycle.resume()
            val cleanup = CleanupRunner("CleanupRunnerTest")
            cleanup.destroyLifecycle("plugin lifecycle", lifecycle)
            cleanup.destroyLifecycle("plugin lifecycle", lifecycle)
            assertEquals(Lifecycle.State.DESTROYED, lifecycle.state)
            assertEquals(listOf("pause", "stop", "destroy"), events)
        }
    }
}
