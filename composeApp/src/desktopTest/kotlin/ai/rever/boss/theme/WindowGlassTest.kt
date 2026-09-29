package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossThemes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowGlassTest {
    @Test
    fun `BossTerm defaults keep sidebar tint separate from combined content opacity`() {
        val settings = AppThemeSettings()
        val glass =
            WindowGlass(
                installed = true,
                coverage = settings.glassCoverage,
                tint = settings.glassTint,
                opacity = settings.glassOpacity,
            )
        assertEquals(0.5f, glass.chromeOpacity)
        assertEquals(0.75f, glass.contentOpacity)
        assertEquals(0f, glass.copy(tint = 0f, opacity = 0f).contentOpacity)
        assertEquals(1f, glass.copy(opacity = 1f).contentOpacity)
        assertEquals(1f, glass.copy(tint = 1f).contentOpacity)
        assertEquals(0.75f, glass.copy(tint = Float.NaN, opacity = Float.NaN).contentOpacity)
    }

    @Test
    fun `failed native installation stays opaque even with zero saved tint`() {
        val glass = WindowGlass(installed = false, coverage = "window", tint = 0f)
        assertEquals(1f, glass.chromeOpacity)
        assertEquals(1f, glass.contentOpacity)
    }

    @Test
    fun `sidebar coverage preserves opaque content while window coverage includes it`() {
        val sidebar = WindowGlass(installed = true, coverage = "sidebar", tint = 0.3f)
        assertEquals(0.3f, sidebar.chromeOpacity)
        assertEquals(1f, sidebar.contentOpacity)
        assertEquals(0.65f, sidebar.copy(coverage = "window").contentOpacity)
        assertEquals(1f, sidebar.copy(coverage = "off").chromeOpacity)
        assertEquals(0.5f, sidebar.copy(tint = Float.NaN).chromeOpacity)
    }

    @Test
    fun `glass identities retain opaque palettes and existing defaults`() {
        val dark = BossThemes.byId("liquid-glass-dark")
        val light = BossThemes.byId("liquid-glass-light")
        assertTrue(isGlassTheme(dark.id))
        assertTrue(isGlassTheme(light.id))
        assertFalse(isGlassTheme(BossThemes.DEFAULT_ID))
        assertFalse(dark.isLight)
        assertTrue(light.isLight)
        assertEquals(1f, dark.colors.panel.alpha)
        assertEquals(1f, light.colors.panel.alpha)
        assertEquals("nvidia", AppThemeSettings.defaultsFor(false).appThemeId)
    }

    @Test
    fun `old settings retain theme and new glass preferences round trip`() {
        val old = AppThemeSettings.decodeOrDefaults("{\"appThemeId\":\"operator\"}", false)
        assertEquals("operator", old.appThemeId)
        val glass =
            old.copy(
                appThemeId = "liquid-glass-light",
                glassCoverage = "sidebar",
                glassStyle = "clear",
                glassTint = 0.42f,
                glassOpacity = 0.6f,
            )
        val json = AppThemeSettings.storageJson.encodeToString(AppThemeSettings.serializer(), glass)
        assertEquals(glass, AppThemeSettings.decodeOrDefaults(json, false))
    }
}
