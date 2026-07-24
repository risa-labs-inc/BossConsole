package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-domain zoom settings for a website.
 */
@Serializable
data class DomainZoomSettings(
    val domain: String,
    val zoomLevel: Double,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Container for all zoom settings data.
 */
@Serializable
data class BrowserZoomSettingsData(
    val domainSettings: Map<String, DomainZoomSettings> = emptyMap(),
    val defaultZoomLevel: Double = 1.0
)

/**
 * Manager for persisting per-domain zoom settings.
 *
 * Stores zoom preferences in ~/.boss/browser-zoom-settings.json
 * so users can have different zoom levels for different websites.
 */
object BrowserZoomSettingsManager {
    private val logger = BossLogger.forComponent("BrowserZoomSettingsManager")
    private val settingsFile = BossDirectories.resolve("browser-zoom-settings.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private var settings = BrowserZoomSettingsData()

    init {
        loadSettings()
    }

    /**
     * Get the zoom level for a domain.
     * Returns 1.0 (100%) if no custom zoom is set.
     */
    fun getZoomForDomain(domain: String): Double {
        val normalizedDomain = normalizeDomain(domain)
        return settings.domainSettings[normalizedDomain]?.zoomLevel ?: settings.defaultZoomLevel
    }

    /**
     * Set the zoom level for a domain.
     * If zoomLevel is 1.0 (100%), removes the domain entry.
     */
    fun setZoomForDomain(domain: String, zoomLevel: Double) {
        val normalizedDomain = normalizeDomain(domain)

        settings = if (kotlin.math.abs(zoomLevel - 1.0) < 0.001) {
            // Remove entry if zoom is reset to 100%
            settings.copy(
                domainSettings = settings.domainSettings - normalizedDomain
            )
        } else {
            // Update or add entry
            settings.copy(
                domainSettings = settings.domainSettings + (normalizedDomain to DomainZoomSettings(
                    domain = normalizedDomain,
                    zoomLevel = zoomLevel,
                    lastUpdated = System.currentTimeMillis()
                ))
            )
        }
    }

    /**
     * Load settings from disk.
     */
    private fun loadSettings() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                settings = json.decodeFromString<BrowserZoomSettingsData>(content)
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error loading zoom settings", error = e)
            settings = BrowserZoomSettingsData()
        }
    }

    /**
     * Save settings to disk.
     */
    suspend fun saveSettings() {
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText(json.encodeToString(settings))
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error saving zoom settings", error = e)
            }
        }
    }

    /**
     * Save settings synchronously (for use in non-coroutine contexts).
     */
    fun saveSettingsSync() {
        try {
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(json.encodeToString(settings))
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error saving zoom settings (sync)", error = e)
        }
    }

    /**
     * Normalize domain to handle variations.
     * Removes www. prefix and converts to lowercase.
     */
    private fun normalizeDomain(domain: String): String {
        return domain
            .lowercase()
            .removePrefix("www.")
            .trim()
    }

    /**
     * Extract domain from a URL.
     */
    fun extractDomain(url: String): String? {
        return try {
            java.net.URL(url).host.let { normalizeDomain(it) }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "URL has no parsable host - no per-domain zoom",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Get all stored domain zoom settings.
     */
    fun getAllDomainSettings(): Map<String, DomainZoomSettings> {
        return settings.domainSettings.toMap()
    }

    /**
     * Clear zoom setting for a specific domain.
     */
    fun clearDomainZoom(domain: String) {
        val normalizedDomain = normalizeDomain(domain)
        settings = settings.copy(
            domainSettings = settings.domainSettings - normalizedDomain
        )
    }

    /**
     * Clear all domain zoom settings.
     */
    fun clearAllSettings() {
        settings = BrowserZoomSettingsData()
    }
}
