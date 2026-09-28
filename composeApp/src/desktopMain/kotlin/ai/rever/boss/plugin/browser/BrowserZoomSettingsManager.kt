package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
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
     * The production settings path, captured once so [resetForTesting] can restore it without
     * re-deriving the literal at every call site.
     */
    private val defaultSettingsFile = BossDirectories.resolve("browser-zoom-settings.json")

    /**
     * Overridable so hermetic tests exercise the real read/write path without touching
     * `~/.boss`, as [ai.rever.boss.run.RunConfigurationManager] does. Restored by
     * [resetForTesting] callers; production code never reassigns it.
     */
    @Volatile
    internal var settingsFile: File = defaultSettingsFile
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    // Guards every read-modify-write of `settings` and both save entry points. Without it, two
    // zoom changes on different domains (or a save racing a mutation) can each read the same
    // starting map, and the second write silently drops the first's change. A plain monitor
    // rather than a coroutines Mutex because saveSettingsSync() is called from non-suspend code.
    // The monitor is held across the fsync inside atomicWriteText, so a zoom change can wait behind
    // another thread's save. That is deliberate: snapshotting under the lock and writing outside it
    // would let two saves reach the disk out of order and resurrect the older map.
    private val lock = Any()

    // Volatile so the lock-free readers (getZoomForDomain, getAllDomainSettings) see a locked
    // writer's update promptly; the reference swap itself is atomic, so a read cannot tear.
    @Volatile
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
        val snapshot = settings
        return snapshot.domainSettings[normalizedDomain]?.zoomLevel ?: snapshot.defaultZoomLevel
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
        // Under [lock] like every other write of `settings`; reentrant, so the corrupt branch's
        // saveSettingsSync() is safe.
        synchronized(lock) {
            try {
                settings = json.decodeFromString<BrowserZoomSettingsData>(settingsFile.readText())
            } catch (e: SerializationException) {
                // Corrupt content, not a read error: move the bad file aside so the stored
                // per-domain zoom levels are kept for inspection instead of being re-failed on
                // every launch or overwritten by the next save, and persist a fresh file so this
                // launch self-heals.
                logger.error(
                    LogCategory.BROWSER,
                    "Zoom settings file is corrupt, resetting to defaults",
                    decodeFailure(e),
                )
                if (!settingsFile.renameAsideCorrupt()) {
                    logger.warn(LogCategory.BROWSER, "Zoom settings file not moved aside; overwriting it")
                }
                settings = BrowserZoomSettingsData()
                saveSettingsSync()
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error loading zoom settings", error = e)
                settings = BrowserZoomSettingsData()
            }
        }
    }

    /**
     * Reset manager state and optionally redirect [settingsFile] to [testFile]; with no
     * argument, restore [defaultSettingsFile]. Call only when no save is in flight, and
     * always finish with a no-argument call, so the singleton is left where the app and
     * other tests expect it. Mirrors [ai.rever.boss.run.RunConfigurationManager].
     */
    internal fun resetForTesting(testFile: File? = null) {
        synchronized(lock) {
            settingsFile = testFile ?: defaultSettingsFile
            settings = BrowserZoomSettingsData()
            loadSettings()
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
     * Shares [lock] with every mutator, so a save writes a consistent snapshot of `settings`
     * rather than racing a concurrent change - and with [saveSettings], which delegates here, so
     * the two entry points are one ordered write path.
     */
    fun saveSettingsSync() {
        synchronized(lock) {
            try {
                settingsFile.parentFile?.mkdirs()
                settingsFile.atomicWriteText(json.encodeToString(settings))
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error saving zoom settings", error = e)
            }
        }
    }

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

/**
 * Normalize domain to handle variations.
 * Removes www. prefix and converts to lowercase.
 *
 * Top-level because [BrowserZoomSettingsManager] sits on detekt's TooManyFunctions
 * threshold inside objects, and this helper touches none of its state.
 */
private fun normalizeDomain(domain: String): String =
    domain
        .lowercase()
        .removePrefix("www.")
        .trim()
