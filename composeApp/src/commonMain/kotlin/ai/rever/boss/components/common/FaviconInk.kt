package ai.rever.boss.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * What a favicon's ink looks like, for deciding whether it can be read on a given background.
 *
 * Null [luminance] means there is nothing to judge: the icon has its own opaque tile (which carries
 * its own contrast), uses colour (a brand's colours are never altered), or is blank.
 */
internal class FaviconInk(
    val luminance: Float?,
)

private const val SAMPLE_SIZE = 32
private const val INK_ALPHA = 0.5f
private const val CHROMA_LIMIT = 0.1f
private const val TILE_CORNER_ALPHA = 0.9f

/** Below this contrast ratio a glyph reads as missing (black on a dark tab is about 1.2). */
private const val MIN_CONTRAST = 2f

internal fun faviconInk(painter: Painter): FaviconInk {
    val size = painter.intrinsicSize
    val drawable = size.width.isFinite() && size.height.isFinite() && size.minDimension > 0f
    if (!drawable) return FaviconInk(null)
    val bitmap = ImageBitmap(SAMPLE_SIZE, SAMPLE_SIZE)
    val target = Size(SAMPLE_SIZE.toFloat(), SAMPLE_SIZE.toFloat())
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), target) {
        with(painter) { draw(target) }
    }
    return faviconInk(bitmap)
}

internal fun faviconInk(bitmap: ImageBitmap): FaviconInk {
    val pixels = bitmap.toPixelMap()
    val edgeX = bitmap.width - 1
    val edgeY = bitmap.height - 1
    val opaqueCorners =
        listOf(pixels[0, 0], pixels[edgeX, 0], pixels[0, edgeY], pixels[edgeX, edgeY])
            .count { it.alpha >= TILE_CORNER_ALPHA }
    val ink =
        (0..edgeY).flatMap { y -> (0..edgeX).map { x -> pixels[x, y] } }.filter { it.alpha >= INK_ALPHA }
    val coloured = ink.any { maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue) > CHROMA_LIMIT }
    val judged = opaqueCorners < 3 && !coloured && ink.isNotEmpty()
    return FaviconInk(if (judged) ink.map { it.copy(alpha = 1f).luminance() }.average().toFloat() else null)
}

/**
 * True when [ink] would all but vanish on a background of [backgroundLuminance] and its inverse
 * would not: GitHub's black octocat on a dark tab, or a white glyph on a light one.
 */
internal fun faviconNeedsInversion(
    ink: FaviconInk,
    backgroundLuminance: Float,
): Boolean {
    val luminance = ink.luminance ?: return false
    val asIs = contrastRatio(luminance, backgroundLuminance)
    return asIs < MIN_CONTRAST && contrastRatio(1f - luminance, backgroundLuminance) > asIs
}

private fun contrastRatio(
    a: Float,
    b: Float,
): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

private val INVERT =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f,
                0f,
                0f,
                0f,
                255f,
                0f,
                -1f,
                0f,
                0f,
                255f,
                0f,
                0f,
                -1f,
                0f,
                255f,
                0f,
                0f,
                0f,
                1f,
                0f,
            ),
        ),
    )

/**
 * The filter that keeps a monochrome favicon readable on [background], or null to draw it as-is.
 *
 * The last line of defence after the cache's own per-theme handling: many sites ship one icon for
 * every theme, and nothing upstream can make black ink visible on a dark tab. Decided at draw time
 * against the colours on screen, so it follows a theme switch with nothing cached. Coloured
 * artwork and icons on their own tile are never touched. [background] may be translucent; it is
 * judged over [surface], whose alpha is ignored because glass themes zero it.
 */
@Composable
internal fun rememberFaviconContrastFilter(
    painter: Painter,
    background: Color,
    surface: Color,
): ColorFilter? {
    val ink = remember(painter) { faviconInk(painter) }
    val behind = background.compositeOver(surface.copy(alpha = 1f)).luminance()
    return if (faviconNeedsInversion(ink, behind)) INVERT else null
}
