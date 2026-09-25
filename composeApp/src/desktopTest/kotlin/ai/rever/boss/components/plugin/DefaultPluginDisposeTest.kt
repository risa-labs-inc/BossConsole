package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginCleanupCallback
import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.SandboxConfig
import ai.rever.boss.plugin.sandbox.health.PluginHealthSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * b07: `DefaultPlugin.dispose()` is called from a window's Compose `onDispose` - the UI
 * thread. Before the fix it ran the whole plugin teardown inside `runBlocking`, so every
 * window close paid the sandbox drain and classloader closes on the UI thread, and quit
 * could stall under load. These tests pin the new contract: `dispose()` returns
 * immediately, the teardown runs on a background scope, and it is bounded.
 */
class DefaultPluginDisposeTest {
    /** A sandbox manager whose dispose records where it runs and waits on a test latch. */
    private class RecordingSandboxManager(
        private val onDispose: suspend () -> Unit,
    ) : PluginSandboxManager {
        override val healthSummary = MutableStateFlow(PluginHealthSummary())

        override fun createSandbox(
            pluginId: String,
            config: SandboxConfig,
        ): PluginSandbox = error("no sandboxes in this fixture")

        override fun getSandbox(pluginId: String): PluginSandbox? = null

        override suspend fun removeSandbox(pluginId: String) = Unit

        override suspend fun fullyUnloadPlugin(pluginId: String): Result<Unit> = Result.success(Unit)

        override fun registerCleanupCallback(callback: PluginCleanupCallback) = Unit

        override fun unregisterCleanupCallback(callback: PluginCleanupCallback) = Unit

        override suspend fun restartPlugin(pluginId: String): Result<Unit> = Result.success(Unit)

        override suspend fun disablePlugin(pluginId: String): Result<Unit> = Result.success(Unit)

        override suspend fun enablePlugin(pluginId: String): Result<Unit> = Result.success(Unit)

        override fun isPluginDisabled(pluginId: String): Boolean = false

        override fun getDisabledPlugins(): Set<String> = emptySet()

        override fun getAllSandboxes(): Map<String, PluginSandbox> = emptyMap()

        override suspend fun dispose() = onDispose()
    }

    private fun windowPlugin(sandboxManager: PluginSandboxManager) =
        DefaultPlugin(
            PanelRegistry(),
            TabRegistry(),
            windowProjectState = null,
            sandboxManager = sandboxManager,
        )

    @Test
    fun `dispose returns on the caller thread while teardown runs in the background`() =
        runBlocking {
            val teardownThread = CompletableDeferred<Thread>()
            val teardownStarted = CompletableDeferred<Unit>()
            val releaseTeardown = CompletableDeferred<Unit>()
            val plugin =
                windowPlugin(
                    RecordingSandboxManager {
                        teardownThread.complete(Thread.currentThread())
                        teardownStarted.complete(Unit)
                        releaseTeardown.await()
                    },
                )

            val caller = Thread.currentThread()
            val job = plugin.dispose(timeoutMillis = 5_000)

            // The call returned while teardown is still suspended inside it - the window's
            // onDispose pays nothing for classloader closes or the sandbox drain.
            withTimeout(5_000) { teardownStarted.await() }
            assertFalse(job.isCompleted, "dispose() returned before teardown finished, as designed")
            assertNotEquals(
                caller,
                teardownThread.await(),
                "teardown must not run on the caller's thread - that is the b07 stall",
            )

            releaseTeardown.complete(Unit)
            withTimeout(5_000) { job.join() }
        }

    @Test
    fun `several plugin windows tear down concurrently rather than serialising on the caller`() =
        runBlocking {
            val entered = AtomicInteger(0)
            val release = CompletableDeferred<Unit>()
            val plugins =
                List(2) {
                    windowPlugin(
                        RecordingSandboxManager {
                            entered.incrementAndGet()
                            release.await()
                        },
                    )
                }

            val jobs = plugins.map { it.dispose(timeoutMillis = 5_000) }

            // Both teardowns are in flight at once: disposing N windows does not cost N
            // sequential teardowns on the thread that asked to close.
            withTimeout(5_000) {
                while (entered.get() < 2) delay(10)
            }
            assertTrue(jobs.none { it.isCompleted })

            release.complete(Unit)
            jobs.forEach { withTimeout(5_000) { it.join() } }
        }

    @Test
    fun `a hung sandbox teardown cannot wedge dispose past its bound`() =
        runBlocking {
            val plugin =
                windowPlugin(
                    RecordingSandboxManager { awaitCancellation() },
                )

            val job = plugin.dispose(timeoutMillis = 200)
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCompleted, "bounded teardown must finish even when a plugin hangs")
        }

    @Test
    fun `dispose is idempotent - a repeat call returns the same teardown job`() =
        runBlocking {
            val plugin = windowPlugin(RecordingSandboxManager {})

            val first = plugin.dispose(timeoutMillis = 5_000)
            val second = plugin.dispose(timeoutMillis = 1)

            assertSame(first, second)
            withTimeout(5_000) { first.join() }
        }

    @Test
    fun `concurrent dispose calls start one teardown`() =
        runBlocking {
            val entered = AtomicInteger(0)
            val release = CompletableDeferred<Unit>()
            val plugin =
                windowPlugin(
                    RecordingSandboxManager {
                        entered.incrementAndGet()
                        release.await()
                    },
                )
            val start = CountDownLatch(1)
            val jobs = arrayOfNulls<Job>(8)
            val callers =
                jobs.indices.map { index ->
                    Thread {
                        start.await()
                        jobs[index] = plugin.dispose(timeoutMillis = 5_000)
                    }.also { it.start() }
                }

            start.countDown()
            callers.forEach { it.join() }
            withTimeout(5_000) {
                while (entered.get() == 0) delay(10)
            }
            assertTrue(jobs.all { it === jobs[0] })
            assertTrue(entered.get() == 1, "only the canonical teardown may run")
            release.complete(Unit)
            withTimeout(5_000) { jobs[0]!!.join() }
        }
}
