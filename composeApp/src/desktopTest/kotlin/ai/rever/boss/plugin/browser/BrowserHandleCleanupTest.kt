package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserHandleCleanupTest {
    @Test
    fun `failed early resource release does not skip later releases or native request`() {
        val order = mutableListOf<String>()
        val disposed = AtomicBoolean(false)
        val owner = BrowserHandleCleanup(disposed, "test")
        repeat(2) {
            owner.run(
                teardown = { cleanup ->
                    assertTrue(disposed.get(), "Admission closes before resource release")
                    cleanup.run("release audio") {
                        order += "audio"
                        error("audio callback failed")
                    }
                    cleanup.run("stop capture") { order += "capture" }
                    cleanup.run("unsubscribe") { order += "unsubscribe" }
                },
                detachView = { order += "detach" },
                requestNativeClose = { order += "native" },
            )
        }
        assertEquals(listOf("audio", "capture", "unsubscribe", "detach", "native"), order)
    }

    @Test
    fun `unexpected local failure and failed detach still request native disposal once`() {
        val order = mutableListOf<String>()
        val owner = BrowserHandleCleanup(AtomicBoolean(false), "test")
        repeat(2) {
            owner.run(
                teardown = {
                    order += "local"
                    error("local failure")
                },
                detachView = {
                    order += "detach"
                    error("closed view")
                },
                requestNativeClose = { order += "native" },
            )
        }
        assertEquals(listOf("local", "detach", "native"), order)
    }

    @Test
    fun `local cleanup failure preserves native call drain`(): Unit =
        runBlocking {
            val executor = DrainingBrowserExecutor("test-cleanup-drain")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val nativeClosed = AtomicBoolean(false)
            val detached = AtomicBoolean(false)
            executor.execute {
                entered.countDown()
                release.await()
            }
            val nativeDisposal =
                BrowserNativeDisposal(listOf(executor)) {
                    assertTrue(detached.get())
                    nativeClosed.set(true)
                }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                BrowserHandleCleanup(AtomicBoolean(false), "test").run(
                    teardown = { error("early cleanup failure") },
                    detachView = { detached.set(true) },
                    requestNativeClose = { nativeDisposal.start() },
                )
                assertTrue(detached.get())
                assertTrue(executor.isShutdown)
                assertEquals(false, nativeClosed.get(), "Running native work must retain its browser")
            } finally {
                release.countDown()
                nativeDisposal.start()
                withTimeout(5_000) { nativeDisposal.awaitCompletion() }
            }
            assertTrue(nativeClosed.get())
        }
}
