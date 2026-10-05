package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class FaviconArtworkCacheTest {
    private val dir =
        File.createTempFile("favicon-artwork-", "").apply {
            delete()
            mkdirs()
        }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
        FaviconMissMemory.forget()
        FaviconRetryMemory.clear()
    }

    @Test
    fun `Google no-icon cannot remove original artwork`(): Unit =
        runTest {
            saveOriginal()
            HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor(HOST), image(32, Color.RED), dir)
            File(dir, "${HqFaviconDiskCache.keyFor(HOST)}.png").setLastModified(1)
            HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ -> FaviconFetch.NoIcon }
            assertNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(HOST), dir))
            val original = assertNotNull(loadFaviconArtwork(HOST, dir))
            assertEquals(FaviconArtworkSource.SITE, original.source)
            assertEquals(256f, original.icon.painter.intrinsicSize.width)
        }

    @Test
    fun `a Google refresh cannot overwrite original artwork and tabs still choose the original`(): Unit =
        runTest {
            saveOriginal()
            HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ ->
                HighQualityFaviconService.acceptResponse(
                    pngBytes(image(128, Color.RED)),
                    HOST,
                    HqFaviconDiskCache.keyFor(HOST),
                    dir,
                    NOW,
                )
            }
            val page = icon(16, Color.BLUE)
            val result =
                upgradeCachedFavicon(
                    URL,
                    page,
                    NOW,
                    loadCandidate = { loadFaviconArtwork(it, dir) },
                    refresh = { _, _ -> error("fresh site art refreshed") },
                )
            assertEquals(256f, result.painter.intrinsicSize.width)
            assertEquals(FaviconArtworkSource.SITE, assertNotNull(loadFaviconArtwork(HOST, dir)).source)
            assertEquals(
                128f,
                assertNotNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(HOST), dir))
                    .icon.painter.intrinsicSize.width,
            )
        }

    @Test
    fun `legacy untagged Google cache entries stay readable`(): Unit =
        runTest {
            HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor(HOST), image(128, Color.BLUE), dir)
            assertEquals(FaviconArtworkSource.GOOGLE, assertNotNull(loadFaviconArtwork(HOST, dir)).source)
        }

    @Test
    fun `expired original artwork refreshes only from its site and stays sharp offline`(): Unit =
        runTest {
            val page = icon(16, Color.BLUE)
            val old = CachedFavicon(icon(256, Color.BLUE), 1, FaviconArtworkSource.SITE)
            var requested: String? = null
            val result =
                refreshFaviconArtwork(
                    URL,
                    page,
                    old,
                    NOW,
                    FaviconArtworkRefreshSources(
                        original = { url, anchor ->
                            requested = url
                            assertSame(page, anchor)
                            null
                        },
                        google = { _, _ -> error("Google was asked to refresh site-owned artwork") },
                    ),
                )
            assertSame(old.icon, result)
            assertEquals(URL, requested)
        }

    private suspend fun saveOriginal() {
        HqFaviconDiskCache.save(HqFaviconDiskCache.originalKeyFor(HOST), image(256, Color.BLUE), dir)
        File(dir, "${HqFaviconDiskCache.originalKeyFor(HOST)}.png").setLastModified(NOW)
    }

    private fun icon(
        size: Int,
        colour: Color,
    ) = TabIcon.Image(BitmapPainter(image(size, colour).toComposeImageBitmap()))

    private fun image(
        size: Int,
        colour: Color,
    ) = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().apply {
            color = colour
            fillRect(size / 4, size / 4, size / 2, size / 2)
            dispose()
        }
    }

    private companion object {
        const val HOST = "artwork.example.com"
        const val URL = "https://artwork.example.com"
        const val NOW = 1_700_000_000_000L
    }
}
