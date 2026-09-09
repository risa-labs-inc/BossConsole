package ai.rever.boss.plugin.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Deterministic commit/lifecycle tests. No native browser, engine, sleeps or network. */
class NavigationPageInjectionTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class Fixture : AutoCloseable {
        val pid = RendererPid()
        val dispatcher = QueuedDispatcher()
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, error -> failures += error })
        val navigation = NavigationPageInjection(pid, scope, dispatcher) { failures += it }
        val reads = mutableListOf<Int>()
        val injections = mutableListOf<Int>()

        fun commit(
            frame: Int?,
            url: String = "https://example.test/",
        ) {
            navigation.onCommit(
                url,
                { frame },
                {
                    reads += it
                    it
                },
                { injections += it },
            )
        }

        override fun close() {
            scope.cancel()
            dispatcher.drain()
        }
    }

    @Test
    fun `successful commit clears first then captures once and defers follow-up`() {
        Fixture().use { f ->
            f.pid.onCommit(17)
            var acquisitions = 0
            f.navigation.onCommit(
                url = "https://example.test/",
                mainFrame = {
                    assertNull(f.pid.value, "cleanup must happen before a native call that can throw")
                    acquisitions++
                    23
                },
                readPid = {
                    f.reads += it
                    it
                },
                inject = { f.injections += it },
            )
            assertEquals(1, acquisitions)
            assertNull(f.pid.value)
            assertTrue(f.reads.isEmpty(), "follow-up must not run on the acquisition callback")
            assertTrue(f.injections.isEmpty())
            f.dispatcher.drain()
            assertEquals(23, f.pid.value)
            assertEquals(listOf(23), f.reads)
            assertEquals(listOf(23), f.injections)
        }
    }

    @Test
    fun `absent frame supersedes queued work and leaves PID unknown`() {
        Fixture().use { f ->
            f.commit(17)
            f.pid.onCommit(11)
            f.commit(null)
            assertNull(f.pid.value)
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertTrue(f.reads.isEmpty())
            assertTrue(f.injections.isEmpty())
        }
    }

    @Test
    fun `about blank and empty URL still refresh PID without injecting`() {
        for (url in listOf("about:blank", "")) {
            Fixture().use { f ->
                f.commit(17)
                f.commit(23, url)
                assertNull(f.pid.value)
                f.dispatcher.drain()
                assertEquals(23, f.pid.value)
                assertEquals(listOf(23), f.reads)
                assertTrue(f.injections.isEmpty())
            }
        }
    }

    @Test
    fun `unknown PID does not retain the previous renderer or prevent ordinary injection`() {
        Fixture().use { f ->
            f.pid.onCommit(17)
            f.navigation.onCommit("https://example.test/", { 23 }, { null }, { f.injections += it })
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertEquals(listOf(23), f.injections)
        }
    }

    @Test
    fun `only latest queued commit reads and injects`() {
        Fixture().use { f ->
            f.commit(17)
            f.commit(23)
            f.dispatcher.drain()
            assertEquals(23, f.pid.value)
            assertEquals(listOf(23), f.reads)
            assertEquals(listOf(23), f.injections)
        }
    }

    @Test
    fun `slower frame acquisition cannot replace a newer commit`() {
        Fixture().use { f ->
            f.navigation.onCommit(
                "https://old.example.test/",
                {
                    f.commit(23)
                    17
                },
                {
                    f.reads += it
                    it
                },
                { f.injections += it },
            )
            f.dispatcher.drain()
            assertEquals(23, f.pid.value)
            assertEquals(listOf(23), f.reads)
            assertEquals(listOf(23), f.injections)
        }
    }

    @Test
    fun `renderer death during acquisition discards that frame but permits a later reload`() {
        Fixture().use { f ->
            f.navigation.onCommit(
                "https://example.test/",
                {
                    f.navigation.onGone()
                    17
                },
                {
                    f.reads += it
                    it
                },
                { f.injections += it },
            )
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertTrue(f.reads.isEmpty())
            assertTrue(f.injections.isEmpty())
            f.commit(23)
            f.dispatcher.drain()
            assertEquals(23, f.pid.value)
            assertEquals(listOf(23), f.injections)
        }
    }

    @Test
    fun `renderer death cancels queued work and clears the known PID`() {
        Fixture().use { f ->
            f.commit(17)
            f.pid.onCommit(11)
            f.navigation.onGone()
            assertNull(f.pid.value)
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertTrue(f.reads.isEmpty())
            assertTrue(f.injections.isEmpty())
        }
    }

    @Test
    fun `scope cancellation prevents a queued follow-up from running`() {
        Fixture().use { f ->
            f.commit(17)
            f.scope.cancel()
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertTrue(f.reads.isEmpty())
            assertTrue(f.injections.isEmpty())
        }
    }

    @Test
    fun `failed acquisition propagates after invalidating the previous work`() {
        Fixture().use { f ->
            f.commit(17)
            f.pid.onCommit(11)
            val failure = IllegalStateException("unrelated lookup bug")
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    f.navigation.onCommit<Int>(
                        "https://example.test/",
                        { throw failure },
                        { it },
                        { f.injections += it },
                    )
                },
            )
            assertNull(f.pid.value)
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertTrue(f.injections.isEmpty())
        }
    }

    @Test
    fun `acquisition cancellation is not swallowed`() {
        Fixture().use { f ->
            val cancellation = CancellationException("cancel lookup")
            assertSame(
                cancellation,
                assertFailsWith<CancellationException> {
                    f.navigation.onCommit<Int>("https://example.test/", { throw cancellation }, { it }, {})
                },
            )
        }
    }

    @Test
    fun `PID read failures are reported rather than swallowed`() {
        Fixture().use { f ->
            val failure = AssertionError("unexpected PID bug")
            f.navigation.onCommit("https://example.test/", { 17 }, { throw failure }, { f.injections += it })
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertSame(failure, f.failures.single())
            assertTrue(f.injections.isEmpty())
        }
    }

    @Test
    fun `optional PID failure reports unknown and still installs page helpers`() {
        Fixture().use { f ->
            f.pid.onCommit(11)
            val failure = IllegalStateException("PID unavailable")
            f.navigation.onCommit("https://example.test/", { 17 }, { throw failure }, { f.injections += it })
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertSame(failure, f.failures.single())
            assertEquals(listOf(17), f.injections)
        }
    }

    @Test
    fun `PID read cancellation does not report a failure or install helpers`() {
        Fixture().use { f ->
            val cancellation = CancellationException("superseded read")
            f.navigation.onCommit("https://example.test/", { 17 }, { throw cancellation }, { f.injections += it })
            f.dispatcher.drain()
            assertNull(f.pid.value)
            assertTrue(f.failures.isEmpty())
            assertTrue(f.injections.isEmpty())
        }
    }

    @Test
    fun `injection failures remain observable after recording the current PID`() {
        Fixture().use { f ->
            val failure = IllegalArgumentException("unrelated injection bug")
            f.navigation.onCommit("https://example.test/", { 17 }, { it }, { throw failure })
            f.dispatcher.drain()
            assertEquals(17, f.pid.value)
            assertSame(failure, f.failures.single())
        }
    }

    @Test
    fun `an absent replacement cancels a suspended injection before lookup`() {
        Fixture().use { f ->
            val release = CompletableDeferred<Unit>()
            var stopped = false
            var resumed = false
            f.navigation.onCommit(
                "https://old.example.test/",
                { 17 },
                { it },
                {
                    try {
                        release.await()
                        resumed = true
                    } finally {
                        stopped = true
                    }
                },
            )
            f.dispatcher.drain()
            assertEquals(17, f.pid.value)
            assertFalse(stopped)
            f.navigation.onCommit<Int>(
                "https://new.example.test/",
                {
                    assertNull(f.pid.value)
                    null
                },
                { error("absent frame must not read a PID") },
                { error("absent frame must not inject") },
            )
            release.complete(Unit)
            f.dispatcher.drain()
            assertTrue(stopped)
            assertFalse(resumed)
            assertNull(f.pid.value)
        }
    }

    @Test
    fun `a superseded blocking PID read cannot publish or start injection`() {
        checkBlockedRead(replacementFrame = 23)
    }

    @Test
    fun `an absent replacement frame still cancels a blocking predecessor`() {
        checkBlockedRead(replacementFrame = null)
    }

    /** A real executor and latches model a native call that does not observe cancellation. */
    private fun checkBlockedRead(replacementFrame: Int?) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "test-page-injection").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, error -> failures += error })
        val pid = RendererPid()
        val navigation = NavigationPageInjection(pid, scope, dispatcher) { failures += it }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val injections = CopyOnWriteArrayList<Int>()
        try {
            navigation.onCommit(
                "https://old.example.test/",
                { 17 },
                {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "test did not release the blocked PID read" }
                    it
                },
                { injections += it },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS), "old PID read never started")
            navigation.onCommit("https://new.example.test/", { replacementFrame }, { it }, { injections += it })
            assertNull(pid.value, "supersession must not wait for the blocked call")
            release.countDown()
            // FIFO executor barrier, not a sleep: both previously queued coroutines have returned.
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(replacementFrame, pid.value)
            assertEquals(listOfNotNull(replacementFrame), injections.toList())
            assertTrue(failures.isEmpty(), failures.toString())
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "test executor failed to stop")
        }
    }
}
