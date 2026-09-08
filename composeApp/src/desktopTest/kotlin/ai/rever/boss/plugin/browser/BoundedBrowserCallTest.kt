package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogLevel
import ai.rever.boss.utils.logging.LogListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the deadline actually buys, pinned without a Chromium.
 *
 * The freeze this class exists to prevent needs a real renderer that stops answering, which no unit
 * test can arrange. What it *can* arrange is the shape: a call that never returns, on the one thread
 * it is confined to. Everything the KDoc promises about that is checked here, because the promise
 * was wrong once already - the wait used to run in the caller's context, so a caller that happened
 * to be single-threaded lost the bound with nothing in the signature to say so.
 */
class BoundedBrowserCallTest {
    private val timeout = 300L

    /** Long enough that a *bounded* wait cannot reach it, short enough to fail a test run fast. */
    private val generous = 10_000L

    @Test
    fun `a call that returns is answered with its value`() {
        val call = BoundedBrowserCall("test-bounded-ok")
        try {
            runBlocking { assertEquals("answered", call.call(generous) { "answered" }) }
        } finally {
            call.shutdown()
        }
    }

    /**
     * The property this class exists for, and the only one every other test here takes for granted.
     *
     * Everything else is about the *wait*. If a refactor ran `block()` inline in the caller's context
     * the deadline tests would all still pass and the EDT would be back in the blocking call - which
     * is the entire bug.
     */
    @Test
    fun `the block runs on the dedicated thread, not the caller's`() {
        val call = BoundedBrowserCall("test-bounded-thread")
        try {
            runBlocking {
                // startsWith, not equals: with coroutine debug on, kotlinx appends "@coroutine#N" to
                // the thread name. The prefix is the assertion - it says which thread ran the block.
                val ranOn = call.call(generous) { Thread.currentThread().name }.orEmpty()
                assertTrue(
                    ranOn.startsWith("test-bounded-thread"),
                    "block ran on \"$ranOn\" - it must run on the dedicated thread, not the caller's",
                )
            }
        } finally {
            call.shutdown()
        }
    }

    @Test
    fun `a call that never answers gives up on schedule`() {
        val call = BoundedBrowserCall("test-bounded-wedge")
        val release = CountDownLatch(1)
        try {
            val elapsed =
                measureTimeMillis {
                    runBlocking {
                        assertNull(
                            call.call(timeout) {
                                release.await()
                                "never"
                            },
                        )
                    }
                }
            // Both sides: `< generous` proves the wait ended, `>= timeout` proves the DEADLINE is what
            // ended it rather than the call quietly answering null for some unrelated reason.
            assertTrue(elapsed < generous, "waited ${elapsed}ms - the deadline did not bound the call")
            assertTrue(elapsed >= timeout, "returned after ${elapsed}ms, before its own ${timeout}ms deadline")
        } finally {
            release.countDown()
            call.shutdown()
        }
    }

    /**
     * A wedge has to be *reportable*, not just survivable.
     *
     * The first version answered null on timeout and told nobody: `onError` was reachable only
     * through the success path's `getOrElse`, and `withTimeoutOrNull` returning null short-circuited
     * straight past it. That turns "the app froze" into "plugin JS silently returns null forever",
     * which is better to live with and much worse to diagnose.
     */
    @Test
    fun `a timeout is reported, and is not reported as an error`() {
        val call = BoundedBrowserCall("test-bounded-report")
        val release = CountDownLatch(1)
        var timedOut = 0
        val errors = mutableListOf<Throwable>()
        try {
            runBlocking {
                assertNull(
                    call.call(timeout, onError = { errors += it }, onTimeout = { timedOut++ }) {
                        release.await()
                        "never"
                    },
                )
            }
            assertEquals(1, timedOut, "the timeout produced no report at all")
            assertTrue(errors.isEmpty(), "a timeout was reported as a thrown error: $errors")
        } finally {
            release.countDown()
            call.shutdown()
        }
    }

    /**
     * The trade the KDoc describes: the wedged call keeps the thread, so later calls do NOT get
     * through - they queue behind it and answer null on time. Degradation, not a freeze.
     */
    @Test
    fun `later calls still answer on schedule while one is wedged`() {
        val call = BoundedBrowserCall("test-bounded-queue")
        val release = CountDownLatch(1)
        try {
            runBlocking {
                assertNull(
                    call.call(timeout) {
                        release.await()
                        "never"
                    },
                )
                val elapsed = measureTimeMillis { assertNull(call.call(timeout) { "queued behind it" }) }
                assertTrue(elapsed < generous, "the second call waited ${elapsed}ms rather than its own deadline")
                assertTrue(elapsed >= timeout, "the second call returned after ${elapsed}ms, before its deadline")
            }
        } finally {
            release.countDown()
            call.shutdown()
        }
    }

    /**
     * The regression that matters: the bound must not depend on where the caller runs.
     *
     * A caller confined to its own single thread - which is what `coBrowseScope` and `pageEventScope`
     * are - used to lose the deadline entirely, because `withTimeoutOrNull { await() }` ran in the
     * caller's context and resuming it needed a dispatch this class had no say over.
     */
    @Test
    fun `the bound holds for a caller confined to its own single thread`() {
        val call = BoundedBrowserCall("test-bounded-confined")
        val callerThread = Executors.newSingleThreadExecutor { r -> Thread(r, "test-confined-caller") }
        val release = CountDownLatch(1)
        try {
            val elapsed =
                measureTimeMillis {
                    runBlocking {
                        withContext(callerThread.asCoroutineDispatcher()) {
                            assertNull(
                                call.call(timeout) {
                                    release.await()
                                    "never"
                                },
                            )
                        }
                    }
                }
            assertTrue(elapsed < generous, "waited ${elapsed}ms - the caller's dispatcher still decides the bound")
            assertTrue(elapsed >= timeout, "returned after ${elapsed}ms, before its own deadline")
        } finally {
            release.countDown()
            call.shutdown()
            callerThread.shutdownNow()
        }
    }

    /**
     * After [BoundedBrowserCall.shutdown] the executor rejects the dispatch and kotlinx cancels the
     * job. That cancellation belongs to this class, not to the caller, so it owes a null rather than
     * throwing into a plugin's coroutine - which is what every other failure path here answers.
     */
    @Test
    fun `a call after shutdown answers null rather than throwing`() {
        val call = BoundedBrowserCall("test-bounded-shutdown")
        call.shutdown()
        runBlocking { assertNull(call.call(generous) { "unreachable" }) }
    }

    /** A throwing call is a null answer plus one report, not a propagated exception. */
    @Test
    fun `a throwing call is reported and answered with null`() {
        val call = BoundedBrowserCall("test-bounded-throw")
        val seen = mutableListOf<Throwable>()
        try {
            runBlocking {
                assertNull(call.call(generous, onError = { seen += it }) { error("renderer said no") })
            }
            assertEquals(1, seen.size, "expected exactly one reported failure, got $seen")
        } finally {
            call.shutdown()
        }
    }

    /**
     * [BoundedBrowserCall.backlog] is load-bearing, not diagnostic: `CoBrowseRtcPeerImpl.sendDom`
     * decides whether to drop a DOM frame by reading it. Nothing pinned it, so a refactor that had
     * it answer 0 - the queue swapped, the count taken after the poll - would silently turn the drop
     * policy back into the unbounded growth it replaced, with every other test still green.
     */
    @Test
    fun `backlog counts the work queued behind a wedged call`() {
        val call = BoundedBrowserCall("test-bounded-backlog")
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            assertEquals(0, call.backlog, "an idle instance reported queued work")
            call.post {
                entered.countDown()
                release.await()
            }
            entered.await()
            // Queued, not started: the one thread is inside the post above.
            call.post { }
            call.post { }
            assertEquals(2, call.backlog, "backlog did not count the work waiting behind the wedge")
        } finally {
            release.countDown()
            call.shutdown()
        }
    }

    /**
     * The one documented way to reintroduce a permanent hang, pinned at the moment it is written
     * rather than at the moment a renderer wedges.
     *
     * `coBrowseScope`, `pageEventScope` and `call` all share one dispatcher, so an edit that awaits
     * a call from inside one of those scopes hangs forever - and only once a page stops answering,
     * which is why the warning is eager and why it needs to stay that way.
     */
    @Test
    fun `awaiting a call from its own dispatcher is warned about`() {
        val call = BoundedBrowserCall("test-bounded-confinement-warning")
        val seen = mutableListOf<LogEntry>()
        val listener = LogListener { entry -> synchronized(seen) { seen += entry } }
        val previousLevel = BossLogger.globalLevel
        BossLogger.setGlobalLevel(LogLevel.WARN)
        BossLogger.setCategoryLevel(LogCategory.BROWSER, LogLevel.WARN)
        BossLogger.addListener(listener)
        try {
            // A block that completes, so the call itself succeeds. The warning is about the shape,
            // not the outcome: on the happy path awaiting from this dispatcher works, which is
            // exactly why nothing but an eager warning would ever surface it.
            runBlocking {
                withContext(call.dispatcher) { assertEquals("fine", call.call(generous) { "fine" }) }
            }
            val warnings = synchronized(seen) { seen.filter { it.message.contains("awaited from its own dispatcher") } }
            assertEquals(1, warnings.size, "no warning for a call awaited from its own dispatcher: $seen")
            assertEquals(
                "test-bounded-confinement-warning",
                warnings.single().data?.get("thread"),
                "the warning did not name the instance whose dispatcher was awaited from",
            )
        } finally {
            BossLogger.removeListener(listener)
            BossLogger.clearCategoryLevel(LogCategory.BROWSER)
            BossLogger.setGlobalLevel(previousLevel)
            call.shutdown()
        }
    }

    /** The caller's own cancellation is not swallowed by the shutdown handling above. */
    @Test
    fun `a caller's cancellation still propagates`() {
        val call = BoundedBrowserCall("test-bounded-cancel")
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            var propagated = false
            runBlocking {
                val job =
                    launch {
                        try {
                            call.call(generous) {
                                entered.countDown()
                                release.await()
                                "never"
                            }
                        } catch (_: CancellationException) {
                            propagated = true
                        }
                    }
                // Cancel only once the blocking body is genuinely in flight, so this tests the
                // await being cancelled rather than the job never having started.
                withContext(Dispatchers.IO) { entered.await() }
                job.cancelAndJoin()
            }
            assertTrue(propagated, "the caller's cancellation was swallowed and answered as null")
        } finally {
            release.countDown()
            call.shutdown()
        }
    }
}
