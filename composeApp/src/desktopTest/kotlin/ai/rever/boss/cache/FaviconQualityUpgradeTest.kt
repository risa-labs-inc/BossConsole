package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
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
    fun `tall and wide glyphs retain their aspect ratio during identity checks`() {
        fun glyph(
            size: Int,
            tall: Boolean,
        ): TabIcon.Image {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().apply {
                color = Color.BLACK
                if (tall) {
                    fillRect(size * 3 / 8, size / 8, size / 4, size * 3 / 4)
                } else {
                    fillRect(size / 8, size * 3 / 8, size * 3 / 4, size / 4)
                }
                dispose()
            }
            return TabIcon.Image(BitmapPainter(image.toComposeImageBitmap()))
        }
        val page = glyph(16, true)
        assertSame(page, sharperMatchingFavicon(page, glyph(128, false)))
    }

    @Test
    fun `blank images are not evidence that two icons share artwork`() {
        val page =
            TabIcon.Image(
                BitmapPainter(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).toComposeImageBitmap()),
            )
        val larger =
            TabIcon.Image(
                BitmapPainter(BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB).toComposeImageBitmap()),
            )
        assertSame(page, sharperMatchingFavicon(page, larger))
    }

    @Test
    fun `real Google icon with different transparent padding uses its larger cached artwork`() {
        val page = fixture("google-page-16.png")
        val large = fixture("google-cached-large.png")
        assertSame(large, sharperMatchingFavicon(page, large))
    }

    @Test
    fun `real GitHub theme variants upgrade only in the page's own polarity`() {
        val page = fixture("github-page-16.png")
        // Dark tile, light glyph: the same polarity as the page, so it is used as-is.
        val sameTheme = fixture("github-refreshed-128.png")
        assertSame(sameTheme, sharperMatchingFavicon(page, sameTheme))
        // White tile, black glyph, and the bare black glyph the site serves to a client with no
        // colour scheme: both are recoloured to the page's light glyph, never shown as they are.
        for (name in listOf("github-cached-large.png", "github-light-32.png")) {
            val opposite = fixture(name)
            val upgraded = sharperMatchingFavicon(page, opposite)
            assertNotSame(opposite, upgraded, name)
            assertNotSame(page, upgraded, "$name should still upgrade, recoloured")
            assertTrue(upgraded.painter.intrinsicSize.width > page.painter.intrinsicSize.width, name)
        }
        // The bare glyph becomes light ink; the white tile becomes a dark tile.
        assertTrue(meanInkLuminance(sharperMatchingFavicon(page, fixture("github-light-32.png"))) > 0.6f)
        val darkTile = sharperMatchingFavicon(page, fixture("github-cached-large.png"))
        val tile = painterBitmap(darkTile.painter as BitmapPainter)
        assertTrue(Color(tile.getRGB(0, 0), true).let { it.alpha > 200 && it.red < 80 }, "the white tile stayed white")
    }

    @Test
    fun `a light-theme page keeps black ink when the cached artwork is white`() {
        val darkPage = fixture("github-page-16.png")
        val lightPage = invertedFixture("github-page-16.png")
        val whiteGlyph = sharperMatchingFavicon(darkPage, fixture("github-light-32.png"))
        val upgraded = sharperMatchingFavicon(lightPage, whiteGlyph)
        assertNotSame(whiteGlyph, upgraded)
        assertTrue(meanInkLuminance(upgraded) < 0.4f)
    }

    @Test
    fun `coloured artwork is never recoloured`() {
        val page = icon(16, Color.BLUE)
        assertSame(page, sharperMatchingFavicon(page, icon(128, Color.YELLOW)))
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
                    refresh = { _, _ -> error("lookup started") },
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
                    refresh = { _, _ -> error("fresh entry was fetched again") },
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
                    refresh = { _, _ ->
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
    fun `definite no icon uses the page while unrelated refreshed artwork keeps its old matching copy`(): Unit =
        runTest {
            val page = icon(16, Color.BLUE)
            val now = 2_000_000_000_000L
            val old = CachedFavicon(icon(32, Color.BLUE), now - FaviconFreshness.MAX_CACHE_AGE_MS - 1)
            assertSame(page, upgradeCachedFavicon("https://example.test", page, now, { old }, { _, _ -> null }))
            assertSame(
                old.icon,
                upgradeCachedFavicon("https://example.test", page, now, { old }, { _, _ -> icon(128, Color.RED) }),
            )
        }

    @Test
    fun `refresh receives only the host without credentials paths queries or fragments`(): Unit =
        runTest {
            val page = icon(16, Color.BLUE)
            var requested: String? = null
            upgradeCachedFavicon(
                "https://user:password@example.com:443/path?secret=value#fragment",
                page,
                1_000,
                loadCandidate = { CachedFavicon(icon(128, Color.BLUE), 1_001) },
                refresh = { url, _ ->
                    requested = url
                    null
                },
            )
            assertEquals("https://example.com/", requested)
        }

    @Test
    fun `unspecified size and future timestamps do not remain fresh forever`() {
        val unspecified =
            TabIcon.Image(
                object : Painter() {
                    override val intrinsicSize = Size.Unspecified

                    override fun DrawScope.onDraw() = Unit
                },
            )
        val now = 2_000_000_000_000L
        assertTrue(qualityRefreshDue(CachedFavicon(unspecified, now - 24 * 60 * 60 * 1000L - 1), now))
        assertTrue(qualityRefreshDue(CachedFavicon(icon(128, Color.BLUE), now + 1), now))
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

    /** Mean luminance of the opaque pixels, i.e. how light the icon's ink is. */
    private fun meanInkLuminance(icon: TabIcon.Image): Float {
        val bitmap = (icon.painter as BitmapPainter).let { painterBitmap(it) }
        val ink =
            (0 until bitmap.height)
                .flatMap { y -> (0 until bitmap.width).map { x -> bitmap.getRGB(x, y) } }
                .filter { (it ushr 24) > 128 }
        return ink
            .sumOf { p ->
                val c = Color(p, true)
                (0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue) / 255.0
            }.toFloat() / ink.size
    }

    private fun painterBitmap(painter: BitmapPainter): BufferedImage {
        val size = painter.intrinsicSize
        val image =
            androidx.compose.ui.graphics
                .ImageBitmap(size.width.toInt(), size.height.toInt())
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit
                .Density(1f),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics
                .Canvas(image),
            size,
        ) { with(painter) { draw(size) } }
        return image.toAwtImage()
    }

    private fun invertedFixture(name: String): TabIcon.Image {
        val image = ImageIO.read(javaClass.getResourceAsStream("/favicon-quality/$name"))
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val p = image.getRGB(x, y)
                image.setRGB(x, y, (p and 0xFF000000.toInt()) or (p.inv() and 0x00FFFFFF))
            }
        }
        return TabIcon.Image(BitmapPainter(image.toComposeImageBitmap()))
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
