package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.image.BufferedImage

/** Largest edge a recoloured copy is rasterised at; matches the HQ cache's own bound. */
private const val INVERTED_MAX_DIMENSION = 256
private const val ALPHA_MASK = 0xFF000000.toInt()
private const val RGB_MASK = 0x00FFFFFF

/**
 * [icon] with its colour channels inverted and alpha kept, or null when it has no finite size.
 * Only called for achromatic artwork, where inversion is exact: black ink becomes white ink and a
 * white tile becomes a black one, without shifting any hue.
 */
internal fun invertedFavicon(icon: TabIcon.Image): TabIcon.Image? {
    val size = icon.painter.intrinsicSize
    if (!size.isDrawable()) return null
    val scale = minOf(1f, INVERTED_MAX_DIMENSION / maxOf(size.width, size.height))
    val width = (size.width * scale).toInt().coerceAtLeast(1)
    val height = (size.height * scale).toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        with(icon.painter) { draw(this@draw.size) }
    }
    // getRGB/setRGB work in unpremultiplied ARGB, so flipping RGB leaves edge alpha untouched.
    val image = bitmap.toAwtImage()
    val pixels = image.getRGB(0, 0, width, height, null, 0, width)
    for (i in pixels.indices) {
        pixels[i] = (pixels[i] and ALPHA_MASK) or (pixels[i].inv() and RGB_MASK)
    }
    val inverted = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    inverted.setRGB(0, 0, width, height, pixels, 0, width)
    return TabIcon.Image(BitmapPainter(inverted.toComposeImageBitmap()))
}

private fun Size.isDrawable(): Boolean = width.isFinite() && height.isFinite() && minDimension >= 1f
