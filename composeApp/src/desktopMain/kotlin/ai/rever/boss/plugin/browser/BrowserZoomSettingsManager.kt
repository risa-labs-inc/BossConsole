package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

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
    /**
     * Serializes the two save entry points against each other AND holds the
     * read-modify-write mutators ([setZoomForDomain], [clearDomainZoom],
     * [clearAllSettings]) as a single critical section (#1051): without it,
     * a save interleaved with a mutator can read state the mutator has not
     * yet committed, and two concurrent mutators can each read the same
     * state and overwrite each other's write.
     */
    private val saveLock = Any()
    private val logger = BossLogger.forComponent("BrowserZoomSettingsManager")

    /** Overridable so tests exercise the real read/write path without touching `~/.boss`. */
    internal var settingsFile: File = BossDirectories.resolve("browser-zoom-settings.json")

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    /**
     * `@Volatile` so the mutator's write is visible to readers on other
     * threads without taking [saveLock] (#1051). The lock is what makes
     * the read-modify-write atomic; the volatile is what guarantees a
     * later reader does not see stale state through CPU caching.
     */
    @Volatile
    private var settings = BrowserZoomSettingsData()

    /**
     * `true` iff writing the live settings file is safe. False ONLY when the
     * last [loadSettings] attempt hit an [IOException] reading a file that
     * exists - in that case the file might still hold a valid value we never
     * read, so overwriting it with the in-memory defaults would silently
     * destroy the user's zoom levels. A successful decode OR a quarantine
     * (the file is moved aside) clears this; the I/O-failed state clears on
     * the next [loadSettings] that succeeds (#1051 review).
     */
    private var canSaveSafely = true

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
     *
     * The read, the conditional mutation, and the assignment all run under
     * [saveLock] so a concurrent mutator cannot interleave and overwrite a
     * change (#1051). The companion [saveSettings] / [saveSettingsSync] take
     * the same lock around the write, so a save cannot read a half-applied
     * state either.
     */
    fun setZoomForDomain(
        domain: String,
        zoomLevel: Double,
    ) {
        val normalizedDomain = normalizeDomain(domain)

        synchronized(saveLock) {
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
     *
     * Quarantine ONLY on a real decode failure ([SerializationException]
     * or [IllegalArgumentException] from the JSON decoder). For an I/O
     * failure on a present file, keep the file where it is, load defaults in
     * memory, and refuse subsequent saves - the file might still hold a
     * valid value that an AV lock or a sync hiccup just kept us from reading,
     * and overwriting it with defaults would silently destroy the user's
     * zoom levels. The save gate lifts on the next load that succeeds
     * (the follow-up review on #1051).
     *
     * The in-memory `settings` and `canSaveSafely` are written under [saveLock]
     * so a concurrent save cannot serialize a half-updated state or race the
     * reload (#1051 review).
     *
     * [readText] is a seam for tests to inject a throwing read; production
     * callers do not pass it (the default reads [settingsFile]).
     */
    internal fun loadSettings(readText: () -> String = { settingsFile.readText() }) {
        if (!settingsFile.exists()) {
            // No file to load - defaults stand, the live path is empty, saves
            // are safe.
            synchronized(saveLock) {
                settings = BrowserZoomSettingsData()
                canSaveSafely = true
            }
            return
        }

        val content =
            try {
                readText()
            } catch (e: IOException) {
                // Transient read failure: file might still be valid. Keep it
                // where it is, take defaults in memory, and gate saves until
                // a load succeeds. Log and move on - the next load attempt
                // (often the very next launch) gets a clean chance to read
                // the bytes we just could not.
                logger.warn(
                    LogCategory.BROWSER,
                    "I/O error reading zoom settings - keeping file, using defaults in memory",
                    error = e,
                )
                synchronized(saveLock) {
                    settings = BrowserZoomSettingsData()
                    canSaveSafely = false
                }
                return
            }

        try {
            val decoded = json.decodeFromString<BrowserZoomSettingsData>(content)
            synchronized(saveLock) {
                settings = decoded
                canSaveSafely = true
            }
        } catch (e: SerializationException) {
            // The ONLY decode failure we treat as "the file is corrupt":
            // kotlinx.serialization throws this when the bytes do not match
            // the schema. Self-heal instead of silent data loss (#925,
            // #1051): the corrupt file is renamed aside so the fault is
            // diagnosable AND does not re-fail every launch. The live path
            // is now empty, so saves are safe.
            logger.warn(
                LogCategory.BROWSER,
                "Zoom settings file is corrupt - quarantining",
                error = e,
            )
            moveCorruptSettingsAside(settingsFile)
            synchronized(saveLock) {
                settings = BrowserZoomSettingsData()
                canSaveSafely = true
            }
        } catch (e: Exception) {
            // Any other decode exception - IllegalArgumentException,
            // NumberFormatException, a custom serializer throwing something
            // unanticipated, an unrelated bug in our code - falls into the
            // same keep-file path as [IOException]. We do NOT know whether
            // the bytes are valid, so we do not quarantine (which would
            // delete them). Defaults stand in memory; saves are gated until
            // a later load succeeds (#1051 review).
            //
            // Importantly this catches decode exceptions that escape the
            // singleton's `init { loadSettings() }` otherwise - an
            // IllegalArgumentException from kotlinx.serialization's
            // structural check, for example, used to bubble past the catch
            // list and crash startup with no log line.
            logger.warn(
                LogCategory.BROWSER,
                "Unexpected error decoding zoom settings - keeping file, using defaults in memory",
                error = e,
            )
            synchronized(saveLock) {
                settings = BrowserZoomSettingsData()
                canSaveSafely = false
            }
        }
    }

    /**
     * Save settings to disk.
     */
    suspend fun saveSettings() {
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                synchronized(saveLock) {
                    if (!canSaveSafely) {
                        logger.warn(
                            LogCategory.BROWSER,
                            "Refusing to save zoom settings - last read hit an I/O error " +
                                "and the live file may still hold a valid value",
                        )
                        return@synchronized
                    }
                    settingsFile.atomicWriteText(json.encodeToString(settings))
                }
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
            synchronized(saveLock) {
                if (!canSaveSafely) {
                    logger.warn(
                        LogCategory.BROWSER,
                        "Refusing to save zoom settings - last read hit an I/O error " +
                            "and the live file may still hold a valid value",
                    )
                    return@synchronized
                }
                settingsFile.atomicWriteText(json.encodeToString(settings))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error saving zoom settings (sync)", error = e)
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
     * Clear zoom setting for a specific domain. R-M-W under [saveLock] so
     * a concurrent [setZoomForDomain] for the same domain cannot re-introduce
     * the entry this is removing (#1051).
     */
    fun clearDomainZoom(domain: String) {
        val normalizedDomain = normalizeDomain(domain)
        synchronized(saveLock) {
            settings =
                settings.copy(
                    domainSettings = settings.domainSettings - normalizedDomain,
                )
        }
    }

    /**
     * Clear all domain zoom settings. R-M-W under [saveLock] (#1051).
     *
     * Also lifts the I/O-error save gate, so a test that has flipped
     * [canSaveSafely] to `false` (or any caller clearing settings before
     * the gate would naturally re-open via a successful read) starts clean.
     * The next [loadSettings] will set [canSaveSafely] from the file state
     * again - this just makes "clear all" a safe reset for in-memory state.
     */
    fun clearAllSettings() {
        synchronized(saveLock) {
            settings = BrowserZoomSettingsData()
            canSaveSafely = true
        }
    }
}

/**
 * Renames a corrupt settings file to `<name>.corrupt.<millis>.<uuid>` beside
 * its live path (#925, #1051): the fault stays diagnosable, the live name is
 * freed for a fresh write on the next save, and the next launch does not
 * re-read and re-fail the same bytes. Pure file operation - unit-testable
 * standalone.
 *
 * Three refinements over the original helper (#1051, #1051 review):
 *
 * - **The aside name is collision-proof.** A `System.currentTimeMillis()`
 *   timestamp only has millisecond resolution, so two quarantines in the
 *   same millisecond rename to the same target - POSIX `rename(2)` then
 *   replaces the earlier backup, and Win32 `MoveFile` reports
 *   `ERROR_ALREADY_EXISTS`. A `UUID` random component guarantees distinct
 *   asides even at sub-millisecond spacing.
 * - **`File.renameTo`'s return value is checked.** It returns false on
 *   Windows when the destination exists (the source path the next launch
 *   will try to read) and silently in many other failure modes; a silent
 *   no-op here is exactly the failure mode that re-fails the same decode
 *   on the next launch.
 * - **Asides are capped at [maxAsides] so repeated corruption cannot fill
 *   the user's settings directory.** The oldest beyond the cap are
 *   deleted; the newest stays so the most recent failure stays diagnosable.
 */
internal fun moveCorruptSettingsAside(
    file: File,
    now: () -> Long = { System.currentTimeMillis() },
    renameFn: (File, File) -> Boolean = File::renameTo,
    maxAsides: Int = 3,
    listFiles: (File) -> Array<out File>? = { it.listFiles() },
) {
    // Deliberately quiet: the caller already logged the decode failure; this
    // is the recovery step, and its own failure must not mask the original.
    runCatching {
        // The millisecond timestamp is kept for human diagnosis - a series of
        // crashes within the same second stays observable in filename order -
        // but the UUID random component is what guarantees no two quarantines
        // land on the same aside name.
        val aside = File(file.absolutePath + ".corrupt." + now() + "." + UUID.randomUUID().toString())
        if (!renameFn(file, aside)) {
            // renameTo fails on Windows when the destination already exists
            // and in other platform-specific cases; the live file would
            // otherwise still be where the next launch tries to read it.
            BossLogger.forComponent("BrowserZoomSettingsManager").warn(
                LogCategory.BROWSER,
                "Could not rename corrupt settings aside",
                mapOf("file" to file.absolutePath, "aside" to aside.absolutePath),
            )
            return@runCatching
        }
        // Cap: keep the newest [maxAsides] - delete older ones so repeated
        // corruption cannot fill the user's settings directory. The newest
        // (just-created) aside is the most diagnostic for whatever the user
        // saw last. Sort by the timestamp embedded in the aside name rather
        // than `lastModified`: two asides created in the same millisecond
        // share a `lastModified` on filesystems with millisecond resolution,
        // so the sort would not be deterministic across filesystems - the
        // embedded timestamp is the one we wrote and is sortable.
        //
        // The new aside is PINNED in the survivor set: the cap operates on
        // OTHERS only. Otherwise a same-millisecond tie (or an unparseable
        // older stamp that sorts ahead) could delete the copy we just made,
        // breaking the "newest is kept" guarantee in the comment.
        val parent = file.parentFile ?: return@runCatching
        val asidePrefix = file.name + ".corrupt."
        val asides =
            listFiles(parent)
                ?.filter { it.name.startsWith(asidePrefix) && it.name != aside.name }
                ?.sortedByDescending { asideTimestampMillis(it.name, asidePrefix) }
                ?: return@runCatching
        for (old in asides.drop(maxAsides - 1)) {
            old.delete()
        }
    }
}

/**
 * Parses the millisecond timestamp from an aside name `<name>.corrupt.<millis>.<uuid>`
 * for use as a deterministic sort key. Falls back to `Long.MIN_VALUE` if the name
 * does not match the expected shape - the entry is still considered an aside by the
 * filter, just at the bottom of any sort.
 */
private fun asideTimestampMillis(
    name: String,
    prefix: String,
): Long =
    run {
        val tail = name.removePrefix(prefix)
        val millisEnd = tail.indexOf('.')
        if (millisEnd < 0) {
            Long.MIN_VALUE
        } else {
            tail.substring(0, millisEnd).toLongOrNull() ?: Long.MIN_VALUE
        }
    }
