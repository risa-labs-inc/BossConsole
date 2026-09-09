@file:Suppress("MatchingDeclarationName")

package ai.rever.boss.html

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Desktop persistence. Routing waits for loading; UI can observe defaults immediately. */
actual object HtmlFileSettingsManager {
    private val logger = BossLogger.forComponent("HtmlFileSettingsManager")
    private val store =
        HtmlFileSettingsStore(BossDirectories.resolve("html-file-settings.json")) { error ->
            logger.warn(LogCategory.UI, "Unable to read or save HTML file settings", error = error)
        }
    actual val currentSettings = store.currentSettings

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { store.awaitSettings() }
    }

    actual suspend fun awaitSettings(): HtmlFileSettings = store.awaitSettings()

    actual suspend fun saveSettings() = store.update()

    actual suspend fun updateSettings(settings: HtmlFileSettings) = store.update(settings)

    actual suspend fun setOpenMode(mode: HtmlFileOpenMode) = updateSettings(HtmlFileSettings(mode))

    actual suspend fun resetToDefault() = updateSettings(HtmlFileSettings())
}
