package ai.rever.boss.window

import ai.rever.boss.platform.MacOSScreenCapture
import ai.rever.boss.sharing.AppRawWindowFrame
import ai.rever.boss.sharing.MacAppWindowStream
import ai.rever.boss.sharing.captureSurfaceSnapshot
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.sun.jna.Pointer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owns an inactive synthetic production-shaped window; no user window or global input is touched. */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CONTINUOUS_CAPTURE", matches = "1")
class MacTrafficLightCaptureSmokeTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    fun `inactive glass window captures real traffic lights before and during sharing`() {
        verifyTrafficLights(glassEnabled = true)
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    fun `inactive opaque theme captures real traffic lights before and during sharing`() {
        verifyTrafficLights(glassEnabled = false)
    }

    private fun verifyTrafficLights(glassEnabled: Boolean) {
        assumeTrue(MacOSScreenCapture.hasPermission())
        val window = createWindow()
        val installed = LinkedBlockingQueue<Boolean>()
        val glass = MacWindowGlass(window.windowHandle) { installed.offer(it) }
        val toolbar = MacSidebarToolbar(window.windowHandle, {}, {})
        try {
            glass.update(GlassRequest(glassEnabled, IntSize(640, 260), dark = true, clear = false, fullscreen = false))
            assertEquals(glassEnabled, installed.poll(10, TimeUnit.SECONDS))
            toolbar.update(
                "Synthetic shared window",
                listOf(NativeTitleBarAction("sidebar", "Sidebar", symbol = "sidebar.left", onClick = {})),
                dark = true,
                background = 0xff101012.toInt(),
                icons = emptyMap(),
            )
            val bounds = buttonBounds(window)
            assertFalse(onEdt { window.isFocused }, "Inactive windows must retain their native controls")
            val before = capture(window)
            toolbar.remoteInput.enabled = true
            assertEquals(bounds, buttonBounds(window), "Sharing must preserve the real native button views")
            val sharing = capture(window)
            // A hidden-button reference distinguishes captured controls from a flat toolbar background.
            // Only this disposable fixture changes visibility; production never hides/unhides buttons.
            setButtonsHidden(window, true)
            val hidden = capture(window)
            assertCapturedButtons(before, hidden, bounds, "before sharing")
            assertCapturedButtons(sharing, hidden, bounds, "while sharing")
            exportSyntheticFrames(glassEnabled, sharing, hidden, bounds)
        } finally {
            toolbar.close()
            glass.close()
            onAppKit { Unit }
            onEdt { window.dispose() }
        }
    }

    /** Optional synthetic-only export for the encrypted browser decode regression; never exports user frames. */
    private fun exportSyntheticFrames(
        glassEnabled: Boolean,
        sharing: AppRawWindowFrame,
        hidden: AppRawWindowFrame,
        bounds: List<IntRect>,
    ) {
        val path = System.getenv("BOSS_TEST_TRAFFIC_LIGHT_OUTPUT")?.takeIf { it.isNotBlank() } ?: return
        val output = File(path)
        check(output.isDirectory || output.mkdirs()) { "Cannot create synthetic traffic-light output directory" }
        val name = if (glassEnabled) "glass" else "opaque"
        File(output, "$name-sharing.bgra").writeBytes(sharing.bgra)
        File(output, "$name-hidden.bgra").writeBytes(hidden.bgra)
        val rectangles =
            bounds.joinToString(",") {
                """{"left":${it.left},"top":${it.top},"width":${it.width},"height":${it.height}}"""
            }
        File(output, "$name.json").writeText(
            """
            {"width":${sharing.width},"height":${sharing.height},"format":"BGRA",
             "sharing":"$name-sharing.bgra","hidden":"$name-hidden.bgra","bounds":[$rectangles]}
            """.trimIndent() + "\n",
        )
    }

    private fun createWindow(): ComposeWindow =
        onEdt {
            ComposeWindow().apply {
                title = "Synthetic BOSS traffic lights"
                iconImages = BossWindowIcon.images
                isUndecorated = true
                isTransparent = true
                focusableWindowState = false
                isAutoRequestFocus = false
                rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                setBounds(90, 140, 640, 260)
                setContent { Box(Modifier.fillMaxSize().background(Color(0xff101012))) }
                isVisible = true
            }
        }

    private fun buttonBounds(window: ComposeWindow): List<IntRect> =
        onAppKit {
            val native = Pointer(window.windowHandle)
            (0L..2L).map { index ->
                val button = checkNotNull(pointer(native, "standardWindowButton:", index))
                assertEquals(native, pointer(button, "window"), "Traffic light must belong to the exact window")
                assertEquals(0L, number(button, "isHiddenOrHasHiddenAncestor"), "Native button $index is hidden")
                checkNotNull(nativeAddressBounds(button, native)).also {
                    assertTrue(it.width > 0 && it.height > 0, "Native button $index has no visible bounds: $it")
                }
            }
        }

    private fun setButtonsHidden(
        window: ComposeWindow,
        hidden: Boolean,
    ) {
        onAppKit {
            val native = Pointer(window.windowHandle)
            for (index in 0L..2L) {
                val button = pointer(native, "standardWindowButton:", index)
                send(button, "setHidden:", if (hidden) 1.toByte() else 0.toByte())
            }
            send(native, "displayIfNeeded")
        }
    }

    private fun capture(window: ComposeWindow): AppRawWindowFrame {
        val geometry = onEdt { captureSurfaceSnapshot(window).surfaces.single().geometry }
        return MacAppWindowStream
            .open(
                geometry.nativeHandle,
                geometry.logicalWidth,
                geometry.logicalHeight,
                pixelFormat = "BGRA",
            ).use { stream ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                var frame: AppRawWindowFrame? = null
                while (frame == null && System.nanoTime() < deadline) {
                    frame = stream.latest()
                    if (frame == null) Thread.sleep(10)
                }
                checkNotNull(frame) { "Exact synthetic window did not produce a captured frame" }
            }
    }

    private fun assertCapturedButtons(
        frame: AppRawWindowFrame,
        hidden: AppRawWindowFrame,
        bounds: List<IntRect>,
        phase: String,
    ) {
        assertEquals(IntSize(hidden.width, hidden.height), IntSize(frame.width, frame.height))
        bounds.forEachIndexed { index, rect ->
            val difference = pixelDifference(frame, hidden, rect)
            println("Synthetic native traffic light $index $phase bounds=$rect meanRGBDifference=$difference")
            assertTrue(
                difference >= 2.0,
                "Native traffic light $index missing from captured pixels $phase: $difference",
            )
        }
    }

    private fun pixelDifference(
        frame: AppRawWindowFrame,
        hidden: AppRawWindowFrame,
        bounds: IntRect,
    ): Double {
        // Capture is requested in logical pixels, matching nativeAddressBounds exactly.
        val left = bounds.left.coerceAtLeast(0)
        val top = bounds.top.coerceAtLeast(0)
        val right = bounds.right.coerceAtMost(frame.width)
        val bottom = bounds.bottom.coerceAtMost(frame.height)
        assertTrue(right > left && bottom > top, "Native traffic light lies outside captured full-window bounds")
        var difference = 0L
        for (y in top until bottom) {
            for (x in left until right) {
                val offset = (y * frame.width + x) * 4
                for (channel in 0..2) {
                    val visibleChannel = frame.bgra[offset + channel].toInt() and 255
                    val hiddenChannel = hidden.bgra[offset + channel].toInt() and 255
                    difference += abs(visibleChannel - hiddenChannel)
                }
            }
        }
        return difference.toDouble() / ((right - left) * (bottom - top) * 3)
    }

    private fun <T> onEdt(action: () -> T): T {
        val result = CompletableFuture<T>()
        SwingUtilities.invokeAndWait { runCatching(action).fold(result::complete, result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun <T> onAppKit(action: () -> T): T {
        val result = CompletableFuture<T>()
        MacToolbarRuntime.dispatch { runCatching(action).fold(result::complete, result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }
}
