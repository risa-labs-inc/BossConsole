package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertSame

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
