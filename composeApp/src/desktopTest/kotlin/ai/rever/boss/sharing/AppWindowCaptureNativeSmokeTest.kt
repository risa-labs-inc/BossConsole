package ai.rever.boss.sharing

import ai.rever.boss.platform.MacOSScreenCapture
import ai.rever.boss.utils.SystemUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in: captures only a synthetic window created by this test, never an existing app/display. */
class AppWindowCaptureNativeSmokeTest {
    @Test fun `native exact window capture includes synthetic Compose pixels`() {
        assumeTrue(System.getenv("BOSS_TEST_APP_CAPTURE") == "1", "Native capture smoke is opt-in")
        assumeTrue(SystemUtils.isMacOS, "Native exact-window adapter requires macOS")
        assumeTrue(
            MacOSScreenCapture.hasPermission(),
            "Screen Recording permission must already be granted; test never prompts",
        )
        val window = syntheticWindow()
        val occluder =
            onEdt {
                ComposeWindow().apply {
                    title = "Synthetic occluder (must never appear in selected capture)"
                    bounds = window.bounds
                    setContent { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
                    isVisible = true
                }
            }
        try {
            val source = MacAppWindowFrameSource()
            assumeTrue(source.available(), "ScreenCaptureKit screenshot API unavailable")
            val geometry =
                onEdt {
                    val scale = window.graphicsConfiguration.defaultTransform
                    WindowCaptureGeometry(
                        window.windowHandle,
                        (window.width * scale.scaleX).toInt(),
                        (window.height * scale.scaleY).toInt(),
                        window.width,
                        window.height,
                        window.x,
                        window.y,
                    )
                }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var result: NativeWindowFrame? = null
            var diagnostic = "No frame"
            // No foreground manipulation: desktop-independent capture must still bind only this window.
            while (result == null && System.nanoTime() < deadline) {
                val frame =
                    source.capture(
                        geometry.nativeHandle,
                        ProcessHandle.current().pid(),
                        geometry.width,
                        geometry.height,
                    )
                val image = ImageIO.read(ByteArrayInputStream(frame.png))
                val center = image.getRGB(image.width / 2, image.height / 2)
                diagnostic = "Synthetic PNG ${frame.width}x${frame.height}, center ARGB=$center"
                if (syntheticBlue(image)) result = frame else Thread.sleep(100)
            }
            val frame = assertNotNull(result, "Synthetic Compose pixels not captured: $diagnostic")
            assertEquals(geometry.width, frame.width)
            assertEquals(geometry.height, frame.height)
        } finally {
            onEdt {
                occluder.dispose()
                window.dispose()
            }
        }
    }

    // Keep captured pixels, modal input boundaries and closing geometry in one owned-dialog lifecycle.
    @Suppress("LongMethod")
    @Test
    fun `owned Compose dialog has captured pixels and stale surface geometry is rejected`() {
        assumeTrue(System.getenv("BOSS_TEST_APP_CAPTURE") == "1", "Native capture smoke is opt-in")
        assumeTrue(SystemUtils.isMacOS && MacOSScreenCapture.hasPermission())
        val root = syntheticWindow()
        val dialog =
            onEdt {
                ComposeDialog(root, java.awt.Dialog.ModalityType.MODELESS, root.graphicsConfiguration).apply {
                    title = "Synthetic owned dialog"
                    setBounds(root.x + 220, root.y + 40, 180, 160)
                    setContent { Box(Modifier.fillMaxSize().background(Color(0xFF229944))) }
                    isVisible = true
                }
            }
        try {
            val source = MacAppWindowFrameSource()
            assumeTrue(source.available())
            val snapshot = onEdt { captureSurfaceSnapshot(root) }
            assertEquals(listOf(root, dialog), snapshot.surfaces.map { it.window })
            assertTrue(snapshot.logicalWidth > root.width)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var green = false
            while (!green && System.nanoTime() < deadline) {
                val frame = captureSurfaces(snapshot, source, maxDimension = 640)
                assertTrue(frame.width <= 640 && frame.height <= 640)
                assertEquals(snapshot, onEdt { captureSurfaceSnapshot(root) })
                val image = ImageIO.read(ByteArrayInputStream(frame.png))
                val px =
                    ((dialog.x + dialog.width / 2 - snapshot.x).toDouble() / snapshot.logicalWidth * frame.width)
                        .toInt()
                val py =
                    ((dialog.y + dialog.height / 2 - snapshot.y).toDouble() / snapshot.logicalHeight * frame.height)
                        .toInt()
                val rgb = image.getRGB(px, py)
                green = (rgb shr 8 and 255) > 110 && (rgb shr 16 and 255) < 90 && (rgb and 255) < 110
                if (!green) Thread.sleep(100)
            }
            assertTrue(green, "Owned dialog pixels missing from composite")
            onEdt {
                val sink = AwtAppInputSink(root, requireForeground = false)
                sink.updateSurfaces(snapshot)
                assertTrue(sink.isAvailable())
                val x = (root.x + 30 - snapshot.x).toDouble() / (snapshot.logicalWidth - 1)
                val y = (root.y + root.height / 2 - snapshot.y).toDouble() / (snapshot.logicalHeight - 1)
                assertTrue(sink.apply(AppInputEvent.Pointer("down", x, y, 0)))
                assertTrue(sink.apply(AppInputEvent.Pointer("up", x, y, 0)))
                sink.releaseAll()
                dialog.modalityType = java.awt.Dialog.ModalityType.APPLICATION_MODAL
                val key = AppInputEvent.Key("down", "KeyA", "a", false, false, false, false)
                assertFalse(sink.apply(key), "New modal child must block keys before the next captured frame")
                val modalSnapshot = captureSurfaceSnapshot(root)
                sink.updateSurfaces(modalSnapshot)
                assertFalse(sink.apply(AppInputEvent.Pointer("down", x, y, 0)), "Modal child must block parent clicks")
                assertFalse(sink.apply(key), "Modal child must block retained parent keyboard focus")
                dialog.isVisible = false
                assertFalse(sink.isAvailable(), "Closing an owned surface must invalidate old input geometry")
            }
        } finally {
            onEdt {
                dialog.dispose()
                root.dispose()
            }
        }
    }

    @Test
    fun `session deactivation stops pending publication and does not auto resume`() {
        assumeTrue(System.getenv("BOSS_TEST_APP_CAPTURE") == "1", "Synthetic capture lifecycle smoke is opt-in")
        val window = syntheticWindow()
        val callback = AtomicReference<(() -> Unit)?>()
        val monitor =
            object : AppCaptureSessionMonitor {
                override fun supported() = true

                override fun watch(onUnavailable: () -> Unit): AutoCloseable {
                    callback.set(onUnavailable)
                    return AutoCloseable { callback.set(null) }
                }
            }
        val stopped = CountDownLatch(1)
        val source =
            object : ExactWindowFrameSource {
                override fun available() = true

                override fun capture(
                    nativeHandle: Long,
                    processId: Long,
                    width: Int,
                    height: Int,
                ): NativeWindowFrame {
                    Thread.sleep(500)
                    return NativeWindowFrame(byteArrayOf(), width, height)
                }
            }
        val frame = AtomicReference<AppCapturedFrame?>()
        var session: AutoCloseable? = null
        try {
            session =
                ExactAppWindowCapture(source, isMac = true, sessionMonitor = monitor).start(
                    AppCaptureTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString(), window),
                    { frame.set(it) },
                    { stopped.countDown() },
                )
            callback.get()!!.invoke()
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertEquals(null, callback.get(), "OS listener must be detached on stop")
            Thread.sleep(600)
            assertEquals(null, frame.get(), "Late capture must not publish after OS lock/session loss")
        } finally {
            session?.close()
            onEdt { window.dispose() }
        }
    }

    @Test
    fun `zero viewer demand produces no captures and one viewer resumes same source`() {
        assumeTrue(System.getenv("BOSS_TEST_APP_CAPTURE") == "1", "Synthetic lifecycle smoke is opt-in")
        val window = syntheticWindow()
        val demand = AtomicBoolean(false)
        val calls = AtomicInteger()
        val frameReady = CountDownLatch(1)
        val monitor =
            object : AppCaptureSessionMonitor {
                override fun supported() = true

                override fun watch(onUnavailable: () -> Unit) = AutoCloseable { }
            }
        val source =
            object : ExactWindowFrameSource {
                override fun available() = true

                override fun capture(
                    nativeHandle: Long,
                    processId: Long,
                    width: Int,
                    height: Int,
                ): NativeWindowFrame {
                    calls.incrementAndGet()
                    return NativeWindowFrame(byteArrayOf(1), width, height)
                }
            }
        val session =
            ExactAppWindowCapture(source, isMac = true, sessionMonitor = monitor).start(
                AppCaptureTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString(), window),
                {
                    demand.set(false)
                    frameReady.countDown()
                },
                { error(it) },
                { demand.get() },
            )
        try {
            Thread.sleep(250)
            assertEquals(0, calls.get())
            demand.set(true)
            assertTrue(frameReady.await(3, TimeUnit.SECONDS))
            Thread.sleep(250)
            assertEquals(1, calls.get())
        } finally {
            session.close()
            onEdt { window.dispose() }
        }
    }

    @Test
    fun `transient capture retries are bounded and permission failures stop immediately`() {
        assumeTrue(System.getenv("BOSS_TEST_APP_CAPTURE") == "1", "Synthetic lifecycle smoke is opt-in")
        // Recover once; stop a persistent native failure; never retry denied or lost permission.
        for (scenario in listOf(
            RetryScenario(-3811L, false, false, 2),
            RetryScenario(-3811L, true, false, 4),
            RetryScenario(-3801L, true, false, 1),
            RetryScenario(-3811L, true, true, 1),
        )) {
            val window = syntheticWindow()
            val calls = AtomicInteger()
            val delivered = AtomicInteger()
            val stopped = AtomicInteger()
            val done = CountDownLatch(1)
            val monitor =
                object : AppCaptureSessionMonitor {
                    override fun supported() = true

                    override fun watch(onUnavailable: () -> Unit) = AutoCloseable { }
                }
            val source =
                object : ExactWindowFrameSource {
                    override fun available() = !scenario.permissionLost || calls.get() == 0

                    override fun capture(
                        nativeHandle: Long,
                        processId: Long,
                        width: Int,
                        height: Int,
                    ): NativeWindowFrame {
                        val count = calls.incrementAndGet()
                        if (scenario.persistent || count == 1) {
                            val domain = "com.apple.ScreenCaptureKit.SCStreamErrorDomain"
                            val failure = NativeAppCaptureException(domain, scenario.code)
                            throw java.util.concurrent.ExecutionException(failure)
                        }
                        return NativeWindowFrame(byteArrayOf(1), width, height)
                    }
                }
            val session =
                ExactAppWindowCapture(source, isMac = true, sessionMonitor = monitor).start(
                    AppCaptureTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString(), window),
                    {
                        delivered.incrementAndGet()
                        done.countDown()
                    },
                    {
                        stopped.incrementAndGet()
                        done.countDown()
                    },
                    { delivered.get() == 0 },
                )
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS), "Capture failed to resolve")
                assertEquals(scenario.expectedCalls, calls.get())
                assertEquals(if (scenario.persistent) 0 else 1, delivered.get())
                assertEquals(if (scenario.persistent) 1 else 0, stopped.get())
            } finally {
                session.close()
                onEdt { window.dispose() }
            }
        }
    }

    private data class RetryScenario(
        val code: Long,
        val persistent: Boolean,
        val permissionLost: Boolean,
        val expectedCalls: Int,
    )

    private fun syntheticBlue(image: BufferedImage): Boolean =
        listOf(1, 2, 3).all { quarter ->
            val rgb = image.getRGB(image.width * quarter / 4, image.height / 2)
            // Display ICC conversion changes channels; this checks recognizable blue, not byte-exact color.
            val red = rgb shr 16 and 255
            val green = rgb shr 8 and 255
            val blue = rgb and 255
            kotlin.math.abs(red - 18) <= 32 && kotlin.math.abs(green - 86) <= 32 && kotlin.math.abs(blue - 202) <= 32
        }

    private fun syntheticWindow(): ComposeWindow =
        onEdt {
            ComposeWindow().apply {
                title = "BossConsole synthetic capture test"
                setSize(320, 240)
                setContent { Box(Modifier.fillMaxSize().background(Color(0xFF1256CA))) }
                isVisible = true
            }
        }
}
