package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.frame.Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserHandleExecuteJavaScriptTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `executeJavaScript respects coroutine cancellation without blocking`() = runTest {
        var consumerFired = false
        var savedConsumer: Consumer<Any?>? = null

        val frame = Proxy.newProxyInstance(
            Frame::class.java.classLoader,
            arrayOf(Frame::class.java)
        ) { _, method, args ->
            when (method.name) {
                "executeJavaScript" -> {
                    if (args.size == 2 && args[1] is Consumer<*>) {
                        @Suppress("UNCHECKED_CAST")
                        savedConsumer = args[1] as Consumer<Any?>
                    }
                    null // Method returns void
                }
                "toString" -> "MockFrame"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.get(0)
                else -> null
            }
        } as Frame

        val browser = Proxy.newProxyInstance(
            Browser::class.java.classLoader,
            arrayOf(Browser::class.java)
        ) { _, method, args ->
            when (method.name) {
                "mainFrame" -> Optional.of(frame)
                "isClosed" -> false
                "toString" -> "MockBrowser"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.get(0)
                else -> null
            }
        } as Browser

        val result = runCatching {
            withTimeout(100) {
                browser.executeJavaScriptSuspending("fakeScript")
            }
        }

        // Must fail with timeout exception
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)

        // Simulating the JxBrowser IPC callback arriving LATE after the coroutine has already cancelled.
        // It must NOT throw an exception inside the callback (cont.isActive check protects it).
        savedConsumer?.accept("late-result")
        consumerFired = true

        assertTrue(consumerFired)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `executeJavaScript returns result normally`() = runTest {
        val frame = Proxy.newProxyInstance(
            Frame::class.java.classLoader,
            arrayOf(Frame::class.java)
        ) { _, method, args ->
            when (method.name) {
                "executeJavaScript" -> {
                    if (args.size == 2 && args[1] is Consumer<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val consumer = args[1] as Consumer<Any?>
                        // Fire callback immediately
                        consumer.accept("success-result")
                    }
                    null
                }
                "toString" -> "MockFrame"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.get(0)
                else -> null
            }
        } as Frame

        val browser = Proxy.newProxyInstance(
            Browser::class.java.classLoader,
            arrayOf(Browser::class.java)
        ) { _, method, args ->
            when (method.name) {
                "mainFrame" -> Optional.of(frame)
                "isClosed" -> false
                "toString" -> "MockBrowser"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.get(0)
                else -> null
            }
        } as Browser

        val result = browser.executeJavaScriptSuspending("fakeScript")
        assertEquals("success-result", result)
    }
}
