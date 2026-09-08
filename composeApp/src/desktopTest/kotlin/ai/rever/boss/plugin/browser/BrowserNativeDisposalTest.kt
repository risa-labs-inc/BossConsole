package ai.rever.boss.plugin.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserNativeDisposalTest {
    @Test
    fun `view detachment precedes native disposal even when detachment fails`() {
        val order = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            finishLocalBrowserDisposal(
                detachView = {
                    order += "detach"
                    error("view failure")
                },
                requestNativeClose = { order += "native" },
            )
        }
        assertEquals(listOf("detach", "native"), order)
    }

    /** #409's fast-drain case: the native close follows the actual return of the worker. */
    @Test
    fun `close follows a fast admitted call and runs only once`(): Unit =
        runBlocking {
            val executor = DrainingBrowserExecutor("test-drain-fast")
            val calls = AtomicInteger()
            val closes = AtomicInteger()
            executor.execute { calls.incrementAndGet() }
            val disposal =
                BrowserNativeDisposal(listOf(executor)) {
                    assertEquals(1, calls.get())
                    closes.incrementAndGet()
                }
            disposal.start()
            disposal.start()
            withTimeout(5_000) { disposal.awaitCompletion() }
            assertEquals(1, closes.get())
            assertFailsWith<RejectedExecutionException> { executor.execute {} }
        }

    /** #409's wedged case: bound the caller's wait, but never close on expiry of that bound. */
    @Test
    fun `wedged work leaves disposal pending without blocking its caller`() =
        runBlocking {
            val executor = DrainingBrowserExecutor("test-drain-wedge")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closes = AtomicInteger()
            executor.execute {
                entered.countDown()
                release.await()
            }
            val disposal = BrowserNativeDisposal(listOf(executor)) { closes.incrementAndGet() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                disposal.start()
                assertNull(
                    withTimeoutOrNull(100) {
                        disposal.awaitCompletion()
                        true
                    },
                )
                assertEquals(0, closes.get())
                release.countDown()
                withTimeout(5_000) { disposal.awaitCompletion() }
                assertEquals(1, closes.get())
            } finally {
                release.countDown()
                disposal.start()
            }
        }

    @Test
    fun `pending warning fires once and never permits early close`() =
        runBlocking {
            val executor = DrainingBrowserExecutor("test-drain-warning")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val warned = CompletableDeferred<Unit>()
            val warnings = AtomicInteger()
            val closes = AtomicInteger()
            executor.execute {
                entered.countDown()
                release.await()
            }
            val disposal =
                BrowserNativeDisposal(
                    listOf(executor),
                    warningAfterMs = 25,
                    reportPending = {
                        warnings.incrementAndGet()
                        warned.complete(Unit)
                    },
                ) { closes.incrementAndGet() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                disposal.start()
                withTimeout(5_000) { warned.await() }
                assertNull(
                    withTimeoutOrNull(100) {
                        disposal.awaitCompletion()
                        true
                    },
                )
                assertEquals(1, warnings.get())
                assertEquals(0, closes.get())
                release.countDown()
                withTimeout(5_000) { disposal.awaitCompletion() }
                assertEquals(1, closes.get())
            } finally {
                release.countDown()
                disposal.start()
            }
        }

    @Test
    fun `all owned workers must drain and multiple waiters see the same completion`() =
        runBlocking {
            val first = DrainingBrowserExecutor("test-drain-first")
            val second = DrainingBrowserExecutor("test-drain-second")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closes = AtomicInteger()
            second.execute {
                entered.countDown()
                release.await()
            }
            val disposal = BrowserNativeDisposal(listOf(first, second)) { closes.incrementAndGet() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                disposal.start()
                val waiter1 = async { disposal.awaitCompletion() }
                val waiter2 = async { disposal.awaitCompletion() }
                withTimeout(5_000) { first.awaitDrained() }
                assertNull(
                    withTimeoutOrNull(100) {
                        waiter1.await()
                        true
                    },
                )
                assertEquals(0, closes.get())
                release.countDown()
                withTimeout(5_000) {
                    waiter1.await()
                    waiter2.await()
                }
                assertEquals(1, closes.get())
            } finally {
                release.countDown()
                disposal.start()
            }
        }

    @Test
    fun `disposal requested on its own worker does not wait for itself`() =
        runBlocking {
            val executor = DrainingBrowserExecutor("test-drain-own-worker")
            val returned = CompletableDeferred<Unit>()
            val disposal = BrowserNativeDisposal(listOf(executor)) {}
            executor.execute {
                disposal.start()
                returned.complete(Unit)
            }
            withTimeout(5_000) {
                returned.await()
                disposal.awaitCompletion()
            }
        }

    @Test
    fun `idle executor closes without needing a worker thread`() =
        runBlocking {
            val executor = DrainingBrowserExecutor("test-drain-unused")
            val closes = AtomicInteger()
            val disposal = BrowserNativeDisposal(listOf(executor)) { closes.incrementAndGet() }
            disposal.start()
            withTimeout(5_000) { disposal.awaitCompletion() }
            assertEquals(1, closes.get())
            assertEquals(0, executor.largestPoolSize)
        }

    @Test
    fun `queued admitted work finishes before close`() =
        runBlocking {
            val executor = DrainingBrowserExecutor("test-drain-queue")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = AtomicInteger()
            executor.execute {
                entered.countDown()
                release.await()
                finished.incrementAndGet()
            }
            executor.execute { finished.incrementAndGet() }
            val disposal = BrowserNativeDisposal(listOf(executor)) { assertEquals(2, finished.get()) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                disposal.start()
                release.countDown()
                withTimeout(5_000) { disposal.awaitCompletion() }
            } finally {
                release.countDown()
                disposal.start()
            }
        }

    @Test
    fun `service waiter cancellation cannot cancel native close or profile release`() =
        runBlocking {
            val closeFinished = CompletableDeferred<Unit>()
            val releaseFinished = CompletableDeferred<Unit>()
            var detached = false
            val cleanup =
                disposeBrowserResources(
                    dispose = { detached = true },
                    awaitNativeClose = { closeFinished.await() },
                    release = { releaseFinished.complete(Unit) },
                )
            assertTrue(detached, "View detaches synchronously for window teardown")
            val waiter = launch { cleanup.await() }
            waiter.cancelAndJoin()
            assertFalse(cleanup.isCancelled)
            assertFalse(releaseFinished.isCompleted)
            closeFinished.complete(Unit)
            withTimeout(5_000) {
                releaseFinished.await()
                cleanup.await()
            }
        }

    @Test
    fun `local teardown failure still waits for native close before releasing profile`() =
        runBlocking {
            val closeFinished = CompletableDeferred<Unit>()
            val releaseFinished = CompletableDeferred<Unit>()
            val cleanup =
                disposeBrowserResources(
                    dispose = { error("view teardown failed") },
                    awaitNativeClose = { closeFinished.await() },
                    release = { releaseFinished.complete(Unit) },
                )
            assertFalse(releaseFinished.isCompleted)
            closeFinished.complete(Unit)
            assertFailsWith<IllegalStateException> { withTimeout(5_000) { cleanup.await() } }
            assertTrue(releaseFinished.isCompleted)
        }

    @Test
    fun `failed native close does not release a potentially live profile`() =
        runBlocking {
            val disposal = BrowserNativeDisposal(emptyList()) { error("native close failed") }
            val mutex = Mutex(locked = true)
            var released = false
            val cleanup =
                disposeBrowserResources(disposal::start, disposal::awaitCompletion) {
                    released = true
                    mutex.unlock()
                }
            try {
                assertFailsWith<IllegalStateException> { withTimeout(5_000) { cleanup.await() } }
                assertFalse(released)
                assertFailsWith<IllegalStateException> { acquireManagedProfileLock(mutex, timeoutMs = 25) }
                assertTrue(mutex.isLocked)
            } finally {
                mutex.unlock()
            }
        }
}
