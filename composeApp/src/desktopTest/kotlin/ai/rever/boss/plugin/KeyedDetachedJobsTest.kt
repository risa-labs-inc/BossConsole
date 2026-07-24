package ai.rever.boss.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [KeyedDetachedJobs] — the detach-and-coalesce runner behind
 * [PluginLoaderDelegateImpl.reloadPlugin]. The invariant that matters: a
 * plugin reloading ITSELF gets its calling coroutine cancelled mid-reload
 * (the force-unload disposes it), and the reload must still complete.
 */
class KeyedDetachedJobsTest {
    private fun ownerScope() = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Test
    fun `job runs to completion although the caller is cancelled mid-run`() =
        runBlocking {
            val owner = ownerScope()
            val jobs = KeyedDetachedJobs<String, String>(owner)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<String>()

            val caller =
                launch {
                    jobs.run("plugin") {
                        entered.complete(Unit)
                        gate.await()
                        completed.complete("reloaded")
                        "reloaded"
                    }
                }
            entered.await()
            // The reload just unloaded the caller's own plugin: its scope dies.
            caller.cancelAndJoin()
            gate.complete(Unit)

            assertEquals("reloaded", withTimeout(5_000) { completed.await() })
            owner.cancel()
        }

    @Test
    fun `concurrent runs for the same key coalesce onto one execution`() =
        runBlocking {
            val owner = ownerScope()
            val jobs = KeyedDetachedJobs<String, String>(owner)
            val executions = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()

            val first =
                async {
                    jobs.run("plugin") {
                        executions.incrementAndGet()
                        entered.complete(Unit)
                        gate.await()
                        "first"
                    }
                }
            entered.await()
            // UNDISPATCHED so the lookup happens before this async returns —
            // guaranteed to observe the first job still in flight.
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    jobs.run("plugin") {
                        executions.incrementAndGet()
                        "second"
                    }
                }
            gate.complete(Unit)

            assertEquals("first", first.await())
            assertEquals("first", second.await())
            assertEquals(1, executions.get())
            owner.cancel()
        }

    @Test
    fun `a run after completion executes again instead of joining a stale result`() =
        runBlocking {
            val owner = ownerScope()
            val jobs = KeyedDetachedJobs<String, String>(owner)
            val executions = AtomicInteger()

            assertEquals(
                "a",
                jobs.run("plugin") {
                    executions.incrementAndGet()
                    "a"
                },
            )
            assertEquals(
                "b",
                jobs.run("plugin") {
                    executions.incrementAndGet()
                    "b"
                },
            )
            assertEquals(2, executions.get())
            owner.cancel()
        }

    @Test
    fun `distinct keys run independently`() =
        runBlocking {
            val owner = ownerScope()
            val jobs = KeyedDetachedJobs<String, String>(owner)
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()

            val blocked =
                async {
                    jobs.run("blocked") {
                        entered.complete(Unit)
                        gate.await()
                        "blocked"
                    }
                }
            entered.await()
            // Must not queue behind the other key's in-flight job.
            assertEquals("free", withTimeout(5_000) { jobs.run("free") { "free" } })
            gate.complete(Unit)
            assertEquals("blocked", blocked.await())
            owner.cancel()
        }

    @Test
    fun `detached failure after caller cancellation reaches onDetachedFailure`() =
        runBlocking {
            val owner = ownerScope()
            val jobs = KeyedDetachedJobs<String, String>(owner)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val reported = CompletableDeferred<Throwable>()

            val caller =
                launch {
                    jobs.run("plugin", onDetachedFailure = { reported.complete(it) }) {
                        entered.complete(Unit)
                        gate.await()
                        // The kind of failure the real reload can die with once its
                        // caller is gone (a closed classloader surfaces as an Error).
                        throw NoClassDefFoundError("boom")
                    }
                }
            entered.await()
            caller.cancelAndJoin()
            gate.complete(Unit)

            val cause = withTimeout(5_000) { reported.await() }
            assertTrue(cause is NoClassDefFoundError)
            owner.cancel()
        }
}
