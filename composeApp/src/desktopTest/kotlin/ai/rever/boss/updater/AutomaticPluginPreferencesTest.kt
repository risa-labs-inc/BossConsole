package ai.rever.boss.updater

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticPluginPreferencesTest {
    @Test
    fun `legacy update preferences inherit the build default for plugin updates`() {
        val settings = Json.decodeFromString<UpdateSettingsData>("{\"autoCheckEnabled\":false}")
        assertEquals(defaultAutoUpdateEnabled(), settings.autoPluginUpdatesEnabled)
        assertFalse(settings.autoCheckEnabled)
        assertTrue(settings.pluginAutoUpdateOptOuts.isEmpty())
    }

    @Test
    fun `explicit disabled plugin preference persists independently from app updates`(): Unit =
        runBlocking {
            UpdateSettingsManager.ensureLoaded()
            val directory = Files.createTempDirectory("automatic-plugin-settings").toFile()
            val originalFile = UpdateSettingsFiles.settingsFileOverride
            val originalPlugins = UpdateSettings.autoPluginUpdatesEnabled
            val originalApp = UpdateSettings.autoUpdateEnabled
            val originalOptOuts = UpdateSettings.pluginAutoUpdateOptOuts.value
            try {
                UpdateSettingsFiles.settingsFileOverride = File(directory, "update-settings.json")
                UpdateSettings.setPluginAutomaticUpdates("test.pinned", false)
                UpdateSettings.autoPluginUpdatesEnabled = true
                assertFalse(UpdateSettings.isPluginAutomaticUpdateEnabled("test.pinned"))
                assertTrue(UpdateSettings.isPluginAutomaticUpdateEnabled("test.newly-installed"))
                UpdateSettings.autoPluginUpdatesEnabled = false
                UpdateSettings.autoUpdateEnabled = true
                UpdateSettingsManager.saveSettings()
                val persisted = Json.decodeFromString<UpdateSettingsData>(UpdateSettingsFiles.settingsFile.readText())
                assertFalse(persisted.autoPluginUpdatesEnabled)
                assertTrue("test.pinned" in persisted.pluginAutoUpdateOptOuts)
                assertTrue(persisted.autoUpdateEnabled)
                assertEquals("false", System.getProperty("boss.plugins.autoUpdate.enabled"))
            } finally {
                UpdateSettingsFiles.settingsFileOverride = originalFile
                UpdateSettings.autoPluginUpdatesEnabled = originalPlugins
                UpdateSettings.autoUpdateEnabled = originalApp
                UpdateSettings.restorePluginOptOuts(originalOptOuts)
                directory.deleteRecursively()
            }
        }
}
