package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.callback.StartCaptureSessionCallback
import com.teamdev.jxbrowser.capture.CaptureSource
import com.teamdev.jxbrowser.capture.CaptureSources
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenCaptureNotifierTest {
    // Every member overridden: CaptureSources' default bodies cast `this` to JxBrowser's internal
    // implementation, so an empty `object : CaptureSources {}` throws ClassCastException on first use.
    private val sources =
        object : CaptureSources {
            override fun browsers(): List<CaptureSource> = emptyList()

            override fun screens(): List<CaptureSource> = emptyList()

            override fun applicationWindows(): List<CaptureSource> = emptyList()
        }

    @After
    fun clearPendingRequest() {
        ScreenCaptureNotifier.captureRequest.value?.let { ScreenCaptureNotifier.cancel(it.requestId) }
    }

    @Test
    fun `replacement cancels the native callback and stale cancellation preserves the new one`() {
        var firstAnswers = 0
        var secondAnswers = 0
        ScreenCaptureNotifier.requestCapture("first", sources, StartCaptureSessionCallback.Action { firstAnswers++ })
        ScreenCaptureNotifier.requestCapture("second", sources, StartCaptureSessionCallback.Action { secondAnswers++ })

        assertEquals(1, firstAnswers)
        assertEquals(0, secondAnswers)
        ScreenCaptureNotifier.cancel("first")
        assertEquals("second", ScreenCaptureNotifier.captureRequest.value?.requestId)
        ScreenCaptureNotifier.cancel("second")
        ScreenCaptureNotifier.cancel("second")
        assertEquals(1, secondAnswers)
    }

    @Test
    fun `a failed superseded native callback cannot escape publication of the replacement`() {
        ScreenCaptureNotifier.requestCapture(
            "closed-peer",
            sources,
            StartCaptureSessionCallback.Action { error("native peer closed") },
        )
        var replacementAnswers = 0
        ScreenCaptureNotifier.requestCapture(
            "replacement",
            sources,
            StartCaptureSessionCallback.Action { replacementAnswers++ },
        )

        assertFalse(ScreenCaptureNotifier.hasPendingRequest("closed-peer"))
        assertTrue(ScreenCaptureNotifier.hasPendingRequest("replacement"))
        ScreenCaptureNotifier.cancel("replacement")
        assertEquals(1, replacementAnswers)
    }
}
