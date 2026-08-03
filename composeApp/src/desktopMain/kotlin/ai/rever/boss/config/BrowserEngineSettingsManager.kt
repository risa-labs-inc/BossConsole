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

    /**
     * A pin equal to the bundled version carries no information, so drop it.
     *
     * The users who hit the `UnsatisfiedLinkError` this release fixes are exactly
     * those who pinned the then-newer engine in Settings. Once the app catches up,
     * that pin is redundant — but if it survives, the next bump reproduces the same
     * crash in mirror image: bundled 9.5.0 wanting Chromium 152 against a pinned
     * 9.4.0 engine shipping 151.
     *
     * Filtering inside [effectiveVersion] would not be enough: the Settings UI reads
     * `selectedVersion` directly, so the dropdown would still show a redundant
     * explicit pin. Normalising the loaded state fixes both call sites at once.
     */
    private fun BrowserEngineSettings.withoutRedundantPin(): BrowserEngineSettings =
        if (selectedVersion == VersionConstants.JXBROWSER_VERSION) copy(selectedVersion = null) else this

    private fun loadSync(): BrowserEngineSettings {
        val loaded =
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

        val normalized = loaded.withoutRedundantPin()
        if (normalized != loaded) {
            // Write the normalisation back so the file stops carrying the pin at
            // all — otherwise it returns the moment the bundled version moves on.
            // Best-effort and synchronous: this runs in init, before anything reads
            // effectiveVersion, and a failed write only costs us the cleanup.
            runCatching {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText(json.encodeToString(BrowserEngineSettings.serializer(), normalized))
            }.onSuccess {
                // Only claim the cleanup happened when it actually did — otherwise
                // this line reads as confirmation during triage while the pin is
                // still sitting on disk.
                logger.info(
                    LogCategory.BROWSER,
                    "Cleared engine pin equal to the bundled version",
                    mapOf("version" to VersionConstants.JXBROWSER_VERSION),
                )
            }.onFailure {
                logger.warn(
                    LogCategory.BROWSER,
                    "Could not clear redundant engine pin",
                    mapOf("error" to it.toString()),
                    error = it as? Exception,
                )
            }
        }
        return normalized
    }

    suspend fun updateSettings(settings: BrowserEngineSettings) =
        withContext(Dispatchers.IO) {
            // Normalise here too, so the invariant holds for any future caller
            // rather than depending on the Settings UI never writing a pin equal
            // to the bundled version.
            val normalized = settings.withoutRedundantPin()
            _currentSettings.value = normalized
            try {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText(json.encodeToString(BrowserEngineSettings.serializer(), normalized))
                logger.debug(LogCategory.BROWSER, "Browser engine settings saved")
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error saving browser engine settings", error = e)
            }
        }

    suspend fun resetToDefault() = updateSettings(BrowserEngineSettings())
}
