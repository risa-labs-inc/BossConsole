package ai.rever.boss.sharing

import androidx.compose.ui.awt.ComposeWindow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real AWT lifetime with a deterministic native geometry race, independent of capture permission. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CONTINUOUS_CAPTURE", matches = "1")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class AppCaptureGeometryRetrySmokeTest {
    @Test
    fun `native resize before AWT updates clears pixels and resumes bounded retries`() {
        withWindow { window ->
            val platform = GeometryRacePlatform(2)
            val pixels = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val before = onEdt { captureSurfaceSnapshot(window) }
            AppContinuousWindowCapture(platform = platform)
                .start(target(window), { if (it != null) pixels.countDown() }, { stopped.countDown() }, { true })
                .use {
                    assertTrue(pixels.await(8, TimeUnit.SECONDS), "Typed geometry races must recover")
                    assertEquals(3, platform.opened.get())
                    assertEquals(before, onEdt { captureSurfaceSnapshot(window) }, "AWT geometry never changed")
                    assertEquals(1L, stopped.count, "Geometry retry must retain the publication")
                }
        }
    }

    @Test
    fun `repeated native geometry failures stop after three retries`() {
        withWindow { window ->
            val platform = GeometryRacePlatform(Int.MAX_VALUE)
            val stopped = CountDownLatch(1)
            AppContinuousWindowCapture(platform = platform)
                .start(target(window), {}, { stopped.countDown() }, { true })
                .use {
                    assertTrue(stopped.await(8, TimeUnit.SECONDS), "Repeated mismatch must be terminal")
                    assertEquals(4, platform.opened.get(), "Initial open plus exactly three retries")
                }
        }
    }

    @Test
    fun `independent session loss cancels geometry retry without reopening`() {
        withWindow { window ->
            val platform = GeometryRacePlatform(Int.MAX_VALUE)
            val stopped = CountDownLatch(1)
            AppContinuousWindowCapture(platform = platform)
                .start(target(window), {}, { stopped.countDown() }, { true })
                .use {
                    assertTrue(platform.failed.await(5, TimeUnit.SECONDS))
                    checkNotNull(platform.unavailable.get()).invoke()
                    assertTrue(stopped.await(2, TimeUnit.SECONDS))
                    assertEquals(1, platform.opened.get(), "Session loss must invalidate the pending retry")
                }
        }
    }

    private fun target(window: ComposeWindow): AppCaptureTarget {
        val windowId = UUID.randomUUID().toString()
        return AppCaptureTarget(windowId, UUID.randomUUID().toString(), window)
    }

    private fun withWindow(action: (ComposeWindow) -> Unit) {
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(320, 240)
                    isVisible = true
                }
            }
        try {
            action(window)
        } finally {
            onEdt { window.dispose() }
        }
    }

    private class GeometryRacePlatform(
        private val failures: Int,
    ) : AppWindowStreamPlatform {
        val opened = AtomicInteger()
        val failed = CountDownLatch(1)
        val unavailable = AtomicReference<(() -> Unit)?>()
        override val monitorsSession = true

        override fun available() = true

        override fun watchSession(onUnavailable: () -> Unit): AutoCloseable {
            unavailable.set(onUnavailable)
            return AutoCloseable {}
        }

        @Suppress("LongParameterList") // Mirrors the platform contract exercised by the real capture owner.
        override fun open(
            nativeHandle: Long,
            width: Int,
            height: Int,
            frameRate: () -> Int,
            preferredFormat: String,
            sourceGeometry: WindowCaptureGeometry?,
            diagnostics: AppCaptureDiagnostics?,
        ): AppNativeWindowStream {
            check(sourceGeometry?.nativeHandle == nativeHandle)
            val fail = opened.incrementAndGet() <= failures
            val frame = AppRawWindowFrame(ByteArray(width * height * 4), width, height)
            return object : AppNativeWindowStream {
                override fun latest(): AppRawWindowFrame {
                    if (fail) {
                        failed.countDown()
                        throw AppNativeGeometryChangedException(AppNativeFrameBoundaryEnd())
                    }
                    return frame
                }

                override fun close() = Unit
            }
        }
    }
}
