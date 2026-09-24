package ai.rever.boss.plugin.browser

import ai.rever.boss.cache.FaviconCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Offloads the FaviconChanged payload work - the BGRA→ARGB pixel conversion, bitmap construction
 * and the FaviconCache PNG encode - off the JxBrowser callback thread.
 *
 * The event arrives on the thread JxBrowser also uses to deliver navigation and context-menu
 * callbacks, so the callback itself may do nothing heavier than reading the icon's dimensions and
 * pixel array: a rapid icon swap or a multi-thousand-pixel icon used to park that thread for the
 * whole decode/encode. Conversion runs on [executor], submissions coalesce - a drain keeps only
 * the latest pending icon - and dimensions beyond [MAX_FAVICON_DIMENSION_PX] are refused before a
 * pixel is read.
 */
internal class BrowserFaviconPipeline(
    private val executor: Executor,
    private val urlProvider: () -> String,
    private val notifyListeners: (String?) -> Unit,
    private val saveFavicon: (String, ImageBitmap) -> String? = FaviconCache::saveFavicon,
    private val warn: (String, Map<String, String>) -> Unit = { _, _ -> },
) {
    private sealed interface Pending {
        /** The page reported no favicon; listeners get null so the tab reverts to its default icon. */
        data object Cleared : Pending

        class Icon(
            val pixels: ByteArray,
            val width: Int,
            val height: Int,
        ) : Pending
    }

    private val pending = AtomicReference<Pending?>(null)

    /**
     * Records a FaviconChanged event and returns. Runs on the JxBrowser callback thread, so it
     * stays O(1): [pixels] is only invoked for an icon inside the dimension cap, and the heavy
     * conversion happens in [drainLatest] on [executor].
     */
    fun submit(
        width: Int,
        height: Int,
        pixels: () -> ByteArray?,
    ) {
        if (width <= 0 || height <= 0) {
            pending.set(Pending.Cleared)
        } else if (width > MAX_FAVICON_DIMENSION_PX || height > MAX_FAVICON_DIMENSION_PX) {
            warn(
                "Favicon refused: dimensions over cap",
                mapOf(
                    "width" to width.toString(),
                    "height" to height.toString(),
                    "cap" to MAX_FAVICON_DIMENSION_PX.toString(),
                ),
            )
            return
        } else {
            val data = pixels()
            if (data == null || data.size < width * height * BGRA_BYTES_PER_PIXEL) {
                warn(
                    "Favicon refused: pixel buffer too short",
                    mapOf(
                        "width" to width.toString(),
                        "height" to height.toString(),
                        "bytes" to (data?.size ?: -1).toString(),
                    ),
                )
                return
            }
            pending.set(Pending.Icon(data, width, height))
        }
        executor.execute(::drainLatest)
    }

    /**
     * One drain per submission, each draining whatever is pending at its turn. Under a burst the
     * single-threaded [executor] serialises them, so intermediate icons collapse into the latest
     * and the surplus drains return on an empty reference.
     */
    private fun drainLatest() {
        val next = pending.getAndSet(null) ?: return
        val cacheKey =
            when (next) {
                Pending.Cleared -> null
                is Pending.Icon -> saveFavicon(urlProvider(), next.toImageBitmap())
            }
        notifyListeners(cacheKey)
    }

    private fun Pending.Icon.toImageBitmap(): ImageBitmap {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var pixelIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val b = pixels[pixelIndex++].toInt() and 0xFF
                val g = pixels[pixelIndex++].toInt() and 0xFF
                val r = pixels[pixelIndex++].toInt() and 0xFF
                val a = pixels[pixelIndex++].toInt() and 0xFF
                image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return image.toComposeImageBitmap()
    }

    companion object {
        /** Past this, the icon is refused: a 4096px icon is ~64MB of pixels before encoding. */
        const val MAX_FAVICON_DIMENSION_PX = 1024

        private const val BGRA_BYTES_PER_PIXEL = 4
    }
}
