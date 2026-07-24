package ai.rever.boss.config

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.VersionConstants
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * User-facing browser engine preferences, persisted to ~/.boss/browser-engine-settings.json.
 *
 * @property selectedVersion Engine version pinned from Settings, or null to follow the
 *   app's bundled JxBrowser version. Pinning a version that doesn't match the bundled
 *   JxBrowser library is unsupported (JxBrowser requires matching binaries) — the
 *   Settings UI warns about this; the pin exists for recovery/testing.
 */
@Serializable
data class BrowserEngineSettings(
    val selectedVersion: String? = null,
)

/**
 * Persistence for [BrowserEngineSettings].
 *
 * Loaded synchronously in init: [ChromiumAutoDownloader.isChromiumInstalled] consults
 * the override before the first frame (main.kt pre-UI startup), so an async load could
 * race and momentarily report the wrong effective version.
 */
object BrowserEngineSettingsManager {
    private val logger = BossLogger.forComponent("BrowserEngineSettingsManager")
    private val settingsFile = BossDirectories.resolve("browser-engine-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val _currentSettings = MutableStateFlow(loadSync())
    val currentSettings: StateFlow<BrowserEngineSettings> = _currentSettings.asStateFlow()

    /** The engine version the app should install and run: user pin, else the bundled JxBrowser version. */
    val effectiveVersion: String
        get() = _currentSettings.value.selectedVersion ?: VersionConstants.JXBROWSER_VERSION

    private fun loadSync(): BrowserEngineSettings =
        try {
            if (settingsFile.exists()) {
                json.decodeFromString<BrowserEngineSettings>(settingsFile.readText())
            } else {
                BrowserEngineSettings()
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error loading browser engine settings, using defaults", error = e)
            BrowserEngineSettings()
        }

    suspend fun updateSettings(settings: BrowserEngineSettings) =
        withContext(Dispatchers.IO) {
            _currentSettings.value = settings
            try {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText(json.encodeToString(BrowserEngineSettings.serializer(), settings))
                logger.debug(LogCategory.BROWSER, "Browser engine settings saved")
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error saving browser engine settings", error = e)
            }
        }

    suspend fun resetToDefault() = updateSettings(BrowserEngineSettings())
}
