package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CachedFaviconResolutionTest {
    @Test
    fun `tab rows reuse stale matching artwork through cache reads only`(): Unit =
        runTest {
            val page = icon(16)
            val sharp = icon(128)
            var reads = 0
            val result =
                resolveCachedHighQualityFavicon(
                    "https://example.com:8443/page",
                    "page-key",
                    pageIcon = {
                        assertEquals("page-key", it)
                        page
                    },
                    artwork = { url ->
                        reads++
                        assertEquals("https://example.com:8443/page", url)
                        CachedFavicon(sharp, 1, FaviconArtworkSource.SITE)
                    },
                )
            assertSame(sharp, result)
            assertEquals(1, reads)
        }

    @Test
    fun `a missing page and artwork stay missing without a host request`(): Unit =
        runTest {
            var reads = 0
            assertNull(
                resolveCachedHighQualityFavicon(
                    "https://example.com/page",
                    null,
                    pageIcon = { null },
                    artwork = {
                        reads++
                        null
                    },
                ),
            )
            assertEquals(1, reads)
        }

    private fun icon(size: Int): TabIcon.Image {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = Color.BLUE
            fillRect(size / 4, size / 4, size / 2, size / 2)
            dispose()
        }
        return TabIcon.Image(BitmapPainter(image.toComposeImageBitmap()))
    }
}
