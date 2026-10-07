package ai.rever.boss.sharing

import ai.rever.boss.window.WindowInputModalBoundary
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Frame
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Local-only geometry; never serialize Window references or native handles into viewer metadata. */
internal data class AppCapturedSurface(
    val window: Window,
    val geometry: WindowCaptureGeometry,
    val modal: Boolean = WindowInputModalBoundary.isModal(window),
)

internal data class AppSurfaceSnapshot(
    val surfaces: List<AppCapturedSurface>,
    val x: Int,
    val y: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val width: Int,
    val height: Int,
)

/** AWT ownership is authoritative. Unknown visible owned surfaces must not silently disappear. */
internal fun captureSurfaceSnapshot(root: Window): AppSurfaceSnapshot {
    val surfaces = mutableListOf<AppCapturedSurface>()

    fun visit(window: Window) {
        checkCaptureVisibility(
            window.isDisplayable,
            window.isShowing,
            window is Frame && window.extendedState and Frame.ICONIFIED != 0,
        )
        val handle =
            when (window) {
                is ComposeWindow -> window.windowHandle
                is ComposeDialog -> window.windowHandle
                else -> error("This owned popup has no verified native capture identity")
            }
        check(handle != 0L && surfaces.size < 8) { "Shared surface limit exceeded" }
        val scale = window.graphicsConfiguration.defaultTransform
        val insets = window.insets
        surfaces.add(
            AppCapturedSurface(
                window,
                WindowCaptureGeometry(
                    handle,
                    (window.width * scale.scaleX).toInt(),
                    (window.height * scale.scaleY).toInt(),
                    window.width,
                    window.height,
                    window.x,
                    window.y,
                    AppCaptureInsets(
                        (insets.left * scale.scaleX).toInt(),
                        (insets.right * scale.scaleX).toInt(),
                        (insets.top * scale.scaleY).toInt(),
                        (insets.bottom * scale.scaleY).toInt(),
                    ),
                ),
            ),
        )
        ai.rever.boss.window.MacToolbarInput.captureSurfaces(window).forEach { chrome ->
            check(surfaces.size < 8) { "Shared surface limit exceeded" }
            val bounds = chrome.bounds
            surfaces.add(
                AppCapturedSurface(
                    window,
                    WindowCaptureGeometry(
                        chrome.handle,
                        (bounds.width * scale.scaleX).toInt(),
                        (bounds.height * scale.scaleY).toInt(),
                        bounds.width,
                        bounds.height,
                        window.x + bounds.left,
                        window.y + bounds.top,
                        nativeWindowNumber = chrome.windowNumber,
                        nativeParentHandle = handle,
                    ),
                ),
            )
        }
        window.ownedWindows.filter { it.isShowing }.forEach(::visit)
    }
    visit(root)
    val x = surfaces.minOf { it.geometry.x }
    val y = surfaces.minOf { it.geometry.y }
    val logicalWidth = surfaces.maxOf { it.geometry.x + it.geometry.logicalWidth } - x
    val logicalHeight = surfaces.maxOf { it.geometry.y + it.geometry.logicalHeight } - y
    val scale = root.graphicsConfiguration.defaultTransform
    val width = (logicalWidth * scale.scaleX).toInt()
    val height = (logicalHeight * scale.scaleY).toInt()
    check(width in 1..8192 && height in 1..8192) { "Shared window group is too large" }
    return AppSurfaceSnapshot(surfaces.toList(), x, y, logicalWidth, logicalHeight, width, height)
}

internal fun captureSurfaces(
    snapshot: AppSurfaceSnapshot,
    source: ExactWindowFrameSource,
    maxDimension: Int = Int.MAX_VALUE,
): NativeWindowFrame {
    val size = appCaptureFrameSize(snapshot.width, snapshot.height, maxDimension)
    val scaleX = size.width.toDouble() / snapshot.logicalWidth
    val scaleY = size.height.toDouble() / snapshot.logicalHeight
    val frames =
        snapshot.surfaces.map {
            val geometry = it.geometry
            val width = (geometry.logicalWidth * scaleX).roundToInt().coerceAtLeast(1)
            val height = (geometry.logicalHeight * scaleY).roundToInt().coerceAtLeast(1)
            source.capture(geometry.nativeHandle, ProcessHandle.current().pid(), width, height).also { frame ->
                check(frame.width == width && frame.height == height)
            }
        }
    if (frames.size == 1) return frames.single()
    val canvas = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB)
    val graphics = canvas.createGraphics()
    try {
        snapshot.surfaces.zip(frames).forEach { (surface, frame) ->
            val geometry = surface.geometry
            val image = checkNotNull(ImageIO.read(ByteArrayInputStream(frame.png)))
            graphics.drawImage(
                image,
                ((geometry.x - snapshot.x) * scaleX).roundToInt(),
                ((geometry.y - snapshot.y) * scaleY).roundToInt(),
                frame.width,
                frame.height,
                null,
            )
        }
    } finally {
        graphics.dispose()
    }
    val bytes =
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(canvas, "png", output))
            output.toByteArray()
        }
    check(bytes.size <= 16 * 1024 * 1024)
    return NativeWindowFrame(bytes, size.width, size.height)
}

internal fun checkCaptureVisibility(
    displayable: Boolean,
    showing: Boolean,
    minimized: Boolean,
) {
    check(displayable && showing) { "Shared window closed or hidden" }
    check(!minimized) { "Shared window minimized" }
}
