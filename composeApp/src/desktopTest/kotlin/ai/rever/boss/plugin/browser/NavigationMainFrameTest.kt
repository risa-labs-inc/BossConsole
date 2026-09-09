package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.ObjectClosedException
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.frame.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Uses JxBrowser's real API types, but no Chromium process or native crash reproduction. */
class NavigationMainFrameTest {
    private fun browser(mainFrame: () -> Optional<Frame>): Browser =
        Proxy.newProxyInstance(Browser::class.java.classLoader, arrayOf(Browser::class.java)) { _, method, _ ->
            when (method.name) {
                "isClosed" -> false
                "mainFrame" -> mainFrame()
                else -> error("unexpected Browser call: ${method.name}")
            }
        } as Browser

    private fun frame(): Frame =
        Proxy.newProxyInstance(Frame::class.java.classLoader, arrayOf(Frame::class.java)) { _, method, _ ->
            error("unexpected Frame call: ${method.name}")
        } as Frame

    @Test
    fun `closing after a valid check clears PID and cancels the previous injection`() {
        val scope = CoroutineScope(SupervisorJob())
        val pid = RendererPid()
        val navigation = NavigationPageInjection(pid, scope, Dispatchers.Unconfined) { throw it }
        val release = CompletableDeferred<Unit>()
        var started = false
        var stopped = false
        var resumed = false
        var acquisitions = 0
        try {
            navigation.onCommit(
                "https://old.example.test/",
                { frame() },
                { 17 },
                {
                    started = true
                    try {
                        release.await()
                        resumed = true
                    } finally {
                        stopped = true
                    }
                },
            )
            assertTrue(started)
            assertEquals(17, pid.value)
            val closingBrowser =
                browser {
                    acquisitions++
                    assertNull(pid.value, "cleanup must precede the throwing native access")
                    assertTrue(stopped, "the old injection must already be cancelled")
                    throw ObjectClosedException()
                }
            navigation.onCommit(
                "https://new.example.test/",
                {
                    // The host's validity check can return true just before the native call fails.
                    assertFalse(closingBrowser.isClosed)
                    navigationMainFrameOrNull(closingBrowser)
                },
                { error("closed browser must not read a PID") },
                { error("closed browser must not inject") },
            )
            release.complete(Unit)
            assertEquals(1, acquisitions)
            assertNull(pid.value)
            assertTrue(stopped)
            assertFalse(resumed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `ordinary frame is returned unchanged`() {
        val expected = frame()
        assertSame(expected, navigationMainFrameOrNull(browser { Optional.of(expected) }))
    }

    @Test
    fun `absent native frame stays absent`() {
        assertNull(navigationMainFrameOrNull(browser { Optional.empty() }))
    }

    @Test
    fun `wrapped closed objects and closed connections report terminal closure`() {
        val failures =
            listOf(
                ObjectClosedException(),
                IllegalStateException("Failed to receive the response.", ObjectClosedException()),
                IllegalStateException(
                    "Failed to receive the response.",
                    IllegalStateException("The connection has been closed."),
                ),
            )
        for (failure in failures) {
            var closed = 0
            assertNull(navigationMainFrameOrNull(browser { throw failure }) { closed++ })
            assertEquals(1, closed)
        }
    }

    @Test
    fun `unanswered round trip is not mistaken for terminal closure`() {
        val failure = IllegalStateException("Failed to receive the response.")
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                navigationMainFrameOrNull(browser { throw failure }) { error("live transport must not be invalidated") }
            },
        )
    }

    @Test
    fun `cancellation is preserved even with a closed object cause`() {
        val cancellation = CancellationException("cancel lookup").apply { initCause(ObjectClosedException()) }
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                navigationMainFrameOrNull(browser { throw cancellation }) {
                    error("cancellation is not transport death")
                }
            },
        )
    }

    @Test
    fun `unrelated lookup exceptions escape`() {
        val failure = IllegalArgumentException("unrelated lookup bug")
        assertSame(
            failure,
            assertFailsWith<IllegalArgumentException> { navigationMainFrameOrNull(browser { throw failure }) },
        )
    }

    @Test
    fun `lookup cancellation escapes`() {
        val cancellation = CancellationException("cancel lookup")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> { navigationMainFrameOrNull(browser { throw cancellation }) },
        )
    }

    @Test
    fun `lookup errors escape`() {
        val failure = AssertionError("unrelated lookup error")
        assertSame(
            failure,
            assertFailsWith<AssertionError> { navigationMainFrameOrNull(browser { throw failure }) },
        )
    }
}
