package ai.rever.boss.components.common

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaviconContrastTest {
    private val darkTab = 0.012f // BOSS dark panel, #161D26
    private val lightTab = 0.9f

    @Test
    fun `GitHub's black octocat is inverted on a dark tab and left alone on a light one`() {
        val ink = faviconInk(fixture("github-light-32.png"))
        assertTrue(faviconNeedsInversion(ink, darkTab))
        assertFalse(faviconNeedsInversion(ink, lightTab))
    }

    @Test
    fun `a white glyph is inverted on a light tab and left alone on a dark one`() {
        val ink = faviconInk(fixture("github-page-16.png"))
        assertTrue(faviconNeedsInversion(ink, lightTab))
        assertFalse(faviconNeedsInversion(ink, darkTab))
    }

    @Test
    fun `coloured artwork is never inverted, however dark`() {
        val navy = glyph(Color(0x10, 0x10, 0x60))
        assertNull(faviconInk(navy).luminance)
        assertFalse(faviconNeedsInversion(faviconInk(navy), darkTab))
        assertFalse(faviconNeedsInversion(faviconInk(fixture("google-cached-large.png")), darkTab))
    }

    @Test
    fun `an icon on its own tile carries its own contrast`() {
        // White tile, black glyph: readable on any tab, so it is not judged.
        val ink = faviconInk(fixture("github-cached-large.png"))
        assertNull(ink.luminance)
        assertFalse(faviconNeedsInversion(ink, lightTab))
        assertFalse(faviconNeedsInversion(ink, darkTab))
    }

    @Test
    fun `mid grey ink is readable on both and stays as it is`() {
        val ink = faviconInk(glyph(Color(0x90, 0x90, 0x90)))
        assertFalse(faviconNeedsInversion(ink, darkTab))
        assertFalse(faviconNeedsInversion(ink, lightTab))
    }

    @Test
    fun `a blank icon has nothing to judge`() {
        val blank = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).toComposeImageBitmap()
        assertFalse(faviconNeedsInversion(faviconInk(blank), darkTab))
    }

    private fun glyph(colour: Color) =
        BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            .apply {
                createGraphics().apply {
                    color = colour
                    fillOval(6, 6, 20, 20)
                    dispose()
                }
            }.toComposeImageBitmap()

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/favicon-quality/$name").use { stream ->
            checkNotNull(ImageIO.read(checkNotNull(stream))).toComposeImageBitmap()
        }
}
