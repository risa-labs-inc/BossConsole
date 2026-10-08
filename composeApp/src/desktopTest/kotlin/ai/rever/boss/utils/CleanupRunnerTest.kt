package ai.rever.boss.utils

import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
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
