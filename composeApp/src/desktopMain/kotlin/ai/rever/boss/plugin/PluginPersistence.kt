package ai.rever.boss.plugin

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object PluginPersistence {
    private val logger = BossLogger.forComponent("PluginPersistence")

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private val configFile: File by lazy {
        File(PluginStoreSetup.getPluginDir(), "installed.json")
    }

    /**
     * One row of `installed.json`.
     *
     * The three build fields exist so a locally built or hot-reloaded plugin can still be
     * identified as such after a restart. They are deliberately SEPARATE from
     * [installedVersion]: that one feeds store update checks (`isNewerVersion`) and the
     * `pluginId|version|sha256` signing anchor, so a suffixed string must never land in it.
     *
     * These optional fields have defaults, and the reader sets `ignoreUnknownKeys`, so a file
     * written by this build still loads on an older host (it ignores them) and a file written by
     * an older host still loads here (they come back null).
     */
    @Serializable
    data class InstalledPluginEntry(
        val pluginId: String,
        val jarPath: String,
        val enabled: Boolean = true,
        val sourceUrl: String? = null,
        val installedVersion: String? = null,
        /** When this jar was recorded, in epoch millis. Compared against the jar's mtime. */
        val installedAt: Long? = null,
        /** Modification time of the bytes last loaded, in epoch millis. */
        val buildStamp: Long? = null,
        /** "debug" for an unvetted local jar, "hot" once its bytes were replaced under us. */
        val buildTag: String? = null,
        /** Why the host automatically disabled this plugin, if applicable. */
        val failureReason: String? = null,
        /** When the automatic disable happened, in epoch millis. */
        val failureTimestamp: Long? = null,
        /** Number of restart attempts recorded when the automatic disable happened. */
        val failureRestartAttempts: Int? = null,
    )

    @Serializable
    data class InstalledPluginsConfig(
        val plugins: MutableList<InstalledPluginEntry> = mutableListOf(),
    )

    private var config: InstalledPluginsConfig? = null

    // Lock for synchronizing config access to prevent race conditions
    private val configLock = Any()

    /**
     * Internal load without synchronization - for use inside synchronized blocks.
     */
    private fun loadConfigInternal(): InstalledPluginsConfig {
        if (config != null) return config!!

        return try {
            if (configFile.exists()) {
                val content = configFile.readText()
                config = json.decodeFromString<InstalledPluginsConfig>(content)
                logger.info(
                    LogCategory.SYSTEM,
                    "Loaded installed plugins config",
                    mapOf(
                        "count" to (config?.plugins?.size ?: 0),
                    ),
                )
                // Backfill missing installedVersion from JAR manifests
                backfillMissingVersions(config!!)
                config!!
            } else {
                logger.debug(LogCategory.SYSTEM, "No installed plugins config found, creating new")
                config = InstalledPluginsConfig()
                config!!
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to load installed plugins config", error = e)
            config = InstalledPluginsConfig()
            config!!
        }
    }

    /**
     * Backfill null installedVersion fields by reading plugin.json from JAR manifests.
     * Saves config if any versions were updated.
     */
    private fun backfillMissingVersions(cfg: InstalledPluginsConfig) {
        var updated = false
        val updatedPlugins =
            cfg.plugins.map { entry ->
                if (entry.installedVersion == null) {
                    val version = extractVersionFromJar(entry.jarPath)
                    if (version != null) {
                        updated = true
                        logger.debug(
                            LogCategory.SYSTEM,
                            "Backfilled plugin version from JAR",
                            mapOf(
                                "pluginId" to entry.pluginId,
                                "version" to version,
                            ),
                        )
                        entry.copy(installedVersion = version)
                    } else {
                        entry
                    }
                } else {
                    entry
                }
            }
        if (updated) {
            cfg.plugins.clear()
            cfg.plugins.addAll(updatedPlugins)
            saveConfigInternal()
        }
    }

    /**
     * Extract version from a plugin JAR's META-INF/boss-plugin/plugin.json manifest.
     */
    private fun extractVersionFromJar(jarPath: String): String? =
        try {
            val jarFile = java.util.jar.JarFile(File(jarPath))
            val entry = jarFile.getJarEntry("META-INF/boss-plugin/plugin.json")
            if (entry != null) {
                val content = jarFile.getInputStream(entry).bufferedReader().readText()
                jarFile.close()
                // Simple JSON extraction — avoid pulling in full parser for this
                val versionMatch = Regex(""""version"\s*:\s*"([^"]+)"""").find(content)
                versionMatch?.groupValues?.get(1)
            } else {
                jarFile.close()
                null
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "Could not extract version from plugin JAR",
                mapOf("jarPath" to jarPath, "error" to e.toString()),
            )
            null
        }

    /**
     * Internal save without synchronization - for use inside synchronized blocks.
     */
    private fun saveConfigInternal() {
        try {
            val cfg = config ?: return
            configFile.parentFile?.mkdirs()
            configFile.writeText(json.encodeToString(cfg))
            logger.debug(
                LogCategory.SYSTEM,
                "Saved installed plugins config",
                mapOf(
                    "count" to cfg.plugins.size,
                ),
            )
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to save installed plugins config", error = e)
        }
    }

    /**
     * Load the installed plugins configuration from disk.
     */
    fun loadConfig(): InstalledPluginsConfig {
        synchronized(configLock) {
            return loadConfigInternal()
        }
    }

    /**
     * Save the configuration to disk.
     */
    private fun saveConfig() {
        synchronized(configLock) {
            saveConfigInternal()
        }
    }

    /**
     * Add an installed plugin to the config.
     * Thread-safe: entire read-modify-write operation is atomic.
     */
    fun addInstalledPlugin(
        pluginId: String,
        jarPath: String,
        enabled: Boolean = true,
        sourceUrl: String? = null,
        installedVersion: String? = null,
    ) {
        synchronized(configLock) {
            val cfg = loadConfigInternal()
            val existing = cfg.plugins.find { it.pluginId == pluginId }
            // Remove existing entry if present
            cfg.plugins.removeIf { it.pluginId == pluginId }
            // Add new entry
            cfg.plugins.add(
                InstalledPluginEntry(
                    pluginId = pluginId,
                    jarPath = jarPath,
                    enabled = enabled,
                    sourceUrl = sourceUrl,
                    installedVersion = installedVersion,
                ).carryingBuildFrom(existing, System.currentTimeMillis()),
            )
            saveConfigInternal()
            logger.info(
                LogCategory.SYSTEM,
                "Added plugin to installed config",
                mapOf(
                    "pluginId" to pluginId,
                    "jarPath" to jarPath,
                    "sourceUrl" to (sourceUrl ?: "none"),
                ),
            )
        }
    }

    /**
     * Carry the install time and build verdict of [existing] onto this freshly built row.
     *
     * A row pointing at the SAME file keeps both. That matters because [addInstalledPlugin] is
     * remove-then-add and is called by repoint paths (the reconciler, the persisted-load pass, the
     * bundled-plugin copy) as well as by real installs, so without this every startup would erase
     * what [recordBuild] wrote and a hot-reloaded plugin would lose its tag on relaunch.
     *
     * A DIFFERENT jar is a different install, so the old verdict is deliberately dropped - otherwise
     * a store update, which lands under a new versioned filename, would inherit the local build's
     * "hot" tag and report a freshly downloaded release as unreleased.
     *
     * Pure and separate so both halves of that rule are testable: [PluginPersistence] resolves its
     * file from `PluginStoreSetup.getPluginDir()`, and a test that let it do so would rewrite the
     * developer's real `installed.json`.
     */
    fun InstalledPluginEntry.carryingBuildFrom(
        existing: InstalledPluginEntry?,
        now: Long,
    ): InstalledPluginEntry {
        // Only a row for the same file has anything to say about these bytes.
        val carried = existing?.takeIf { it.jarPath == jarPath }
        return copy(
            installedAt = carried?.installedAt ?: now,
            buildStamp = carried?.buildStamp,
            buildTag = carried?.buildTag,
        )
    }

    /**
     * Merge a build verdict onto whatever row is already there. See [carryingBuildFrom] for why this
     * is a pure function.
     *
     * [fresh] is the row to write when there is no existing one. Merging rather than replacing is
     * what stops recording a verdict from losing `enabled` or `sourceUrl` - fields this call knows
     * nothing about and other paths own.
     */
    fun mergeBuildInto(
        existing: InstalledPluginEntry?,
        fresh: InstalledPluginEntry,
        now: Long,
    ): InstalledPluginEntry =
        existing?.copy(
            jarPath = fresh.jarPath,
            installedVersion = fresh.installedVersion ?: existing.installedVersion,
            installedAt = existing.installedAt ?: now,
            buildStamp = fresh.buildStamp,
            buildTag = fresh.buildTag,
        ) ?: fresh

    /**
     * Record which build of a plugin is loaded, so the verdict survives a restart.
     *
     * Upserts, and merges rather than replacing: the two paths that most need this - a jar dropped
     * into the plugins directory by hand, and a Toolbox install - historically wrote no row at all,
     * while [addInstalledPlugin] is remove-then-add and would wipe any field its caller omitted.
     *
     * Creating the missing row is a side effect worth having: [setPluginEnabled] updates an existing
     * row and silently does nothing when there is none, so those same plugins could not be disabled
     * persistently. A new row is always written **enabled**, never reflecting the load's outcome: a
     * plugin hidden for lack of access, or one that failed to register, must stay loadable on the
     * next launch, and writing false here would quietly stop it being loaded at all.
     *
     * An existing row's `enabled` is never touched.
     *
     * [buildTag] is "debug" (bytes the store never vetted), "hot" (bytes replaced under the running
     * plugin) or null (a released build).
     */
    fun recordBuild(
        pluginId: String,
        jarPath: String,
        buildStamp: Long?,
        buildTag: String?,
        installedVersion: String? = null,
    ) {
        synchronized(configLock) {
            val cfg = loadConfigInternal()
            val existing = cfg.plugins.find { it.pluginId == pluginId }
            val now = System.currentTimeMillis()
            val merged =
                mergeBuildInto(
                    existing = existing,
                    fresh =
                        InstalledPluginEntry(
                            pluginId = pluginId,
                            jarPath = jarPath,
                            enabled = true,
                            installedVersion = installedVersion,
                            installedAt = now,
                            buildStamp = buildStamp,
                            buildTag = buildTag,
                        ),
                    now = now,
                )
            // Nothing to write for the steady state. This runs on every plugin load, and for an
            // unchanged store plugin the merged row is byte-identical to the one already there
            // (installedAt preserved, buildStamp the same mtime), so without this a launch with N
            // plugins is N serialize-and-atomic-write cycles that change nothing.
            if (merged == existing) return
            if (existing != null) {
                cfg.plugins[cfg.plugins.indexOf(existing)] = merged
            } else {
                // A row pointing at a file that is gone would be retried by the persisted-load pass
                // on every launch. `installPlugin` reports success with state = DISABLED for a
                // binary-incompatible plugin whose jar the installer has already deleted, and the
                // probe deliberately records DISABLED results too, so this is reachable.
                if (!File(jarPath).isFile) {
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Not recording a build for a plugin whose jar is gone",
                        mapOf("pluginId" to pluginId, "jarPath" to jarPath),
                    )
                    return
                }
                cfg.plugins.add(merged)
            }
            saveConfigInternal()
            logger.debug(
                LogCategory.SYSTEM,
                "Recorded plugin build",
                mapOf(
                    "pluginId" to pluginId,
                    "buildTag" to (buildTag ?: "store"),
                    "buildStamp" to (buildStamp ?: 0L),
                ),
            )
        }
    }

    /** The recorded row for [pluginId], or null when nothing has recorded one. */
    fun getInstalledPlugin(pluginId: String): InstalledPluginEntry? {
        synchronized(configLock) {
            return loadConfigInternal().plugins.find { it.pluginId == pluginId }
        }
    }

    /**
     * Get the source URL for an installed plugin.
     */
    fun getSourceUrl(pluginId: String): String? {
        synchronized(configLock) {
            return loadConfigInternal().plugins.find { it.pluginId == pluginId }?.sourceUrl
        }
    }

    /**
     * Update source URL for an installed plugin.
     * Thread-safe: entire read-modify-write operation is atomic.
     */
    fun updateSourceUrl(
        pluginId: String,
        sourceUrl: String,
    ) {
        synchronized(configLock) {
            val cfg = loadConfigInternal()
            val entry = cfg.plugins.find { it.pluginId == pluginId }
            if (entry != null) {
                val index = cfg.plugins.indexOf(entry)
                cfg.plugins[index] = entry.copy(sourceUrl = sourceUrl)
                saveConfigInternal()
            }
        }
    }

    /**
     * Remove an installed plugin from the config.
     * Thread-safe: entire read-modify-write operation is atomic.
     */
    fun removeInstalledPlugin(pluginId: String) {
        synchronized(configLock) {
            val cfg = loadConfigInternal()
            val removed = cfg.plugins.removeIf { it.pluginId == pluginId }
            if (removed) {
                saveConfigInternal()
                logger.info(
                    LogCategory.SYSTEM,
                    "Removed plugin from installed config",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                )
            }
        }
    }

    /**
     * Update the enabled state of a plugin.
     * Thread-safe: entire read-modify-write operation is atomic.
     */
    fun setPluginEnabled(
        pluginId: String,
        enabled: Boolean,
    ) {
        synchronized(configLock) {
            val cfg = loadConfigInternal()
            val entry = cfg.plugins.find { it.pluginId == pluginId }
            if (entry != null) {
                val index = cfg.plugins.indexOf(entry)
                cfg.plugins[index] = entry.copy(enabled = enabled)
                saveConfigInternal()
                logger.debug(
                    LogCategory.SYSTEM,
                    "Updated plugin enabled state",
                    mapOf(
                        "pluginId" to pluginId,
                        "enabled" to enabled,
                    ),
                )
            }
        }
    }

    /**
     * Persist the one automatic-disable outcome that callers may surface later.
     * Manual enables/disables deliberately continue to use [setPluginEnabled]
     * and therefore do not create or overwrite failure details.
     */
    fun recordRestartLimitExceeded(
        pluginId: String,
        restartAttempts: Int,
        timestamp: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(configLock) {
            val cfg = loadConfigInternal()
            val entry = cfg.plugins.find { it.pluginId == pluginId } ?: return false
            val index = cfg.plugins.indexOf(entry)
            cfg.plugins[index] = restartLimitFailureEntry(entry, restartAttempts, timestamp)
            saveConfigInternal()
            return true
        }
    }

    /** Pure entry update used by [recordRestartLimitExceeded] and its persistence-focused tests. */
    fun restartLimitFailureEntry(
        entry: InstalledPluginEntry,
        restartAttempts: Int,
        timestamp: Long,
    ): InstalledPluginEntry =
        entry.copy(
            enabled = false,
            failureReason = MAX_RESTART_ATTEMPTS_FAILURE_REASON,
            failureTimestamp = timestamp,
            failureRestartAttempts = restartAttempts,
        )

    /**
     * Get all installed plugins.
     */
    fun getInstalledPlugins(): List<InstalledPluginEntry> {
        synchronized(configLock) {
            return loadConfigInternal().plugins.toList()
        }
    }

    /**
     * Check if a plugin is installed.
     */
    fun isInstalled(pluginId: String): Boolean {
        synchronized(configLock) {
            return loadConfigInternal().plugins.any { it.pluginId == pluginId }
        }
    }

    /**
     * Clear all installed plugins (for testing).
     * Thread-safe: entire operation is atomic.
     */
    fun clear() {
        synchronized(configLock) {
            config = InstalledPluginsConfig()
            saveConfigInternal()
        }
    }

    /** A successful Re-enable must survive the next launch as well as this session. */
    fun recordRestartLimitRecovery(pluginId: String) {
        synchronized(configLock) {
            val cfg = loadConfigInternal()
            val index = cfg.plugins.indexOfFirst { it.pluginId == pluginId }
            if (index < 0) return
            cfg.plugins[index] = restartLimitRecoveryEntry(cfg.plugins[index])
            saveConfigInternal()
        }
    }

    internal fun restartLimitRecoveryEntry(entry: InstalledPluginEntry): InstalledPluginEntry =
        if (entry.failureReason == MAX_RESTART_ATTEMPTS_FAILURE_REASON) {
            entry.copy(enabled = true, failureReason = null, failureTimestamp = null, failureRestartAttempts = null)
        } else {
            entry
        }

    const val MAX_RESTART_ATTEMPTS_FAILURE_REASON = "Maximum restart attempts exceeded"
}
