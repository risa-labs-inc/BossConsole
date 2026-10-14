package ai.rever.boss.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Timer
import kotlin.test.assertTrue

/** Actual native stream, synthetic pixels, an occluding foreign window, and no user profile/network. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CONTINUOUS_CAPTURE", matches = "1")
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class AppContinuousCaptureSmokeTest {
    @Suppress("LongMethod", "NestedBlockDepth") // One synthetic native lifecycle owns every assertion and cleanup.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `continuous stream captures only its exact window without PNG encoding`() {
        val tick = mutableStateOf(0)
        val window =
            onEdt {
                ComposeWindow().apply {
                    title = "Synthetic continuous capture"
                    focusableWindowState = false
                    setSize(640, 400)
                    setContent {
                        Box(Modifier.fillMaxSize().background(Color(18 + tick.value % 10, 86, 202)))
                    }
                    isVisible = true
                }
            }
        val occluder =
            onEdt {
                ComposeWindow().apply {
                    title = "Synthetic foreign occluder"
                    focusableWindowState = false
                    bounds = window.bounds
                    setContent { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
                    isVisible = true
                }
            }
        val timer = onEdt { Timer(8) { tick.value++ }.also { it.start() } }
        val requestedRate = AtomicInteger(60)
        try {
            MacAppWindowStream.open(window.windowHandle, 640, 400, frameRate = requestedRate::get).use { stream ->
                for (rate in listOf(60, 30, 60)) {
                    requestedRate.set(rate)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    var last: AppRawWindowFrame? = null
                    var frames = 0
                    var firstFrameAt = 0L
                    while (System.nanoTime() < deadline && frames < 50) {
                        val frame = stream.latest()
                        if (frame != null && frame !== last) {
                            last = frame
                            if (firstFrameAt == 0L) firstFrameAt = System.nanoTime()
                            val center = (200 * frame.width + 320) * 4
                            assertTrue((frame.bgra[center].toInt() and 255) > 180, "Synthetic blue must be captured")
                            assertTrue((frame.bgra[center + 1].toInt() and 255) in 70..100, "Occluder must be excluded")
                            frames++
                        }
                        Thread.sleep(5)
                    }
                    val fps = (frames - 1) * 1_000_000_000.0 / (System.nanoTime() - firstFrameAt)
                    println("Synthetic continuous raw frames: $frames; target: $rate; fps: $fps")
                    assertTrue(frames >= 50, "Continuous capture must deliver changing raw pixels")
                    if (rate == 30) {
                        assertTrue(fps < 40, "Native capture must respect the reduced rate")
                    } else {
                        assertTrue(fps > 35, "Native capture must exceed the old 30 FPS ceiling")
                    }
                }
            }
        } finally {
            onEdt {
                timer.stop()
                occluder.dispose()
                window.dispose()
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Suppress("NestedBlockDepth") // One synthetic window owns native stream startup, color assertions, and cleanup.
    fun `NV12 capture preserves blue pixels with a smaller limited-range buffer`() {
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(640, 400)
                    setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                    isVisible = true
                }
            }
        try {
            MacAppWindowStream.open(window.windowHandle, 640, 400, pixelFormat = "NV12").use { stream ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var frame: AppRawWindowFrame? = null
                while (frame == null && System.nanoTime() < deadline) {
                    frame = stream.latest()
                    if (frame == null) Thread.sleep(10)
                }
                val captured = checkNotNull(frame) { "NV12 capture did not produce a frame" }
                assertTrue(captured.format == "NV12")
                assertTrue(captured.bgra.size == 640 * 400 * 3 / 2)
                val y = (captured.bgra[200 * 640 + 320].toInt() and 255) - 16
                val uv = 640 * 400 + 100 * 640 + 320
                val u = (captured.bgra[uv].toInt() and 255) - 128
                val v = (captured.bgra[uv + 1].toInt() and 255) - 128
                val red = 1.164 * y + 1.793 * v
                val green = 1.164 * y - 0.213 * u - 0.533 * v
                val blue = 1.164 * y + 2.112 * u
                assertTrue(red < 45 && green < 45 && blue > 180, "NV12 must preserve limited-range BT.709 blue")
            }
        } finally {
            onEdt { window.dispose() }
        }
    }

    @Suppress("LongMethod", "NestedBlockDepth") // One synthetic native lifecycle owns every assertion and cleanup.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `continuous capture gates demand includes owned dialogs and stops on session loss`() {
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(320, 240)
                    setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                    isVisible = true
                }
            }
        val demand = AtomicBoolean()
        val frames = AtomicInteger()
        val mainFrame = CountDownLatch(1)
        val dialogFrame = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var sessionLost: () -> Unit = {}
        val monitor =
            object : AppCaptureSessionMonitor {
                override fun supported() = true

                override fun watch(onUnavailable: () -> Unit): AutoCloseable {
                    sessionLost = onUnavailable
                    return AutoCloseable {}
                }
            }
        val capture = AppContinuousWindowCapture(monitor)
        val target = AppCaptureTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString(), window)
        var dialog: ComposeDialog? = null
        try {
            capture
                .start(target, { frame ->
                    if (frame != null) {
                        frames.incrementAndGet()
                        if (frame.surfaces.surfaces.size == 1) mainFrame.countDown()
                        if (frame.surfaces.surfaces.size == 2) {
                            val surface =
                                frame.surfaces.surfaces
                                    .last()
                                    .geometry
                            val scale = frame.pixels.width.toDouble() / frame.surfaces.logicalWidth
                            val x = ((surface.x - frame.surfaces.x + surface.logicalWidth / 2) * scale).toInt()
                            val y = ((surface.y - frame.surfaces.y + surface.logicalHeight / 2) * scale).toInt()
                            val index = (y * frame.pixels.width + x) * 4
                            if ((frame.pixels.bgra[index].toInt() and 255) > 180 &&
                                (frame.pixels.bgra[index + 2].toInt() and 255) > 180
                            ) {
                                dialogFrame.countDown()
                            }
                        }
                    }
                }, { stopped.countDown() }, { demand.get() })
                .use {
                    Thread.sleep(300)
                    assertTrue(frames.get() == 0, "No viewer demand must produce no native frames")
                    demand.set(true)
                    assertTrue(mainFrame.await(15, TimeUnit.SECONDS), "Main window must stream")
                    dialog =
                        onEdt {
                            ComposeDialog(window).apply {
                                focusableWindowState = false
                                setSize(100, 80)
                                setLocation(window.x + 40, window.y + 60)
                                setContent { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
                                isVisible = true
                            }
                        }
                    assertTrue(dialogFrame.await(15, TimeUnit.SECONDS), "Owned dialog pixels must be composited")
                    sessionLost()
                    assertTrue(stopped.await(2, TimeUnit.SECONDS), "Session loss must stop immediately")
                    val before = frames.get()
                    Thread.sleep(200)
                    assertTrue(frames.get() == before, "A stopped stream must never emit late frames")
                }
        } finally {
            onEdt {
                dialog?.dispose()
                window.dispose()
            }
        }
    }
}
