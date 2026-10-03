package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppCaptureQualityTest {
    @Test fun `popup output size follows logical bounds across display density changes`() {
        val popup = WindowCaptureGeometry(1, 800, 600, 400, 300, 100, 100)
        val samePopupOnLowDensityDisplay = popup.copy(width = 400, height = 300)
        val samePopupOnFractionalDensityDisplay = popup.copy(width = 600, height = 450)
        for (geometry in listOf(popup, samePopupOnLowDensityDisplay, samePopupOnFractionalDensityDisplay)) {
            assertEquals(AppCaptureFrameSize(400, 300), appCaptureSurfaceFrameSize(geometry, 1920, 1920))
            assertEquals(AppCaptureFrameSize(200, 150), appCaptureSurfaceFrameSize(geometry, 1920, 960))
            // A high-density root scales every owned surface uniformly, including a low-density popup.
            assertEquals(AppCaptureFrameSize(800, 600), appCaptureSurfaceFrameSize(geometry, 960, 1920))
        }
    }

    @Test fun `sustained capture overload reduces resolution with a bounded floor`() {
        val quality = AppCaptureQuality()
        val high = quality.maxDimension
        quality.recordCapture(TimeUnit.MILLISECONDS.toNanos(100))
        assertEquals(high, quality.maxDimension, "One slow frame must not lower quality")
        repeat(100) { quality.recordCapture(TimeUnit.MILLISECONDS.toNanos(100)) }
        assertTrue(quality.maxDimension < high)
        assertEquals(960, quality.maxDimension)
        repeat(100) { quality.recordCapture(TimeUnit.MILLISECONDS.toNanos(100)) }
        assertEquals(960, quality.maxDimension, "Capture must not shrink without a bound")
    }

    @Test fun `resolution recovery requires sustained headroom and never exceeds the starting quality`() {
        val quality = AppCaptureQuality()
        repeat(100) { quality.recordCapture(TimeUnit.MILLISECONDS.toNanos(100)) }
        val low = quality.maxDimension
        repeat(100) { quality.recordCapture(TimeUnit.MILLISECONDS.toNanos(5)) }
        assertEquals(low, quality.maxDimension)
        // Moderate load interrupts recovery instead of making the resolution oscillate.
        quality.recordCapture(TimeUnit.MILLISECONDS.toNanos(25))
        repeat(100) { quality.recordCapture(TimeUnit.MILLISECONDS.toNanos(5)) }
        assertEquals(low, quality.maxDimension)
        repeat(1000) { quality.recordCapture(TimeUnit.MILLISECONDS.toNanos(5)) }
        assertEquals(1920, quality.maxDimension)
        assertTrue(quality.frameIntervalNanos <= TimeUnit.MILLISECONDS.toNanos(34))
    }

    @Test fun `capture dimensions preserve aspect ratio without enlarging small windows`() {
        assertEquals(AppCaptureFrameSize(1920, 1106), appCaptureFrameSize(3024, 1742, 1920))
        assertEquals(AppCaptureFrameSize(960, 553), appCaptureFrameSize(3024, 1742, 960))
        assertEquals(AppCaptureFrameSize(553, 960), appCaptureFrameSize(1742, 3024, 960))
        assertEquals(AppCaptureFrameSize(480, 320), appCaptureFrameSize(480, 320, 1920))
    }
}
