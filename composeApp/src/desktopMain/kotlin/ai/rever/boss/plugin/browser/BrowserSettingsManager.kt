package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
data class BrowserSettingsData(
    val userAgent: String? = null,
    val customUserAgent: String? = null,
    val currentProfile: String = "browser-profile",
    val availableProfiles: List<String> = listOf("browser-profile"),
    // Browser initialization retry settings
    val maxInitRetries: Int = 3,
    val maxRecoveryAttempts: Int = 3,
    // Secret Manager settings
    val discretePasswordFill: Boolean = true,
    // Offer a generated password on new-password fields, and save it when taken (on by default)
    val suggestPasswords: Boolean = true,
    // Offer to save or update a credential the user typed after a login (on by default)
    val offerToSavePasswords: Boolean = true,
    // Tab sharing — show the co-browse share (QR) button in the browser toolbar (off by default)
    val showShareButton: Boolean = false,
)

internal sealed interface BrowserSettingsFileResult {
    data class Loaded(
        val settings: BrowserSettingsData,
    ) : BrowserSettingsFileResult

    data object Missing : BrowserSettingsFileResult

    data class Failed(
        val error: Exception,
    ) : BrowserSettingsFileResult
}

private val browserSettingsJson =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

internal fun readBrowserSettingsFile(file: File): BrowserSettingsFileResult {
    return try {
        if (!file.exists()) return BrowserSettingsFileResult.Missing
        BrowserSettingsFileResult.Loaded(browserSettingsJson.decodeFromString(file.readText()))
    } catch (e: IOException) {
        BrowserSettingsFileResult.Failed(e)
    } catch (e: SerializationException) {
        BrowserSettingsFileResult.Failed(e)
    } catch (e: SecurityException) {
        BrowserSettingsFileResult.Failed(e)
    }
}

object BrowserSettingsManager {
    private val logger = BossLogger.forComponent("BrowserSettingsManager")
    private val settingsFile = BossDirectories.resolve("browser-settings.json")

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    /**
     * No-op whose only purpose is to force this object's `init` (which loads the
     * persisted settings into [BrowserSettings] and mirrors the share-button toggle
     * to its system property) to run early — before the first browser/toolbar is
     * created. Without an early touch, settings only loaded when the Settings UI was
     * first opened, so a persisted "show share button = true" wouldn't apply on boot.
     */
    fun ensureLoaded() { /* referencing this object already ran loadSettingsSync() */ }

    private fun loadSettingsSync() {
        when (val result = readBrowserSettingsFile(settingsFile)) {
            is BrowserSettingsFileResult.Loaded -> {
                val settings = result.settings
                // Apply loaded settings
                BrowserSettings.userAgent = settings.userAgent
                BrowserSettings.customUserAgent = settings.customUserAgent
                BrowserSettings.installPersistedProfiles(
                    currentProfile = settings.currentProfile,
                    availableProfiles = settings.availableProfiles,
                )
                // Validate retry/recovery settings to prevent invalid values from manual file editing
                BrowserSettings.maxInitRetries = settings.maxInitRetries.coerceIn(1, 10)
                BrowserSettings.maxRecoveryAttempts = settings.maxRecoveryAttempts.coerceIn(1, 10)
                // Secret Manager settings (setter mirrors to the system property the plugin reads)
                BrowserSettings.discretePasswordFill = settings.discretePasswordFill
                BrowserSettings.suggestPasswords = settings.suggestPasswords
                BrowserSettings.offerToSavePasswords = settings.offerToSavePasswords
                // Tab sharing (setter mirrors to the system property the plugin reads)
                BrowserSettings.showShareButton = settings.showShareButton
            }

            BrowserSettingsFileResult.Missing -> {
                BrowserSettings.markLegacyProfileNamesUntrusted()
            }

            is BrowserSettingsFileResult.Failed -> {
                BrowserSettings.markLegacyProfileNamesUntrusted()
                logger.warn(LogCategory.BROWSER, "Failed to load browser settings", error = result.error)
            }
        }
    }

    suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val profileSnapshot = BrowserSettings.profileProtectionSnapshot()
                val settings =
                    BrowserSettingsData(
                        userAgent = BrowserSettings.userAgent,
                        customUserAgent = BrowserSettings.customUserAgent,
                        currentProfile = profileSnapshot.currentProfile,
                        availableProfiles = profileSnapshot.availableProfiles.toList(),
                        maxInitRetries = BrowserSettings.maxInitRetries,
                        maxRecoveryAttempts = BrowserSettings.maxRecoveryAttempts,
                        discretePasswordFill = BrowserSettings.discretePasswordFill,
                        suggestPasswords = BrowserSettings.suggestPasswords,
                        offerToSavePasswords = BrowserSettings.offerToSavePasswords,
                        showShareButton = BrowserSettings.showShareButton,
                    )

                val content = browserSettingsJson.encodeToString(settings)
                settingsFile.writeText(content)
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to save browser settings", error = e)
            }
        }
}
