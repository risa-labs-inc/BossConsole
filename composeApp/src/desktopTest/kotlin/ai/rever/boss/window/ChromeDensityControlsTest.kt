package ai.rever.boss.window

import ai.rever.boss.keymap.handler.KeymapValidator
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.layout.ChromeDensity
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChromeDensityControlsTest {
    @Test
    fun `screen defaults preserve the lean rails and the status bar`() {
        val defaults = WindowAppearanceSettingsManager.getDefaultSettings()
        assertEquals(false, defaults.showLeftStrip)
        assertEquals(false, defaults.showRightStrip)
        assertEquals(true, defaults.showBottomBar)
    }

    @Test
    fun `legacy files retain comfortable density and explicit bar preferences`() {
        val settings =
            Json.decodeFromString<WindowAppearanceSettings>(
                """{"showBottomBar":false,"showLeftStrip":true,"showRightStrip":false}""",
            )
        assertEquals(ChromeDensity.COMFORTABLE, settings.density)
        assertEquals(false, settings.showBottomBar)
        assertEquals(true, settings.showLeftStrip)
        assertEquals(false, settings.showRightStrip)
    }

    @Test
    fun `each density survives serialization without changing bar preferences`() {
        for (density in ChromeDensity.entries) {
            val settings = WindowAppearanceSettings(density = density, showBottomBar = false, showLeftStrip = true)
            val encoded = Json.encodeToString(WindowAppearanceSettings.serializer(), settings)
            assertEquals(settings, Json.decodeFromString<WindowAppearanceSettings>(encoded))
        }
    }

    @Test
    fun `cycling wraps through all densities and preserves every other setting`() {
        val initial =
            WindowAppearanceSettings(density = ChromeDensity.COMPACT, showBottomBar = false, showRightStrip = true)
        val comfortable = initial.withNextDensity()
        val spacious = comfortable.withNextDensity()
        assertEquals(initial.copy(density = ChromeDensity.COMFORTABLE), comfortable)
        assertEquals(initial.copy(density = ChromeDensity.SPACIOUS), spacious)
        assertEquals(initial, spacious.withNextDensity())
    }

    @Test
    fun `every preset exposes a global density shortcut without collisions`() {
        val presets =
            listOf(
                KeymapPresets.getBOSSDefault(),
                KeymapPresets.getVSCodePreset(),
                KeymapPresets.getIntelliJPreset(),
                KeymapPresets.getEmacsPreset(),
            )
        for (preset in presets) {
            val binding = assertNotNull(preset.getBinding(KeymapActions.CHROME_DENSITY_CYCLE))
            assertEquals(ShortcutContext.GLOBAL, binding.context)
            assertTrue(KeymapValidator.validate(preset).isEmpty(), KeymapValidator.validate(preset).toString())
        }
    }
}
