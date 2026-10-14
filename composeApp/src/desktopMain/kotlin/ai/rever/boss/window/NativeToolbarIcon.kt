package ai.rever.boss.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import org.jetbrains.skia.Image

/** Render the registered vector unchanged; AppKit uses its alpha mask for native state tinting. */
@Composable
internal fun rememberNativeToolbarIcon(icon: ImageVector): ByteArray {
    val painter = rememberVectorPainter(icon)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(icon, density, direction) {
        val bitmap = ImageBitmap(32, 32)
        CanvasDrawScope().draw(density, direction, Canvas(bitmap), Size(32f, 32f)) {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(Color.Black)) }
        }
        Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { it.bytes }
        }
    }
}

/** Preserve site favicon colours rather than producing a toolbar template mask. */
@Composable
internal fun rememberNativeFavicon(painter: androidx.compose.ui.graphics.painter.Painter): ByteArray {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(painter, density, direction) {
        val source = painter.intrinsicSize
        val pixels = nativeFaviconPixels(source, density.density)
        val bitmap = ImageBitmap(pixels, pixels)
        CanvasDrawScope().draw(density, direction, Canvas(bitmap), Size(pixels.toFloat(), pixels.toFloat())) {
            with(painter) { draw(size) }
        }
        Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { it.bytes }
        }
    }
}

/** Keep source detail; AppKit chooses the final backing scale instead of resampling twice. */
internal fun nativeFaviconPixels(
    source: Size,
    density: Float,
): Int {
    val dimension = if (source == Size.Unspecified) Float.NaN else maxOf(source.width, source.height)
    val pixels = if (dimension.isFinite() && dimension > 0f) dimension else 16f * density
    return kotlin.math
        .ceil(pixels)
        .toInt()
        .coerceIn(16, 256)
}
