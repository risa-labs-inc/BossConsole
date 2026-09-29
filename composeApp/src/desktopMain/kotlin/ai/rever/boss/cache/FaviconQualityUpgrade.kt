package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Reuse an existing sharper representation, without a network request or changing site identity. */
internal fun upgradeCachedFavicon(
    url: String?,
    page: TabIcon.Image,
): TabIcon.Image {
    val host = FaviconHost.of(url) ?: return page
    val candidate = HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(host))?.icon
    return sharperMatchingFavicon(page, candidate)
}

internal fun sharperMatchingFavicon(
    page: TabIcon.Image,
    candidate: TabIcon.Image?,
): TabIcon.Image {
    val originalSize = page.painter.intrinsicSize
    val newSize = candidate?.painter?.intrinsicSize ?: Size.Zero
    val larger = newSize.width > originalSize.width && newSize.height > originalSize.height
    return if (candidate != null && larger && faviconDifference(page, candidate) < 0.025f) candidate else page
}

/** Compare premultiplied colour and alpha so transparent padding cannot disguise another icon. */
private fun faviconDifference(
    first: TabIcon.Image,
    second: TabIcon.Image,
): Float {
    val a = faviconSample(first).toPixelMap()
    val b = faviconSample(second).toPixelMap()
    var error = 0f
    for (y in 0 until 16) {
        for (x in 0 until 16) {
            error += pixelDifference(a[x, y], b[x, y])
        }
    }
    return error / (16 * 16 * 4)
}

private fun pixelDifference(
    a: Color,
    b: Color,
): Float {
    val red = a.red * a.alpha - b.red * b.alpha
    val green = a.green * a.alpha - b.green * b.alpha
    val blue = a.blue * a.alpha - b.blue * b.alpha
    val alpha = a.alpha - b.alpha
    return red * red + green * green + blue * blue + alpha * alpha
}

private fun faviconSample(icon: TabIcon.Image): ImageBitmap {
    val bitmap = ImageBitmap(16, 16)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(16f, 16f)) {
        with(icon.painter) { draw(size) }
    }
    return bitmap
}
