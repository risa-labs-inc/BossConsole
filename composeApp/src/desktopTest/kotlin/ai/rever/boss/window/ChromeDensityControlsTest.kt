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
        for (height in listOf(null, 956, 1000, 1440)) {
            val defaults = defaultWindowAppearanceSettings(isMacOs = true, screenHeightDp = height)
            assertEquals(false, defaults.showLeftStrip)
            assertEquals(false, defaults.showRightStrip)
            assertEquals(true, defaults.showBottomBar)
        }
    }

    @Test
    fun `read failure recovery does not apply the fresh install screen profile`() {
        for (isMacOs in listOf(false, true)) {
            val recovery = defaultWindowAppearanceSettings(isMacOs = isMacOs)
            val fresh = defaultWindowAppearanceSettings(isMacOs = isMacOs, screenHeightDp = 956)
            assertEquals(ChromeDensity.COMFORTABLE, recovery.density)
            assertEquals(ChromeDensity.COMPACT, fresh.density)
            assertEquals(isMacOs, recovery.showTitleBar)
            assertEquals(recovery.copy(density = ChromeDensity.COMPACT), fresh)
        }
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
    fun `density shortcut is assignable everywhere but opt-in for editor presets`() {
        val presets =
            listOf(
                KeymapPresets.getBOSSDefault(),
                KeymapPresets.getVSCodePreset(),
                KeymapPresets.getIntelliJPreset(),
                KeymapPresets.getEmacsPreset(),
            )
        for ((index, preset) in presets.withIndex()) {
            val binding = assertNotNull(preset.getBinding(KeymapActions.CHROME_DENSITY_CYCLE))
            assertEquals(ShortcutContext.GLOBAL, binding.context)
            assertEquals(index == 0 || index == 3, binding.enabled)
            val conflicts = KeymapValidator.validate(preset)
            assertTrue(conflicts.isEmpty(), conflicts.toString())
        }
    }
}
