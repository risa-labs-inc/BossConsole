package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #312's caller/native lifetime cases, exercised through the actual bounded worker and disposal. */
class BrowserHandleExecuteJavaScriptTest {
    @Test
    fun `caller timeout does not permit closing a still-running native call`() =
        runBlocking {
            val call = BoundedBrowserCall("test-js-cancelled-waiter")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closes = AtomicInteger()
            val disposal = BrowserNativeDisposal(listOf(call.executor)) { closes.incrementAndGet() }
            try {
                assertNull(
                    call.call<String>(timeoutMs = 100) {
                        entered.countDown()
                        release.await()
                        "late-result"
                    },
                )
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
    fun `native failures finish tracking and retain the null result contract`() =
        runBlocking {
            val failures =
                listOf(
                    IllegalArgumentException("empty script"),
                    IllegalStateException("closed"),
                    AssertionError("native failure"),
                )
            failures.forEach { failure ->
                val call = BoundedBrowserCall("test-js-failed-call")
                var reported: Throwable? = null
                val result = call.call<String>(onError = { reported = it }) { throw failure }
                assertNull(result)
                assertEquals(failure, reported)
                val closes = AtomicInteger()
                val disposal = BrowserNativeDisposal(listOf(call.executor)) { closes.incrementAndGet() }
                disposal.start()
                withTimeout(5_000) { disposal.awaitCompletion() }
                assertEquals(1, closes.get())
            }
        }
}
