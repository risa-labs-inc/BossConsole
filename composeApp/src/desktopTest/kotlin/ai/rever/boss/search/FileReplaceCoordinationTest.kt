package ai.rever.boss.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The locking primitive behind BossConsole#622's fix - [ContentSearchService]'s closed-file
 * replace path serializes its whole read/compute/persist transaction through
 * [FileReplaceCoordination.withFileLock] rather than exercising the primitive only indirectly
 * through file I/O, which cannot deterministically prove mutual exclusion without sleeps.
 *
 * Every test here runs on `runBlocking`'s own single-threaded event-loop dispatcher and never
 * specifies another one, so scheduling is deterministic: a coroutine started with `async`/
 * `launch` is merely enqueued, and only actually runs up to its own next suspension point once
 * the currently-running coroutine itself suspends (here, via [kotlinx.coroutines.yield] or an
 * `await` on a [CompletableDeferred]). That is what makes `assertFalse(second.isCompleted, ...)`
 * right after a `yield()` a real proof rather than a timing guess.
 */
class FileReplaceCoordinationTest {
    @Test
    fun `two calls for the same key are mutually exclusive`() =
        runBlocking {
            withTimeout(5_000) {
                val order = mutableListOf<String>()
                val firstEntered = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()

                val first =
                    async {
                        FileReplaceCoordination.withFileLock("same-key") {
                            order.add("first-enter")
                            firstEntered.complete(Unit)
                            releaseFirst.await()
                            order.add("first-exit")
                        }
                    }
                firstEntered.await()

                val second =
                    async {
                        FileReplaceCoordination.withFileLock("same-key") {
                            order.add("second-enter")
                        }
                    }
                // Hand control to the dispatcher's queue: `second` (enqueued first, by
                // `async`) runs up to its own first suspension point before this coroutine
                // resumes. A correctly exclusive lock leaves it parked on the mutex, never
                // having reached "second-enter" - a buggy, non-exclusive one would run it
                // to completion in this same turn, with nothing to await.
                yield()
                assertFalse(second.isCompleted, "second call entered the critical section while first still held it")

                releaseFirst.complete(Unit)
                first.await()
                second.await()

                assertEquals(listOf("first-enter", "first-exit", "second-enter"), order)
            }
        }

    @Test
    fun `two calls for different keys do not wait on each other`() =
        runBlocking {
            withTimeout(5_000) {
                val aEntered = CompletableDeferred<Unit>()
                val bEntered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                val a =
                    async {
                        FileReplaceCoordination.withFileLock("key-a") {
                            aEntered.complete(Unit)
                            release.await()
                        }
                    }
                val b =
                    async {
                        FileReplaceCoordination.withFileLock("key-b") {
                            bEntered.complete(Unit)
                            release.await()
                        }
                    }
                // If different keys were serialized onto one lock, `b` could never reach
                // its own critical section while `a` holds "key-a" and awaits `release` -
                // this would hang (and time out) rather than complete.
                aEntered.await()
                bEntered.await()

                release.complete(Unit)
                a.await()
                b.await()
            }
        }

    @Test
    fun `cancelling a caller releases the lock for the next one`() =
        runBlocking {
            withTimeout(5_000) {
                val entered = CompletableDeferred<Unit>()
                val job =
                    launch {
                        FileReplaceCoordination.withFileLock("cancel-key") {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                entered.await()
                job.cancelAndJoin()

                // If the lock were still held after cancellation, this would hang.
                val ran = withTimeoutOrNull(2_000) { FileReplaceCoordination.withFileLock("cancel-key") { true } }
                assertEquals(true, ran, "a cancelled holder left the lock unreleased")
            }
        }

    @Test
    fun `a block that throws still releases the lock for the next caller`() =
        runBlocking {
            withTimeout(5_000) {
                assertFailsWith<IllegalStateException> {
                    FileReplaceCoordination.withFileLock("throw-key") { error("boom") }
                }

                // If the failed transaction had leaked the lock, this would hang.
                val ran = withTimeoutOrNull(2_000) { FileReplaceCoordination.withFileLock("throw-key") { true } }
                assertEquals(true, ran, "a thrown block left the lock unreleased")
            }
        }

    @Test
    fun `three callers for one key are admitted in FIFO order`() =
        runBlocking {
            withTimeout(5_000) {
                val order = mutableListOf<String>()
                val aEntered = CompletableDeferred<Unit>()
                val releaseA = CompletableDeferred<Unit>()
                val releaseB = CompletableDeferred<Unit>()

                val a =
                    async {
                        FileReplaceCoordination.withFileLock("fifo-key") {
                            order.add("a-enter")
                            aEntered.complete(Unit)
                            releaseA.await()
                            order.add("a-exit")
                        }
                    }
                aEntered.await()

                // b queues behind a; c queues behind b. Each yield() lets the just-launched
                // coroutine run up to its own suspension point (on the mutex) before the
                // next one is created, so the queue order is deterministic, not incidental.
                val b =
                    async {
                        FileReplaceCoordination.withFileLock("fifo-key") {
                            order.add("b-enter")
                            releaseB.await()
                            order.add("b-exit")
                        }
                    }
                yield()

                val c =
                    async {
                        FileReplaceCoordination.withFileLock("fifo-key") {
                            order.add("c-enter")
                        }
                    }
                yield()

                releaseA.complete(Unit)
                a.await()
                assertTrue("b-enter" in order, "b did not acquire once a released")
                assertTrue("c-enter" !in order, "c was admitted before b - not FIFO")

                releaseB.complete(Unit)
                b.await()
                c.await()

                assertEquals(listOf("a-enter", "a-exit", "b-enter", "b-exit", "c-enter"), order)
            }
        }

    @Test
    fun `cancelling a queued waiter does not block the caller queued behind it`() =
        runBlocking {
            withTimeout(5_000) {
                val holderEntered = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder =
                    launch {
                        FileReplaceCoordination.withFileLock("waiter-cancel-key") {
                            holderEntered.complete(Unit)
                            releaseHolder.await()
                        }
                    }
                holderEntered.await()

                // Queues behind the holder, then is cancelled before it ever acquires.
                val waiter =
                    launch {
                        FileReplaceCoordination.withFileLock("waiter-cancel-key") {
                            error("must never run: cancelled while still queued")
                        }
                    }
                yield()
                waiter.cancelAndJoin()

                // Queues behind the now-cancelled waiter.
                val newcomerEntered = CompletableDeferred<Unit>()
                val newcomer =
                    launch {
                        FileReplaceCoordination.withFileLock("waiter-cancel-key") {
                            newcomerEntered.complete(Unit)
                        }
                    }
                yield()

                releaseHolder.complete(Unit)
                holder.join()

                // If the cancelled waiter had corrupted the slot's refcount or left the mutex
                // thinking a cancelled acquire still holds a place in line, this hangs.
                newcomerEntered.await()
                newcomer.join()
            }
        }
}
