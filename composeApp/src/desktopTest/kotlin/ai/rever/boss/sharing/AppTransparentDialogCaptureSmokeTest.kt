package ai.rever.boss.sharing

import ai.rever.boss.platform.MacOSScreenCapture
import ai.rever.boss.utils.SystemUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Dialog
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exact synthetic native alpha and production source-over composition, without user content. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CONTINUOUS_CAPTURE", matches = "1")
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class AppTransparentDialogCaptureSmokeTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `transparent owned dialog reports clear half opacity and opaque patch bytes`() {
        assumeTrue(SystemUtils.isMacOS && MacOSScreenCapture.hasPermission())
        val owner = createOwner()
        var dialog: ComposeDialog? = null
        try {
            val overlay = createOverlay(owner).also { dialog = it }
            val snapshot = onEdt { captureSurfaceSnapshot(owner) }
            assertEquals(listOf(owner, overlay), snapshot.surfaces.map { it.window })
            val frames = snapshot.surfaces.mapIndexed { index, surface -> captureFrame(snapshot, surface, index == 1) }
            verifyNativeAlpha(frames[1])
            verifyComposition(snapshot, frames)
        } finally {
            onEdt {
                dialog?.dispose()
                owner.dispose()
            }
        }
    }

    private fun createOwner(): ComposeWindow =
        onEdt {
            ComposeWindow().apply {
                title = "Synthetic transparent capture owner"
                focusableWindowState = false
                setBounds(100, 100, 600, 240)
                setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                isVisible = true
            }
        }

    private fun createOverlay(owner: ComposeWindow): ComposeDialog =
        onEdt {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                title = "Synthetic transparent patches"
                isUndecorated = true
                isTransparent = true
                type = java.awt.Window.Type.UTILITY
                focusableWindowState = false
                isAutoRequestFocus = false
                background = java.awt.Color(0, 0, 0, 0)
                setBounds(owner.x + 40, owner.y + 60, 300, 120)
                setContent {
                    Canvas(Modifier.fillMaxSize()) {
                        val patch = Size(size.width / 3, size.height)
                        drawRect(Color.Red.copy(alpha = 0.5f), Offset(patch.width, 0f), patch)
                        drawRect(Color.Red, Offset(patch.width * 2, 0f), patch)
                    }
                }
                isVisible = true
            }
        }

    private fun captureFrame(
        snapshot: AppSurfaceSnapshot,
        surface: AppCapturedSurface,
        overlay: Boolean,
    ): AppRawWindowFrame {
        val canvas = appCaptureFrameSize(snapshot.width, snapshot.height, 1920)
        val size = appCaptureSurfaceFrameSize(surface.geometry, snapshot.logicalWidth, canvas.width)
        val stream =
            MacAppWindowStream.open(surface.geometry.nativeHandle, size.width, size.height, pixelFormat = "BGRA")
        return stream.use {
            awaitPaintedFrame(it, overlay)
        }
    }

    private fun verifyNativeAlpha(frame: AppRawWindowFrame) {
        val clear = sample(frame, 0)
        val half = sample(frame, 1)
        val opaque = sample(frame, 2)
        println("Synthetic exact transparent dialog BGRA: clear=$clear; half=$half; opaque=$opaque")
        assertEquals("BGRA", frame.format)
        assertTrue(clear.all { it <= 8 }, "Clear patch must retain transparency: $clear")
        assertTrue(half[3] in 120..136, "Half-opacity patch must retain alpha: $half")
        assertTrue(isOpaqueColor(opaque, 2), "Opaque source patch must remain predominantly red: $opaque")
        val fraction = half[3] / opaque[3].toDouble()
        assertTrue(
            (0..2).all { abs(half[it] - opaque[it] * fraction) <= 12 },
            "Compositor requires premultiplied color-managed native pixels: half=$half; opaque=$opaque",
        )
    }

    private fun verifyComposition(
        snapshot: AppSurfaceSnapshot,
        frames: List<AppRawWindowFrame>,
    ) {
        val composite = composeRawWindowFrames(snapshot, frames)
        val scale = composite.width.toDouble() / snapshot.logicalWidth
        val root = snapshot.surfaces[0].geometry
        val overlay = snapshot.surfaces[1].geometry
        val overlayX = ((overlay.x - snapshot.x) * scale).roundToInt()
        val overlayY = ((overlay.y - snapshot.y) * scale).roundToInt()
        val rootX = ((root.x - snapshot.x) * scale).roundToInt()
        val rootY = ((root.y - snapshot.y) * scale).roundToInt()
        for (patch in 0..2) {
            val x = overlayX + frames[1].width * (patch * 2 + 1) / 6
            val y = overlayY + frames[1].height / 2
            val background = sampleAt(frames[0], x - rootX, y - rootY)
            val foreground = sample(frames[1], patch)
            assertTrue(
                isOpaqueColor(background, 0),
                "Exact owner reference must retain blue beneath popup: $background",
            )
            val expected =
                (0..3).map {
                    (foreground[it] + background[it] * (1.0 - foreground[3] / 255.0)).roundToInt().coerceAtMost(255)
                }
            val actual = sampleAt(composite, x, y)
            println("Synthetic popup composite patch=$patch; root=$background; expected=$expected; actual=$actual")
            assertEquals(expected, actual, "Clear, translucent and opaque patches must source-over the exact owner")
        }
    }

    private fun awaitPaintedFrame(
        stream: MacAppWindowStream,
        overlay: Boolean,
    ): AppRawWindowFrame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var frame: AppRawWindowFrame? = null
        while (System.nanoTime() < deadline) {
            frame = stream.latest()
            val opaque = frame?.let { sample(it, 2) }
            if (opaque != null && isOpaqueColor(opaque, if (overlay) 2 else 0)) return checkNotNull(frame)
            Thread.sleep(10)
        }
        error("Synthetic transparent dialog did not paint; final opaque sample=${frame?.let { sample(it, 2) }}")
    }

    private fun isOpaqueColor(
        pixel: List<Int>,
        channel: Int,
    ): Boolean {
        val other = (0..2).filter { it != channel }.maxOf { pixel[it] }
        return pixel[3] >= 245 && pixel[channel] >= 180 && pixel[channel] > other * 2
    }

    private fun sample(
        frame: AppRawWindowFrame,
        patch: Int,
    ): List<Int> {
        val x = frame.width * (patch * 2 + 1) / 6
        return sampleAt(frame, x, frame.height / 2)
    }

    private fun sampleAt(
        frame: AppRawWindowFrame,
        x: Int,
        y: Int,
    ): List<Int> {
        val offset = (y * frame.width + x) * 4
        return (0..3).map { frame.bgra[offset + it].toInt() and 255 }
    }
}
