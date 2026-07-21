package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.TabSwitchMode
import ai.rever.boss.keymap.presets.KeymapPresets
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for the [KeymapSettings.tabSwitchMode] preference (Ctrl+Tab behavior).
 *
 * Tests cover:
 * - Default value for new users (MRU)
 * - JSON round-trip of an explicit (non-default) value
 * - Backward compatibility: keymap files written before this field existed must
 *   deserialize cleanly and fall back to the default.
 */
class KeymapSettingsTabSwitchModeTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `tab switch mode defaults to MRU for new users`() {
        assertEquals(TabSwitchMode.MRU, KeymapSettings().tabSwitchMode)
        assertEquals(TabSwitchMode.MRU, KeymapPresets.getBOSSDefault().tabSwitchMode)
    }

    @Test
    fun `an explicit tab switch mode survives a json round-trip`() {
        // Round-trip the non-default value to prove an explicit choice is persisted.
        val settings = KeymapPresets.getBOSSDefault().copy(tabSwitchMode = TabSwitchMode.POSITIONAL)
        val decoded = json.decodeFromString(
            KeymapSettings.serializer(),
            json.encodeToString(KeymapSettings.serializer(), settings)
        )
        assertEquals(TabSwitchMode.POSITIONAL, decoded.tabSwitchMode)
    }

    @Test
    fun `legacy keymap json without tabSwitchMode falls back to the default`() {
        // A keymap file written before the tabSwitchMode field existed.
        val legacyJson = """
            {
              "shortcuts": {},
              "presetName": "BOSS Default",
              "customized": false,
              "version": 1
            }
        """.trimIndent()
        val decoded = json.decodeFromString(KeymapSettings.serializer(), legacyJson)
        assertEquals(TabSwitchMode.MRU, decoded.tabSwitchMode)
    }
}
