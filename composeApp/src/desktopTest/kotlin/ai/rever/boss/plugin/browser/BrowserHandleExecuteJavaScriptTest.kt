package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.frame.Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserHandleExecuteJavaScriptTest {

    // TEST A — cancellation lifecycle
    @Test
    fun `executeJavaScript respects coroutine cancellation without blocking`() = runTest {
        var consumerFired = false
        var savedConsumer: Consumer<Any?>? = null
        var beginCalled = false
        var endCalled = false

        val frame = Proxy.newProxyInstance(
            Frame::class.java.classLoader,
            arrayOf(Frame::class.java)
        ) { _, method, args ->
            if (method.name == "executeJavaScript") {
                @Suppress("UNCHECKED_CAST")
                savedConsumer = args[1] as Consumer<Any?>
                null
            } else null
        } as Frame

        val browser = Proxy.newProxyInstance(
            Browser::class.java.classLoader,
            arrayOf(Browser::class.java)
        ) { _, method, _ ->
            if (method.name == "mainFrame") Optional.of(frame) else null
        } as Browser

        val result = runCatching {
            withTimeout(100) {
                browser.executeJavaScriptSuspending(
                    script = "fakeScript",
                    beginOp = { beginCalled = true; true },
                    endOp = { endCalled = true }
                )
            }
        }

        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(beginCalled, "beginOp should be called (count = 1)")
        assertFalse(endCalled, "endOp should not be called yet since callback is pending")

        savedConsumer?.accept("late-result")
        consumerFired = true

        assertTrue(consumerFired)
        assertTrue(endCalled, "endOp must be called when late callback fires (count = 0)")
    }

    // TEST B — await actually suspends
    @Test
    fun `awaitPendingNativeOperations actually suspends until callback`() = runTest {
        val tracker = NativeOperationTracker(AtomicBoolean(false))
        assertTrue(tracker.beginNativeOperation())

        var awaitCompleted = false
        val awaitJob = launch {
            tracker.awaitPendingNativeOperations()
            awaitCompleted = true
        }

        // Yield to allow awaitJob to suspend
        delay(50)
        assertFalse(awaitCompleted, "await should remain suspended while operation is pending")

        tracker.endNativeOperation() // Simulate callback
        awaitJob.join()
        assertTrue(awaitCompleted, "await should complete after callback")
    }

    // TEST C — operation rejected after prepareForDisposal
    @Test
    fun `operation rejected after prepareForDisposal`() = runTest {
        val tracker = NativeOperationTracker(AtomicBoolean(false))
        tracker.prepareForDisposal()
        val admitted = tracker.beginNativeOperation()
        assertFalse(admitted, "Operation must be rejected after prepareForDisposal")

        // Wait should complete immediately since nothing was admitted
        withTimeout(1000) { tracker.awaitPendingNativeOperations() }
    }

    // TEST D — disposal race
    @Test
    fun `deterministic disposal race interleaving`() = runTest {
        val tracker = NativeOperationTracker(AtomicBoolean(false))

        // 1. One native operation is pending
        assertTrue(tracker.beginNativeOperation())

        // 2. Disposal calls prepareForDisposal
        tracker.prepareForDisposal()

        // 3. Second executeJavaScript attempts admission -> rejected
        assertFalse(tracker.beginNativeOperation())

        // 4. Disposal waits
        var disposalContinued = false
        val disposalJob = launch {
            tracker.awaitPendingNativeOperations()
            disposalContinued = true
        }

        delay(50)
        assertFalse(disposalContinued, "Disposal must wait for the first operation")

        // 5. First callback fires
        tracker.endNativeOperation()

        // 6. Disposal continues
        disposalJob.join()
        assertTrue(disposalContinued)
    }

    // TEST E — multiple awaiters
    @Test
    fun `multiple concurrent awaiters safely resume`() = runTest {
        val tracker = NativeOperationTracker(AtomicBoolean(false))
        assertTrue(tracker.beginNativeOperation())

        var awaiter1Done = false
        var awaiter2Done = false

        val job1 = launch { tracker.awaitPendingNativeOperations(); awaiter1Done = true }
        val job2 = launch { tracker.awaitPendingNativeOperations(); awaiter2Done = true }

        delay(50)
        assertFalse(awaiter1Done)
        assertFalse(awaiter2Done)

        tracker.endNativeOperation()

        job1.join()
        job2.join()

        assertTrue(awaiter1Done)
        assertTrue(awaiter2Done)
    }

    // TEST F — synchronous native failure
    @Test
    fun `synchronous native failure clears pending state`() = runTest {
        var endCallCount = 0
        val frame = Proxy.newProxyInstance(
            Frame::class.java.classLoader,
            arrayOf(Frame::class.java)
        ) { _, method, _ ->
            if (method.name == "executeJavaScript") {
                throw IllegalStateException("Synchronous failure")
            }
            null
        } as Frame

        val browser = Proxy.newProxyInstance(
            Browser::class.java.classLoader,
            arrayOf(Browser::class.java)
        ) { _, method, _ ->
            if (method.name == "mainFrame") Optional.of(frame) else null
        } as Browser

        val result = runCatching {
            browser.executeJavaScriptSuspending(
                script = "fakeScript",
                beginOp = { true },
                endOp = { endCallCount++ }
            )
        }

        assertTrue(
            result.isSuccess,
            "executeJavaScriptSuspending should convert synchronous exceptions to success(null)"
        )
        assertNull(result.getOrNull(), "Result should be null on synchronous failure")
        assertEquals(1, endCallCount, "endOp must be called exactly once on synchronous throw")
    }
}
