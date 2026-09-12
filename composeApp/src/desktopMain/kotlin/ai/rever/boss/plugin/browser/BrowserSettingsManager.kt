package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
    // Ask for consent before saving a file with a recognized executable extension (on by default)
    val warnForExecutables: Boolean = true,
)

object BrowserSettingsManager {
    private val logger = BossLogger.forComponent("BrowserSettingsManager")

    /**
     * `internal var` so a test can point it at a temp file, matching
     * `DefaultAppsSettingsManager`. There is no other seam: the path is resolved from
     * `BossDirectories`, and a round-trip test that wrote to the real
     * `~/.boss/browser-settings.json` would clobber the developer's own settings.
     */
    internal var settingsFile = BossDirectories.resolve("browser-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

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

    /**
     * Test seam: re-run the synchronous load so a [saveSettings] is visible without a process restart.
     *
     * Named for the [DefaultAppsSettingsManager.resetForTest] precedent but with deliberately
     * different semantics: this one does not reset to defaults, it re-reads. A failed load keeps
     * the in-memory values rather than clobbering them, like [loadSettingsSync] does at startup.
     */
    internal fun reloadForTest() {
        loadSettingsSync()
    }

    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val settings = json.decodeFromString<BrowserSettingsData>(content)

                // Apply loaded settings
                BrowserSettings.userAgent = settings.userAgent
                BrowserSettings.customUserAgent = settings.customUserAgent
                BrowserSettings.currentProfile = settings.currentProfile
                // Validate retry/recovery settings to prevent invalid values from manual file editing
                BrowserSettings.maxInitRetries = settings.maxInitRetries.coerceIn(1, 10)
                BrowserSettings.maxRecoveryAttempts = settings.maxRecoveryAttempts.coerceIn(1, 10)
                // Secret Manager settings (setter mirrors to the system property the plugin reads)
                BrowserSettings.discretePasswordFill = settings.discretePasswordFill
                BrowserSettings.suggestPasswords = settings.suggestPasswords
                BrowserSettings.offerToSavePasswords = settings.offerToSavePasswords
                // Tab sharing (setter mirrors to the system property the plugin reads)
                BrowserSettings.showShareButton = settings.showShareButton
                BrowserSettings.warnForExecutables = settings.warnForExecutables

                // Update available profiles if we have more
                if (settings.availableProfiles.isNotEmpty()) {
                    BrowserSettings.availableProfiles.clear()
                    BrowserSettings.availableProfiles.addAll(settings.availableProfiles)
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to load browser settings", error = e)
        }
    }

    suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val settings =
                    BrowserSettingsData(
                        userAgent = BrowserSettings.userAgent,
                        customUserAgent = BrowserSettings.customUserAgent,
                        currentProfile = BrowserSettings.currentProfile,
                        availableProfiles = BrowserSettings.availableProfiles.toList(),
                        maxInitRetries = BrowserSettings.maxInitRetries,
                        maxRecoveryAttempts = BrowserSettings.maxRecoveryAttempts,
                        discretePasswordFill = BrowserSettings.discretePasswordFill,
                        suggestPasswords = BrowserSettings.suggestPasswords,
                        offerToSavePasswords = BrowserSettings.offerToSavePasswords,
                        showShareButton = BrowserSettings.showShareButton,
                        warnForExecutables = BrowserSettings.warnForExecutables,
                    )

                val content = json.encodeToString(settings)
                settingsFile.writeText(content)
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to save browser settings", error = e)
            }
        }
}
