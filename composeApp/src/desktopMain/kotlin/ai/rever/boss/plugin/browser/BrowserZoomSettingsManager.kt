package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.renameAsideCorrupt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
    val lastUpdated: Long = System.currentTimeMillis(),
)

/**
 * Container for all zoom settings data.
 */
@Serializable
data class BrowserZoomSettingsData(
    val domainSettings: Map<String, DomainZoomSettings> = emptyMap(),
    val defaultZoomLevel: Double = 1.0,
)

/**
 * Manager for persisting per-domain zoom settings.
 *
 * Stores zoom preferences in ~/.boss/browser-zoom-settings.json
 * so users can have different zoom levels for different websites.
 */
object BrowserZoomSettingsManager {
    private val logger = BossLogger.forComponent("BrowserZoomSettingsManager")

    /**
     * `internal` (not `private`) only so [reloadForTesting] can write a fixture straight at the
     * path this object reads - `user.home` is already redirected to a fresh per-test-task
     * directory (see AGENTS.md), so this needs no further redirection of its own.
     */
    internal val settingsFile = BossDirectories.resolve("browser-zoom-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    // Guards every read-modify-write of `settings` and both save entry points below. Without it,
    // two zoom changes on different domains (or a save racing a mutation) can each read the same
    // starting map, so the second write silently drops the first's change - and saveSettings()
    // (IO dispatcher) and saveSettingsSync() (blocking) writing the same file with no ordering
    // between them tears the JSON on disk. A plain monitor rather than a coroutines Mutex because
    // saveSettingsSync() is called from non-suspend contexts and has to keep working there.
    private val lock = Any()

    // Volatile so an unsynchronized reader (getZoomForDomain, getAllDomainSettings) sees a
    // synchronized writer's update promptly rather than a stale cached reference - reads stay
    // lock-free since a torn READ isn't possible here (the reference swap itself is atomic), only
    // a torn concurrent WRITE was.
    @Volatile
    private var settings = BrowserZoomSettingsData()

    init {
        loadSettings()
    }

    /**
     * Re-run [loadSettings] against whatever is currently at [settingsFile]. Production code
     * never calls this - the object loads once, at [init] - it exists so a test can write a
     * fixture to [settingsFile] and observe the load path (including the corrupt-file self-heal)
     * without depending on class-load timing.
     */
    internal fun reloadForTesting() = loadSettings()

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
    fun setZoomForDomain(
        domain: String,
        zoomLevel: Double,
    ) {
        val normalizedDomain = normalizeDomain(domain)

        synchronized(lock) {
            settings =
                if (kotlin.math.abs(zoomLevel - 1.0) < 0.001) {
                    // Remove entry if zoom is reset to 100%
                    settings.copy(
                        domainSettings = settings.domainSettings - normalizedDomain,
                    )
                } else {
                    // Update or add entry
                    settings.copy(
                        domainSettings =
                            settings.domainSettings + (
                                normalizedDomain to
                                    DomainZoomSettings(
                                        domain = normalizedDomain,
                                        zoomLevel = zoomLevel,
                                        lastUpdated = System.currentTimeMillis(),
                                    )
                            ),
                    )
                }
        }
    }

    /**
     * Load settings from disk.
     */
    private fun loadSettings() {
        if (!settingsFile.exists()) return

        try {
            val content = settingsFile.readText()
            settings = json.decodeFromString<BrowserZoomSettingsData>(content)
        } catch (e: SerializationException) {
            // Corrupt content, not a read error: move the bad file aside so every stored
            // per-domain zoom level isn't silently dropped and re-failed on every launch, and
            // persist a fresh, empty file immediately so this launch self-heals rather than
            // re-hitting the same corrupt bytes.
            logger.error(LogCategory.BROWSER, "Zoom settings file is corrupt, resetting to defaults", error = e)
            if (!settingsFile.renameAsideCorrupt()) {
                logger.warn(LogCategory.BROWSER, "Could not move the corrupt zoom settings file aside")
            }
            settings = BrowserZoomSettingsData()
            saveSettingsSync()
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
            saveSettingsSync()
        }
    }

    /**
     * Save settings synchronously (for use in non-coroutine contexts).
     *
     * Shares [lock] with [setZoomForDomain] and the other mutators, so a save always writes a
     * complete, consistent snapshot of `settings` rather than racing a concurrent mutation - and
     * with [saveSettings], so the two entry points can no longer interleave two `writeText` calls
     * into a torn `browser-zoom-settings.json`.
     */
    fun saveSettingsSync() {
        synchronized(lock) {
            try {
                settingsFile.atomicWriteText(json.encodeToString(settings))
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error saving zoom settings", error = e)
            }
        }
    }

    /**
     * Normalize domain to handle variations.
     * Removes www. prefix and converts to lowercase.
     */
    private fun normalizeDomain(domain: String): String =
        domain
            .lowercase()
            .removePrefix("www.")
            .trim()

    /**
     * Extract domain from a URL.
     */
    fun extractDomain(url: String): String? =
        try {
            java.net
                .URL(url)
                .host
                .let { normalizeDomain(it) }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "URL has no parsable host - no per-domain zoom",
                mapOf("error" to e.toString()),
            )
            null
        }

    /**
     * Get all stored domain zoom settings.
     */
    fun getAllDomainSettings(): Map<String, DomainZoomSettings> = settings.domainSettings.toMap()

    /**
     * Clear zoom setting for a specific domain.
     */
    fun clearDomainZoom(domain: String) {
        val normalizedDomain = normalizeDomain(domain)
        synchronized(lock) {
            settings =
                settings.copy(
                    domainSettings = settings.domainSettings - normalizedDomain,
                )
        }
    }

    /**
     * Clear all domain zoom settings.
     */
    fun clearAllSettings() {
        synchronized(lock) {
            settings = BrowserZoomSettingsData()
        }
    }
}
