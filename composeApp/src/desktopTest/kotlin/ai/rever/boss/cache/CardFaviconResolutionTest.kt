package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CardFaviconResolutionTest {
    @Test
    fun `a sharp cached card does not request original or Google artwork`(): Unit =
        runTest {
            val page = icon(128)
            val resolved =
                resolveHighQualityCardFavicon(
                    "https://example.com",
                    "page",
                    sources =
                        CardFaviconSources(
                            pageIcon = { page },
                            qualityUpgrade = { _, cached -> cached },
                            originalIcon = { _, _ -> error("original fetched") },
                            hostIcon = { error("Google fetched") },
                        ),
                )
            assertSame(page, resolved)
        }

    @Test
    fun `a small cached card asks its own site before Google`(): Unit =
        runTest {
            val page = icon(16)
            val original = icon(256)
            val resolved =
                resolveHighQualityCardFavicon(
                    "https://example.com",
                    "page",
                    sources =
                        CardFaviconSources(
                            pageIcon = { page },
                            qualityUpgrade = { _, cached -> cached },
                            originalIcon = { _, cached ->
                                assertSame(page, cached)
                                original
                            },
                            hostIcon = { error("Google fetched") },
                        ),
                )
            assertSame(original, resolved)
        }

    @Test
    fun `Google fallback follows a failed original lookup and still preserves page identity`(): Unit =
        runTest {
            val order = mutableListOf<String>()
            val page = icon(16)
            val resolved =
                resolveHighQualityCardFavicon(
                    "https://example.com",
                    "page",
                    sources =
                        CardFaviconSources(
                            pageIcon = { page },
                            qualityUpgrade = { _, cached -> cached },
                            originalIcon = { _, _ ->
                                order.add("original")
                                null
                            },
                            hostIcon = {
                                order.add("Google")
                                icon(128, Color.RED)
                            },
                        ),
                )
            assertEquals(listOf("original", "Google"), order)
            assertSame(page, resolved)
        }

    @Test
    fun `a card with no cached page also prefers original site artwork`(): Unit =
        runTest {
            val original = icon(256)
            assertSame(
                original,
                resolveHighQualityCardFavicon(
                    "https://example.com",
                    null,
                    sources =
                        CardFaviconSources(
                            pageIcon = { null },
                            originalIcon = { _, _ -> original },
                            hostIcon = { error("Google fetched") },
                        ),
                ),
            )
        }

    private fun icon(
        size: Int,
        colour: Color = Color.BLUE,
    ): TabIcon.Image {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = colour
            fillRect(size / 4, size / 4, size / 2, size / 2)
            dispose()
        }
        return TabIcon.Image(BitmapPainter(image.toComposeImageBitmap()))
    }
}
