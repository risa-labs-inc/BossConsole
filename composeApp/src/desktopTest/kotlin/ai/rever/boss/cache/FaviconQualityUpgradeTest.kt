package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FaviconQualityUpgradeTest {
    @Test
    fun `larger matching artwork replaces the small cached page icon`() {
        val small = icon(16, Color.BLUE)
        val sharp = icon(128, Color.BLUE)
        assertSame(sharp, sharperMatchingFavicon(small, sharp))
    }

    @Test
    fun `different artwork and smaller candidates preserve page identity`() {
        val page = icon(32, Color.BLUE)
        assertSame(page, sharperMatchingFavicon(page, icon(128, Color.RED)))
        assertSame(page, sharperMatchingFavicon(page, icon(16, Color.BLUE)))
        assertSame(page, sharperMatchingFavicon(page, null))
    }

    @Test
    fun `real Google icon with different transparent padding uses its larger cached artwork`() {
        val page = fixture("google-page-16.png")
        val large = fixture("google-cached-large.png")
        assertSame(large, sharperMatchingFavicon(page, large))
    }

    @Test
    fun `real GitHub theme variants use both cached and refreshed larger artwork`() {
        val page = fixture("github-page-16.png")
        for (name in listOf("github-cached-large.png", "github-refreshed-128.png")) {
            val large = fixture(name)
            assertSame(large, sharperMatchingFavicon(page, large), name)
        }
    }

    @Test
    fun `an unrelated monochrome logo and a blank icon cannot replace GitHub artwork`() {
        val page = fixture("github-page-16.png")
        assertSame(page, sharperMatchingFavicon(page, icon(128, Color.WHITE)))
        val blankImage = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        val blank = TabIcon.Image(BitmapPainter(blankImage.toComposeImageBitmap()))
        assertSame(page, sharperMatchingFavicon(page, blank))
        assertSame(page, sharperMatchingFavicon(page, fixture("google-cached-large.png")))
        val otherGlyph = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        otherGlyph.createGraphics().apply {
            color = Color.WHITE
            fillOval(0, 0, 128, 128)
            color = Color.BLACK
            fillRect(52, 32, 24, 64)
            fillRect(32, 52, 64, 24)
            dispose()
        }
        assertSame(page, sharperMatchingFavicon(page, TabIcon.Image(BitmapPainter(otherGlyph.toComposeImageBitmap()))))
    }

    @Test
    fun `a page with no separate HQ entry never starts a host lookup`(): Unit =
        runTest {
            val page = icon(16, Color.BLUE)
            assertSame(
                page,
                upgradeCachedFavicon(
                    "https://private.test",
                    page,
                    loadCandidate = { null },
                    refresh = { error("lookup started") },
                ),
            )
        }

    @Test
    fun `fresh larger entries are reused and expired entries refresh through the existing source`(): Unit =
        runTest {
            val page = icon(16, Color.BLUE)
            val cached = icon(128, Color.BLUE)
            val now = 2_000_000_000_000L
            assertSame(
                cached,
                upgradeCachedFavicon(
                    "https://example.test/page",
                    page,
                    now,
                    loadCandidate = { CachedFavicon(cached, now) },
                    refresh = { error("fresh entry was fetched again") },
                ),
            )
            var refreshed = false
            val large = icon(256, Color.BLUE)
            assertSame(
                large,
                upgradeCachedFavicon(
                    "https://example.test/page",
                    page,
                    now,
                    loadCandidate = { CachedFavicon(cached, now - FaviconFreshness.MAX_CACHE_AGE_MS - 1) },
                    refresh = {
                        refreshed = true
                        large
                    },
                ),
            )
            assertTrue(refreshed)
        }

    @Test
    fun `smaller HQ entries refresh sooner while recently cached results stay fresh`() {
        val now = 2_000_000_000_000L
        val small = icon(32, Color.BLUE)
        assertFalse(qualityRefreshDue(CachedFavicon(small, now), now))
        assertTrue(qualityRefreshDue(CachedFavicon(small, now - 24 * 60 * 60 * 1000L - 1), now))
        assertFalse(qualityRefreshDue(CachedFavicon(icon(128, Color.BLUE), now - 24 * 60 * 60 * 1000L - 1), now))
    }

    @Test
    fun `no icon and unrelated refreshed artwork keep the original page icon`(): Unit =
        runTest {
            val page = icon(16, Color.BLUE)
            val now = 2_000_000_000_000L
            val old = CachedFavicon(icon(32, Color.BLUE), now - FaviconFreshness.MAX_CACHE_AGE_MS - 1)
            assertSame(page, upgradeCachedFavicon("https://example.test", page, now, { old }, { null }))
            assertSame(page, upgradeCachedFavicon("https://example.test", page, now, { old }, { icon(128, Color.RED) }))
        }

    @Test
    fun `a failed quality source keeps the cached page and does not use a host guess`(): Unit =
        runTest {
            val page = icon(16, Color.BLUE)
            assertSame(
                page,
                HighQualityFaviconService.resolve(
                    "https://example.test",
                    "page-key",
                    pageIcon = { page },
                    hostGuess = { error("host guess should not run") },
                    qualityUpgrade = { _, _ -> error("cache unavailable") },
                ),
            )
        }

    private fun fixture(name: String): TabIcon.Image =
        javaClass.getResourceAsStream("/favicon-quality/$name").use { stream ->
            val image = checkNotNull(ImageIO.read(checkNotNull(stream)))
            TabIcon.Image(BitmapPainter(image.toComposeImageBitmap()))
        }

    private fun icon(
        size: Int,
        colour: Color,
    ): TabIcon.Image {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = colour
        graphics.fillRect(size / 4, size / 4, size / 2, size / 2)
        graphics.dispose()
        return TabIcon.Image(BitmapPainter(image.toComposeImageBitmap()))
    }
}
