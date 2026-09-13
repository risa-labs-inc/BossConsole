package ai.rever.boss.plugin.sandbox

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginRestartDisableRaceTest {
    @Test
    fun `budget disable rejects restart while executor teardown is parked`() =
        runBlocking {
            val config = SandboxConfig(maxRestartAttempts = 0)
            val manager = PluginSandboxManagerImpl(config)
            val sandbox = manager.createSandbox("budget-disable-race", config) as InProcessPluginSandbox
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            sandbox.start().getOrThrow()
            sandbox.sandboxScope.launch {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Plugin work was not released" }
            }
            var disabling: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                disabling =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        manager.handleRestartRequest(sandbox.pluginId)
                    }
                assertFalse(disabling.isCompleted, "Budget disable must be waiting for the old executor")
                assertTrue(manager.restartPlugin(sandbox.pluginId).isFailure, "A disabling sandbox must reject restart")
                release.countDown()
                withTimeout(5_000) { disabling.await() }
                assertTrue(manager.isPluginDisabled(sandbox.pluginId))
                assertEquals(SandboxState.DISABLED, sandbox.state.value)
                assertTrue(sandbox.isExecutorTerminated(), "Budget disable must leave no replacement executor")
            } finally {
                release.countDown()
                disabling?.join()
                manager.dispose()
            }
        }

    @Test
    fun `disable cannot be overwritten by an admitted restart publishing running`() =
        runBlocking {
            val sandbox = InProcessPluginSandbox("restart-disable-race")
            sandbox.start().getOrThrow()
            val publishing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val restartLock =
                sandbox.javaClass
                    .getDeclaredField("restartLock")
                    .apply { isAccessible = true }
                    .get(sandbox)
            val stateField = sandbox.javaClass.getDeclaredField("_state").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(sandbox) as MutableStateFlow<SandboxState>
            // Park the real restart at its final publication, without adding a production hook.
            stateField.set(sandbox, PausedRunningState(state, publishing, release))
            val restarting =
                thread(name = "sandbox-restart-test") {
                    runCatching { runBlocking { sandbox.restart().getOrThrow() } }
                        .onFailure { failure.compareAndSet(null, it) }
                }
            var disabling: Thread? = null
            try {
                assertTrue(publishing.await(5, TimeUnit.SECONDS), "Restart must reach RUNNING publication")
                disabling =
                    thread(name = "sandbox-disable-test") {
                        runCatching {
                            sandbox.setDisabled()
                            runBlocking { sandbox.stop().getOrThrow() }
                            sandbox.setDisabled()
                        }.onFailure { failure.compareAndSet(null, it) }
                    }
                // The fixed path blocks on the restart lock. The old path completes disable
                // here, then overwrites it with RUNNING when the parked publication resumes.
                val disableThread = disabling
                withTimeout(5_000) {
                    while (disableThread.isAlive && !blockedOn(disableThread, restartLock)) delay(1)
                }
                release.countDown()
                restarting.join(5_000)
                disableThread.join(5_000)
                assertFalse(restarting.isAlive)
                assertFalse(disableThread.isAlive)
                assertNull(failure.get())
                assertEquals(SandboxState.DISABLED, sandbox.state.value)
                assertTrue(sandbox.isExecutorTerminated())
            } finally {
                release.countDown()
                restarting.join(5_000)
                disabling?.join(5_000)
                sandbox.stop()
            }
        }

    private fun blockedOn(
        thread: Thread,
        lock: Any,
    ): Boolean {
        val info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.id) ?: return false
        return info.threadState == Thread.State.BLOCKED &&
            info.lockInfo?.identityHashCode == System.identityHashCode(lock)
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class PausedRunningState(
        private val delegate: MutableStateFlow<SandboxState>,
        private val publishing: CountDownLatch,
        private val release: CountDownLatch,
    ) : MutableStateFlow<SandboxState> by delegate {
        override var value: SandboxState
            get() = delegate.value
            set(value) {
                if (value == SandboxState.RUNNING) {
                    publishing.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Publication was not released" }
                }
                delegate.value = value
            }
    }
}
