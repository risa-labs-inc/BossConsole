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
     * Drop any pin that is not the bundled version — which is all of them.
     *
     * JxBrowser resolves its native toolkit under
     * `Versions/<VersionInfo.chromiumVersion()>`, and that value is baked into the
     * jar. An engine carrying any other Chromium build therefore fails the native
     * load: 9.4.0 asks for `151.0.7922.72`, a pinned 9.3.0 engine only ships
     * `150.0.7871.47`. The `UnsatisfiedLinkError` this release fixes is exactly
     * that shape.
     *
     * So the "recovery and testing" escape hatch the pin was built for cannot do
     * the thing it exists to do — a setting that is guaranteed non-functional is
     * not a preference worth preserving, and honouring it would mean shipping a
     * known crash to respect a choice the user cannot benefit from. Cleared, with
     * a warning naming both versions.
     *
     * Filtering inside [effectiveVersion] would not be enough: the Settings UI
     * reads `selectedVersion` directly, so the dropdown would still render a dead
     * pin as an ordinary selection. Normalising the loaded state fixes both call
     * sites at once.
     *
     * BossConsole#118 tracks the durable fix — refusing a mismatch at engine init
     * by checking the framework version directory, which also catches skew this
     * normalisation cannot predict.
     */
    private fun BrowserEngineSettings.withoutUnusablePin(): BrowserEngineSettings =
        if (selectedVersion != null && selectedVersion != VersionConstants.JXBROWSER_VERSION) {
            copy(selectedVersion = null)
        } else {
            this
        }

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

        val stalePin = loaded.selectedVersion
        if (stalePin != null && stalePin != VersionConstants.JXBROWSER_VERSION) {
            logger.warn(
                LogCategory.BROWSER,
                "Ignoring engine pin that cannot work with this build's JxBrowser version",
                mapOf("pinned" to stalePin, "bundled" to VersionConstants.JXBROWSER_VERSION),
            )
        }

        val normalized = loaded.withoutUnusablePin()
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
                    "Cleared an unusable engine pin",
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
            val normalized = settings.withoutUnusablePin()
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
