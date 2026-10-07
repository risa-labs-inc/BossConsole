package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossThemes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogColorsTest {
    @Test
    fun `glass dialogs retain the opaque base palette and readable text`() {
        for (theme in listOf(BossThemes.LIQUID_GLASS_DARK, BossThemes.LIQUID_GLASS_LIGHT)) {
            val panel = opaqueDialogPanel(theme.colors)
            assertEquals(theme.colors.panel, panel)
            assertEquals(1f, panel.alpha)
            val background = panel.luminance()
            val foreground = theme.colors.textPrimary.luminance()
            val contrast = (maxOf(background, foreground) + 0.05f) / (minOf(background, foreground) + 0.05f)
            assertTrue(contrast >= 4.5f, "${theme.id}: contrast $contrast")
        }
    }

    @Test
    fun `translucent palette tints are blended instead of made solid`() {
        val colors = BossThemes.LIQUID_GLASS_DARK.colors.copy(ink = Color.Black, panel = Color.White.copy(alpha = 0.5f))
        val panel = opaqueDialogPanel(colors)
        assertEquals(1f, panel.alpha)
        assertEquals(0.5f, panel.red, 0.01f)
        assertEquals(0.5f, panel.green, 0.01f)
        assertEquals(0.5f, panel.blue, 0.01f)
    }
}
