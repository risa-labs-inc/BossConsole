package ai.rever.boss.keymap

import ai.rever.boss.keymap.presets.KeymapPresets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for issue #925's `KeymapSettingsManager` finding: a corrupt
 * `keymap-settings.json` used to be silently re-read (and re-failed) on every future launch
 * instead of self-healing, and saves used truncate-on-open `writeText`.
 *
 * `KeymapSettingsManager` is a singleton that loads once at class-init time, so this test drives
 * it through [KeymapSettingsManager.reloadForTesting] rather than relying on init timing.
 */
class KeymapSettingsManagerCorruptFileTest {
    @AfterTest
    fun cleanUp() {
        KeymapSettingsManager.settingsFile.parentFile
            ?.listFiles { f -> f.name.startsWith("${KeymapSettingsManager.settingsFile.name}.corrupt-") }
            ?.forEach { it.delete() }
        // Leave the manager holding a valid file again for any other test in this process.
        KeymapSettingsManager.reloadForTesting()
    }

    @Test
    fun `a corrupt keymap settings file is moved aside and replaced with a fresh default on reload`() {
        KeymapSettingsManager.settingsFile.parentFile?.mkdirs()
        KeymapSettingsManager.settingsFile.writeText("{ not valid json at all")

        KeymapSettingsManager.reloadForTesting()

        assertEquals(KeymapPresets.getBOSSDefault(), KeymapSettingsManager.currentSettings.value)

        // The original path now holds a fresh, valid file, not the corrupt bytes.
        assertTrue(
            !KeymapSettingsManager.settingsFile.readText().contains("not valid json"),
            "the corrupt content must not still be at the original path",
        )

        // The corrupt bytes themselves survive, renamed aside for inspection.
        val corruptSiblings =
            KeymapSettingsManager.settingsFile.parentFile
                ?.listFiles { f -> f.name.startsWith("${KeymapSettingsManager.settingsFile.name}.corrupt-") }
                .orEmpty()
        assertEquals(1, corruptSiblings.size)
        assertEquals("{ not valid json at all", corruptSiblings.single().readText())
    }
}
