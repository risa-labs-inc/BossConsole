package ai.rever.boss.cache

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for what [FaviconCache.saveFavicon] returns, which is the half that decides whether a tab
 * keeps its icon: the key flows to `updateFavicon`, and a null there resets the tab to the default
 * globe rather than merely leaving the icon stale.
 *
 * Uses the `internal` directory seam — the public entry point resolves through `BossDirectories`
 * to the developer's real `~/.boss/cache`, which is not somewhere a test should be writing.
 */
class FaviconCacheTest {
    private val dir: File =
        File.createTempFile("favicon-cache-", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A tiny solid icon — a handful of bytes once PNG-encoded. */
    private fun smallIcon(rgb: Int): ImageBitmap =
        BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            .apply {
                for (y in 0 until height) {
                    for (x in 0 until width) setRGB(x, y, rgb)
                }
            }.toComposeImageBitmap()

    /** Random noise, which PNG cannot compress, so it clears the 100 KB limit comfortably. */
    private fun oversizeIcon(): ImageBitmap {
        val random = Random(20260802L)
        return BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
            .apply {
                for (y in 0 until height) {
                    for (x in 0 until width) setRGB(x, y, random.nextInt() or (0xFF shl 24))
                }
            }.toComposeImageBitmap()
    }

    private fun cacheFileFor(url: String) = File(dir, "${FaviconCache.generateCacheKey(url)}.png")

    @Test
    fun `a saved favicon returns its key and lands on disk`() {
        val key = FaviconCache.saveFavicon(URL, smallIcon(RED), dir)

        assertEquals(FaviconCache.generateCacheKey(URL), key)
        assertTrue(cacheFileFor(URL).exists())
    }

    @Test
    fun `saving twice for the same URL replaces the icon`() {
        // The reported bug: on Windows the second save failed, returned null, and the tab dropped
        // back to the default globe even though a perfectly good icon was sitting on disk.
        FaviconCache.saveFavicon(URL, smallIcon(RED), dir)
        val first = cacheFileFor(URL).readBytes()

        val key = FaviconCache.saveFavicon(URL, smallIcon(BLUE), dir)

        assertEquals(FaviconCache.generateCacheKey(URL), key, "the second save must not report failure")
        assertTrue(cacheFileFor(URL).readBytes().isNotEmpty())
        assertTrue(!cacheFileFor(URL).readBytes().contentEquals(first), "the icon should have been replaced")
    }

    @Test
    fun `an oversize favicon keeps the icon already cached`() {
        FaviconCache.saveFavicon(URL, smallIcon(RED), dir)
        val cached = cacheFileFor(URL).readBytes()

        val key = FaviconCache.saveFavicon(URL, oversizeIcon(), dir)

        assertEquals(FaviconCache.generateCacheKey(URL), key, "stale beats blank")
        assertTrue(cacheFileFor(URL).readBytes().contentEquals(cached), "the oversize icon must not be written")
    }

    @Test
    fun `an oversize favicon with nothing cached reports no icon`() {
        val key = FaviconCache.saveFavicon(URL, oversizeIcon(), dir)

        assertNull(key)
        assertTrue(!cacheFileFor(URL).exists())
    }

    @Test
    fun `no temp files are left behind by either outcome`() {
        // The temp file is a sibling of the target, so a leak accumulates in the favicon cache
        // itself rather than in the OS temp dir.
        FaviconCache.saveFavicon(URL, smallIcon(RED), dir)
        FaviconCache.saveFavicon(URL, oversizeIcon(), dir)

        val strays = dir.listFiles()?.filter { it.name.startsWith("favicon_") }.orEmpty()
        assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
    }

    @Test
    fun `an unusable cache directory reports no icon rather than throwing`() {
        // The caller is a JxBrowser event listener; this must not throw at it. A regular file
        // standing in for the directory makes createTempFile fail.
        val notADirectory = File(dir, "regular-file").apply { writeText("not a directory") }

        val key = FaviconCache.saveFavicon(URL, smallIcon(RED), notADirectory)

        assertNull(key)
    }

    @Test
    fun `different URLs get different keys`() {
        val first = FaviconCache.saveFavicon(URL, smallIcon(RED), dir)
        val second = FaviconCache.saveFavicon("https://other.example/", smallIcon(BLUE), dir)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first != second)
    }

    @Test
    fun `a site's light and dark icons are kept side by side`() {
        val darkKey = FaviconCache.saveFavicon(URL, smallIcon(RED), dir, FaviconScheme.DARK)
        val lightKey = FaviconCache.saveFavicon(URL, smallIcon(BLUE), dir, FaviconScheme.LIGHT)

        assertNotNull(darkKey)
        assertNotNull(lightKey)
        assertTrue(darkKey != lightKey, "a theme switch must hand the tab a new key, or it never reloads")
        assertEquals(RED, loadedRgb(darkKey, FaviconScheme.DARK))
        assertEquals(BLUE, loadedRgb(lightKey, FaviconScheme.LIGHT))
    }

    @Test
    fun `a restored tab shows the icon for the theme on screen, not the one its key names`() {
        val darkKey = FaviconCache.saveFavicon(URL, smallIcon(RED), dir, FaviconScheme.DARK)!!
        FaviconCache.saveFavicon(URL, smallIcon(BLUE), dir, FaviconScheme.LIGHT)

        assertEquals(BLUE, loadedRgb(darkKey, FaviconScheme.LIGHT))
    }

    @Test
    fun `a key falls back to its own variant, then to the base file`() {
        val darkKey = FaviconCache.saveFavicon(URL, smallIcon(RED), dir, FaviconScheme.DARK)!!
        // No light icon was ever seen: the dark one is still better than none.
        assertEquals(RED, loadedRgb(darkKey, FaviconScheme.LIGHT))

        // A key saved before variants existed has no suffix and reads the base file as before.
        val legacyKey = FaviconCache.generateCacheKey("https://legacy.example/")
        FaviconCache.saveFavicon("https://legacy.example/", smallIcon(BLUE), dir)
        assertEquals(BLUE, loadedRgb(legacyKey, FaviconScheme.DARK))
    }

    private fun loadedRgb(
        key: String,
        scheme: FaviconScheme,
    ): Int {
        val icon = assertNotNull(loadSchemeFavicon(key, scheme, dir))
        val bitmap = (icon.painter as androidx.compose.ui.graphics.painter.BitmapPainter)
        val image =
            androidx.compose.ui.graphics
                .ImageBitmap(16, 16)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit
                .Density(1f),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics
                .Canvas(image),
            androidx.compose.ui.geometry
                .Size(16f, 16f),
        ) { with(bitmap) { draw(size) } }
        return image.toAwtImage().getRGB(8, 8)
    }

    private companion object {
        const val URL = "https://example.com/page"
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
