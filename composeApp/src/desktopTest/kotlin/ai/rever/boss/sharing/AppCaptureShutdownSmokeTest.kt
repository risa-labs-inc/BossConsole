package ai.rever.boss.sharing

import androidx.compose.ui.awt.ComposeWindow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real AWT cleanup callbacks and fake native streams, without screen capture or user input. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CONTINUOUS_CAPTURE", matches = "1")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class AppCaptureShutdownSmokeTest {
    @Test
    fun `worker failure closes streams without interrupting EDT cleanup or stop notification`() {
        withCapture { platform, delivered, window ->
            val stopped = CountDownLatch(1)
            val interrupted = AtomicBoolean()
            capture(window, platform, { frame -> onEdt { if (frame != null) delivered.countDown() } }) {
                interrupted.set(Thread.currentThread().isInterrupted)
                onEdt { stopped.countDown() }
            }.use {
                assertTrue(delivered.await(5, TimeUnit.SECONDS), "Fake stream must deliver its first frame")
                platform.fail.set(true)
                assertTrue(stopped.await(5, TimeUnit.SECONDS), "Stop notification must reach the EDT")
                assertTrue(platform.streamClosed.get(), "Terminal worker failure must close the native stream")
                assertFalse(interrupted.get(), "Stop notification must not inherit a worker shutdown interrupt")
            }
        }
    }

    @Test
    fun `closing still retires native streams when clearing the frame throws`() {
        withCapture { platform, delivered, window ->
            capture(window, platform, { frame ->
                if (frame != null) delivered.countDown()
                check(frame != null || delivered.count != 0L) { "Synthetic frame cleanup failure" }
            }) {}.use {
                assertTrue(delivered.await(5, TimeUnit.SECONDS), "Fake stream must deliver its first frame")
            }
            assertTrue(platform.streamClosed.get(), "Frame callback failure must not skip native cleanup")
        }
    }

    private fun capture(
        window: ComposeWindow,
        platform: FailingStreamPlatform,
        onFrame: (AppRawCapturedFrame?) -> Unit,
        onStopped: (String) -> Unit,
    ) = AppContinuousWindowCapture(platform = platform).start(
        AppCaptureTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString(), window),
        onFrame,
        onStopped,
        { true },
    )

    private fun withCapture(action: (FailingStreamPlatform, CountDownLatch, ComposeWindow) -> Unit) {
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(320, 240)
                    isVisible = true
                }
            }
        try {
            action(FailingStreamPlatform(), CountDownLatch(1), window)
        } finally {
            onEdt { window.dispose() }
        }
    }

    private class FailingStreamPlatform : AppWindowStreamPlatform {
        val streamClosed = AtomicBoolean()
        val fail = AtomicBoolean()
        override val monitorsSession = true

        override fun available() = true

        override fun watchSession(onUnavailable: () -> Unit) = AutoCloseable {}

        @Suppress("LongParameterList") // Mirrors the native platform contract without capturing the user's screen.
        override fun open(
            nativeHandle: Long,
            width: Int,
            height: Int,
            frameRate: () -> Int,
            preferredFormat: String,
            sourceGeometry: WindowCaptureGeometry?,
            diagnostics: AppCaptureDiagnostics?,
        ): AppNativeWindowStream {
            val frame = AppRawWindowFrame(ByteArray(width * height * 4), width, height)
            return object : AppNativeWindowStream {
                override fun latest(): AppRawWindowFrame {
                    check(!fail.get()) { "Synthetic native failure" }
                    return frame
                }

                override fun close() {
                    streamClosed.set(true)
                }
            }
        }
    }
}
