package ai.rever.boss.plugin.browser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

/**
 * Pixel stride when turning a page bitmap into a slide frame.
 *
 * 2, so a Retina capture lands at exactly its logical size - which is the size it will be drawn
 * at, so nothing is thrown away that the transition could have shown. A measured capture is
 * 2560x1600 and about 16 MB of BGRA; at this stride a frame is a quarter of that.
 *
 * Sampled rather than averaged. This is a frame of a 220 ms animation, not a screenshot, and a
 * box filter over 16 MB on the navigation path costs more than the aliasing is worth.
 */
private const val SNAPSHOT_STRIDE = 2

/**
 * How many page frames one tab keeps.
 *
 * Two: the entry behind and the entry ahead, which is every direction a swipe can go. Small on
 * purpose - a frame is roughly 4 MB, and this is held per browser handle, so a window of tabs
 * pays for it. Anything deeper buys nothing a gesture can reach.
 */
private const val MAX_SNAPSHOTS = 2

/**
 * Frames of pages this tab has been on, keyed by their navigation entry index.
 *
 * The transition needs the page it is arriving at, and by the time the gesture happens that page
 * is not loaded - so it has to have been captured on the way out. Keyed by entry index rather
 * than URL because a history can hold the same URL twice and they are different destinations.
 *
 * Not thread-safe by itself; the handle confines every call to its page-inject dispatcher.
 */
internal class PageSnapshots(
    private val max: Int = MAX_SNAPSHOTS,
) {
    // Access-ordered, so eviction drops the entry least recently *used* rather than the oldest
    // captured. Going back and forth across one boundary repeatedly is the common case, and
    // insertion order would evict the page being returned to.
    private val frames =
        object : LinkedHashMap<Int, ImageBitmap>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>): Boolean = size > max
        }

    fun put(
        entryIndex: Int,
        frame: ImageBitmap,
    ) {
        frames[entryIndex] = frame
    }

    fun get(entryIndex: Int): ImageBitmap? = frames[entryIndex]

    fun clear() = frames.clear()

    val size: Int get() = frames.size
}

/**
 * Convert a JxBrowser page bitmap into a Compose image, downscaled by [SNAPSHOT_STRIDE].
 *
 * Returns null for an empty capture rather than a blank image, so a caller can tell "no frame" from
 * "a frame that happens to be white" and skip the transition instead of sliding a white rectangle
 * over the page.
 *
 * Pixel order is BGRA, matching the favicon path in [BrowserHandleImpl]; getting it wrong swaps red
 * and blue, which reads as a bad screenshot rather than as a bug.
 *
 * `Browser.bitmap()` works in both rendering modes - **measured under HARDWARE_ACCELERATED**, where
 * the page is a native surface and the obvious guess is that it would come back empty. It does not.
 */
internal fun pageFrame(
    widthPx: Int,
    heightPx: Int,
    pixels: ByteArray,
): ImageBitmap? {
    // One guard, because every branch of it means the same thing to the caller: no frame, so no
    // transition. A short buffer is a torn capture, and drawing it would show half a page.
    val usable = widthPx > 0 && heightPx > 0 && pixels.size >= widthPx * heightPx * 4
    if (!usable) return null

    val outWidth = (widthPx + SNAPSHOT_STRIDE - 1) / SNAPSHOT_STRIDE
    val outHeight = (heightPx + SNAPSHOT_STRIDE - 1) / SNAPSHOT_STRIDE

    val image = BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until outHeight) {
        val rowStart = y * SNAPSHOT_STRIDE * widthPx * 4
        for (x in 0 until outWidth) {
            val p = rowStart + x * SNAPSHOT_STRIDE * 4
            val b = pixels[p].toInt() and 0xFF
            val g = pixels[p + 1].toInt() and 0xFF
            val r = pixels[p + 2].toInt() and 0xFF
            val a = pixels[p + 3].toInt() and 0xFF
            image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
        }
    }
    return image.toComposeImageBitmap()
}
