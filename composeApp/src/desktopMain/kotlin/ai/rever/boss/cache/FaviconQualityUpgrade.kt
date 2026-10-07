package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

private const val COMPARISON_SIZE = 16
private const val PADDING_SAMPLE_SIZE = 128
private const val ARTWORK_DIFFERENCE_LIMIT = 0.04f
private const val MONOCHROME_DIFFERENCE_LIMIT = 0.05f
private const val SMALL_ICON_REFRESH_MS = 24 * 60 * 60 * 1000L

/** Reuse the separate HQ source without allowing a host guess to replace unrelated page artwork. */
internal suspend fun upgradeCachedFavicon(
    url: String?,
    page: TabIcon.Image,
    nowMs: Long = System.currentTimeMillis(),
    loadCandidate: (String) -> CachedFavicon? = { host -> loadFaviconArtwork(host, origin = url) },
    refresh: suspend (String, CachedFavicon) -> TabIcon.Image? = { pageUrl, cached ->
        refreshFaviconArtwork(pageUrl, page, cached, nowMs)
    },
): TabIcon.Image {
    val host = FaviconHost.of(url)
    val cached = host?.let(loadCandidate) ?: return page
    val candidate =
        if (qualityRefreshDue(
                cached,
                nowMs,
            )
        ) {
            refresh(OriginalFaviconSource.originFor(url) ?: "https://$host/", cached)
        } else {
            cached.icon
        }
    val upgraded = sharperMatchingFavicon(page, candidate)
    // NoIcon returns null after intentionally deleting the cache. An unrelated non-null refresh
    // may belong to another page on the same host; keep the old verified artwork for this page.
    return if (candidate != null && candidate !== cached.icon && upgraded === page) {
        sharperMatchingFavicon(page, cached.icon)
    } else {
        upgraded
    }
}

internal fun qualityRefreshDue(
    cached: CachedFavicon,
    nowMs: Long,
): Boolean =
    FaviconFreshness.isEntryExpired(cached.fetchedAtMs, nowMs) ||
        (
            cached.icon.painter.intrinsicSize.let { size ->
                (!size.width.isFinite() || !size.height.isFinite() || size.minDimension < FAVICON_TARGET_SIZE) &&
                    nowMs - cached.fetchedAtMs > SMALL_ICON_REFRESH_MS
            }
        )

/**
 * [candidate] when it is larger and shows the same artwork as [page], else [page].
 *
 * A monochrome theme variant (GitHub's black octocat for a page showing the white one) is returned
 * as a recoloured copy in the page's own polarity, never as-is: the site's icon files are fetched
 * outside the browser, with no `prefers-color-scheme`, so they are usually the light-theme variant,
 * and handing that back drew a black glyph on a dark tab. Callers that ask "is this the candidate
 * itself?" (`=== candidate`) therefore see only same-polarity matches.
 */
internal fun sharperMatchingFavicon(
    page: TabIcon.Image,
    candidate: TabIcon.Image?,
): TabIcon.Image {
    val originalSize = page.painter.intrinsicSize
    val newSize = candidate?.painter?.intrinsicSize ?: Size.Zero
    val larger = newSize.width > originalSize.width && newSize.height > originalSize.height
    if (candidate == null || !larger) return page
    return when (matchingArtwork(page, candidate)) {
        ArtworkMatch.SAME -> {
            candidate
        }

        ArtworkMatch.INVERTED -> {
            invertedFavicon(candidate)?.takeIf { matchingArtwork(page, it) == ArtworkMatch.SAME } ?: page
        }

        ArtworkMatch.NONE -> {
            page
        }
    }
}

private enum class ArtworkMatch { NONE, SAME, INVERTED }

private fun matchingArtwork(
    first: TabIcon.Image,
    second: TabIcon.Image,
): ArtworkMatch {
    val firstSample = faviconSample(first)
    val secondSample = faviconSample(second)
    val a = firstSample.bitmap.toPixelMap()
    val b = secondSample.bitmap.toPixelMap()
    val coloursA = Array(COMPARISON_SIZE * COMPARISON_SIZE) { i -> a[i % COMPARISON_SIZE, i / COMPARISON_SIZE] }
    val coloursB = Array(COMPARISON_SIZE * COMPARISON_SIZE) { i -> b[i % COMPARISON_SIZE, i / COMPARISON_SIZE] }
    // Normalizing padding may shift a tiny glyph by a pixel. Permit those theme/size variants,
    // but never stretch a tall glyph into a wide one and call them the same artwork.
    val aspectChange =
        maxOf(firstSample.aspectRatio, secondSample.aspectRatio) /
            minOf(firstSample.aspectRatio, secondSample.aspectRatio)
    val comparable = aspectChange <= 1.5f && coloursA.any { it.alpha > 0.01f } && coloursB.any { it.alpha > 0.01f }
    val colourError = coloursA.indices.sumOf { i -> pixelDifference(coloursA[i], coloursB[i]).toDouble() }.toFloat()
    return when {
        !comparable -> ArtworkMatch.NONE
        colourError / (coloursA.size * 4) < ARTWORK_DIFFERENCE_LIMIT -> ArtworkMatch.SAME
        else -> monochromeArtworkMatch(coloursA, coloursB)
    }
}

/**
 * Only achromatic artwork can match after inversion; coloured logos must keep their colours.
 * SAME wins over INVERTED: an icon that looks identical on some background needs no recolouring.
 */
private fun monochromeArtworkMatch(
    a: Array<Color>,
    b: Array<Color>,
): ArtworkMatch {
    if (!a.all(::isMonochrome) || !b.all(::isMonochrome)) return ArtworkMatch.NONE
    val backgrounds = listOf(0f, 1f)

    // White artwork on white (or black on black) is blank, not proof of identity.
    fun visible(colours: Array<Color>) =
        backgrounds.map { bg -> colours.map { compositeLuminance(it, bg) } }.filter { it.max() - it.min() >= 0.25f }
    val firsts = visible(a)
    val seconds = visible(b)

    fun anyPair(invert: Boolean) =
        firsts.any { first ->
            seconds.any { second -> luminanceDifference(first, second, invert) < MONOCHROME_DIFFERENCE_LIMIT }
        }
    return when {
        anyPair(invert = false) -> ArtworkMatch.SAME
        anyPair(invert = true) -> ArtworkMatch.INVERTED
        else -> ArtworkMatch.NONE
    }
}

private fun isMonochrome(colour: Color): Boolean =
    colour.alpha < 0.01f ||
        maxOf(colour.red, colour.green, colour.blue) - minOf(colour.red, colour.green, colour.blue) < 0.05f

private fun compositeLuminance(
    colour: Color,
    background: Float,
): Float {
    val luminance = 0.2126f * colour.red + 0.7152f * colour.green + 0.0722f * colour.blue
    return luminance * colour.alpha + background * (1f - colour.alpha)
}

private fun luminanceDifference(
    a: List<Float>,
    b: List<Float>,
    invert: Boolean,
): Float =
    a.indices
        .sumOf { i ->
            val delta = a[i] - if (invert) 1f - b[i] else b[i]
            (delta * delta).toDouble()
        }.toFloat() / a.size

/** Compare premultiplied colour and alpha so transparent padding cannot disguise another icon. */
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

private class FaviconArtworkSample(
    val bitmap: ImageBitmap,
    val aspectRatio: Float,
)

/** Trim padding but retain its aspect for comparison: the same logo can occupy 90% or 60% of a PNG. */
private fun faviconSample(icon: TabIcon.Image): FaviconArtworkSample {
    val padded = ImageBitmap(PADDING_SAMPLE_SIZE, PADDING_SAMPLE_SIZE)
    val paddedSize = PADDING_SAMPLE_SIZE.toFloat()
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(padded), Size(paddedSize, paddedSize)) {
        with(icon.painter) { draw(size) }
    }
    val pixels = padded.toPixelMap()
    val edge = PADDING_SAMPLE_SIZE - 1
    val corners = listOf(pixels[0, 0], pixels[edge, 0], pixels[0, edge], pixels[edge, edge])
    val background =
        corners.first().takeIf { first ->
            first.alpha > 0.99f && corners.all { pixelDifference(first, it) < 0.002f }
        }
    var left = PADDING_SAMPLE_SIZE
    var top = PADDING_SAMPLE_SIZE
    var right = -1
    var bottom = -1
    for (y in 0 until PADDING_SAMPLE_SIZE) {
        for (x in 0 until PADDING_SAMPLE_SIZE) {
            val colour = pixels[x, y]
            if (colour.alpha >= 0.5f && (background == null || pixelDifference(colour, background) > 0.002f)) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
    }
    val sample = ImageBitmap(COMPARISON_SIZE, COMPARISON_SIZE)
    val hasBounds = right >= left && bottom >= top
    if (!hasBounds && background == null) return FaviconArtworkSample(sample, 1f)
    val contentSize =
        if (hasBounds) {
            IntSize(right - left + 1, bottom - top + 1)
        } else {
            IntSize(PADDING_SAMPLE_SIZE, PADDING_SAMPLE_SIZE)
        }
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(sample), Size(16f, 16f)) {
        drawImage(
            image = padded,
            srcOffset = if (hasBounds) IntOffset(left, top) else IntOffset.Zero,
            srcSize = contentSize,
            dstSize = IntSize(COMPARISON_SIZE, COMPARISON_SIZE),
            filterQuality = FilterQuality.Medium,
        )
    }
    return FaviconArtworkSample(sample, contentSize.width.toFloat() / contentSize.height)
}
