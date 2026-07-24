package ai.rever.boss.plugin

import ai.rever.boss.plugin.api.Version
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One row of the remote system-plugins manifest (`system_plugins` table):
 * the always-installed plugin set + version floors that used to be hardcoded
 * in [PluginStoreSetup]. Snake_case matches the table columns (rows arrive
 * via Postgrest).
 */
@Serializable
data class SystemPluginManifestEntry(
    @SerialName("plugin_id") val pluginId: String,
    @SerialName("github_repo") val githubRepo: String,
    @SerialName("artifact_prefix") val artifactPrefix: String,
    @SerialName("load_priority") val loadPriority: Int = 100,
    @SerialName("download_only") val downloadOnly: Boolean = false,
    @SerialName("min_version") val minVersion: String? = null,
    @SerialName("kernel_only") val kernelOnly: Boolean = false,
    @SerialName("enabled") val enabled: Boolean = true,
)

/**
 * Source of the system-plugins list, replacing the hardcoded
 * `PluginStoreSetup.systemPlugins`: adding a system plugin or bumping a
 * `min_version` floor becomes a Supabase row edit, not a host release.
 *
 * Resolution order (never blocks startup):
 * 1. Session list = the local cache (`~/.boss/system-plugins.json`) when
 *    present, else the hardcoded [FALLBACK] (the last-shipped set).
 * 2. [startSync] fetches the table once at startup and subscribes to
 *    Realtime changes (model: PluginStoreRealtimeService, reusing the main
 *    [SupabaseConfig] client). Every successful fetch rewrites the cache
 *    atomically FOR THE NEXT LAUNCH.
 * 3. Live application is additive-only: brand-new enabled rows are appended
 *    to the session list and reported via the callback (so
 *    ensureSystemPluginsInstalled can install them immediately); changes to
 *    EXISTING rows (min_version bumps, disables) deliberately wait for the
 *    next launch — the same "never swap under a live session" convention as
 *    the JAR updater.
 */
object SystemPluginManifestService {
    private val logger = BossLogger.forComponent("SystemPluginManifest")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private val cacheFile: File by lazy { BossDirectories.resolve("system-plugins.json") }

    /** The last-shipped hardcoded set — used when no cache exists (first run / cache wiped). */
    private val FALLBACK: List<SystemPluginManifestEntry> =
        listOf(
            SystemPluginManifestEntry(
                pluginId = "ai.rever.boss.plugin.api",
                githubRepo = "risa-labs-inc/boss-plugin-api",
                artifactPrefix = "boss-plugin-api",
                loadPriority = 0,
            ),
            SystemPluginManifestEntry(
                pluginId = ai.rever.boss.components.plugin.MicrokernelRuntime.PLUGIN_ID,
                githubRepo = ai.rever.boss.components.plugin.MicrokernelRuntime.GITHUB_REPO,
                artifactPrefix = ai.rever.boss.components.plugin.MicrokernelRuntime.ARTIFACT_PREFIX,
                loadPriority = 1,
                downloadOnly = true,
                kernelOnly = true,
            ),
            SystemPluginManifestEntry(
                pluginId = "ai.rever.boss.plugin.dynamic.pluginmanager",
                githubRepo = "risa-labs-inc/boss-plugin-plugin-manager",
                artifactPrefix = "boss-plugin-plugin-manager",
                loadPriority = 5,
            ),
            SystemPluginManifestEntry(
                pluginId = "ai.rever.boss.plugin.dynamic.terminaltab",
                githubRepo = "risa-labs-inc/boss-plugin-terminal-tab",
                artifactPrefix = "boss-plugin-terminal-tab",
                loadPriority = 10,
            ),
            SystemPluginManifestEntry(
                pluginId = "ai.rever.boss.plugin.dynamic.terminal",
                githubRepo = "risa-labs-inc/boss-plugin-terminal",
                artifactPrefix = "boss-plugin-terminal",
                loadPriority = 10,
            ),
            SystemPluginManifestEntry(
                pluginId = "ai.rever.boss.plugin.dynamic.fluckbrowser",
                githubRepo = "risa-labs-inc/boss-plugin-fluck-browser",
                artifactPrefix = "boss-plugin-fluck-browser",
                loadPriority = 10,
            ),
            SystemPluginManifestEntry(
                pluginId = "ai.rever.boss.plugin.dynamic.editortab",
                githubRepo = "risa-labs-inc/boss-plugin-editor-tab",
                artifactPrefix = "boss-plugin-editor-tab",
                loadPriority = 10,
                // 1.4.0 bundles BossEditor privately; older plugin JARs resolve
                // bosseditor from a host that no longer carries it.
                minVersion = "1.4.0",
            ),
        )

    /**
     * Plugin ids this host build cannot run without. Remote rows may
     * retarget or reprioritize them but can never disable or drop them.
     */
    private val BOOTSTRAP_PLUGIN_IDS =
        setOf(
            "ai.rever.boss.plugin.api",
            "ai.rever.boss.plugin.dynamic.pluginmanager",
        )

    private val entries = MutableStateFlow(loadCacheOrFallback())

    /**
     * Serializes refreshes: the startup fetch, the on-(re)connect catch-up,
     * and every Realtime change event can otherwise run concurrently
     * (read-modify-write on [entries], duplicate onAdditions → duplicate
     * downloads, competing cache writes).
     */
    private val refreshMutex = Mutex()

    private var channel: RealtimeChannel? = null
    private var started = false

    /** Callback fired after brand-new enabled rows were appended live. */
    private var onAdditions: (suspend () -> Unit)? = null

    /**
     * The current system-plugin set as [SystemPluginInfo]s, filtered for this
     * process (enabled; kernel-only rows only in kernel mode). Sorted with a
     * pluginId tiebreak so equal-priority rows keep a deterministic order
     * regardless of DB row order (the old hardcoded list was deterministic).
     */
    fun currentList(isKernelMode: Boolean): List<SystemPluginInfo> =
        entries.value
            .filter { it.enabled && (!it.kernelOnly || isKernelMode) }
            .sortedWith(compareBy({ it.loadPriority }, { it.pluginId }))
            .map {
                SystemPluginInfo(
                    pluginId = it.pluginId,
                    githubRepo = it.githubRepo,
                    artifactPrefix = it.artifactPrefix,
                    loadPriority = it.loadPriority,
                    downloadOnly = it.downloadOnly,
                    minVersion = it.minVersion,
                )
            }

    /**
     * Merge remote rows over the built-in [FALLBACK] so a table edit can add
     * plugins, retarget repos, raise version floors, or disable optional
     * rows — but can never DROP a row this host build ships with, LOWER a
     * minVersion floor the build requires (e.g. the editortab 1.4.0 floor
     * that prevents NoClassDefFoundError with older jars), or disable a
     * [BOOTSTRAP_PLUGIN_IDS] row. Without this, a partial/fat-fingered edit
     * would be cached verbatim and shadow the fallback on every later launch.
     */
    private fun mergeWithFallback(fetched: List<SystemPluginManifestEntry>): List<SystemPluginManifestEntry> {
        val fetchedById = fetched.associateBy { it.pluginId }
        val merged = LinkedHashMap<String, SystemPluginManifestEntry>()
        for (fallback in FALLBACK) {
            val remote = fetchedById[fallback.pluginId]
            merged[fallback.pluginId] =
                if (remote == null) {
                    fallback
                } else {
                    remote.copy(
                        minVersion = highestVersion(remote.minVersion, fallback.minVersion),
                        enabled = remote.enabled || fallback.pluginId in BOOTSTRAP_PLUGIN_IDS,
                    )
                }
        }
        for (remote in fetched) {
            merged.putIfAbsent(remote.pluginId, remote)
        }
        return merged.values.toList()
    }

    /** The higher of two optional semver floors (unparseable/null = no floor). */
    private fun highestVersion(
        a: String?,
        b: String?,
    ): String? {
        val va = a?.let { Version.parse(it) }
        val vb = b?.let { Version.parse(it) }
        return when {
            va == null -> b ?: a
            vb == null -> a
            va >= vb -> a
            else -> b
        }
    }

    /**
     * Start the startup catch-up fetch + Realtime subscription. Idempotent.
     * [onNewPluginsInstallable] runs after new rows were appended live.
     */
    fun startSync(onNewPluginsInstallable: suspend () -> Unit) {
        if (started) return
        started = true
        onAdditions = onNewPluginsInstallable

        scope.launch { refreshFromRemote() }
        subscribeToChanges()
    }

    private suspend fun refreshFromRemote() =
        refreshMutex.withLock {
            val fetched =
                try {
                    SupabaseConfig.client.postgrest
                        .from("system_plugins")
                        .select()
                        .decodeList<SystemPluginManifestEntry>()
                } catch (e: Exception) {
                    logger.warn(
                        LogCategory.NETWORK,
                        "System-plugins manifest fetch failed; using ${if (cacheFile.exists()) "cache" else "built-in fallback"}",
                        mapOf(
                            "error" to (e.message ?: e::class.simpleName),
                        ),
                    )
                    return
                }
            if (fetched.isEmpty()) {
                // An empty table is far more likely a backend misconfiguration
                // than a real "no system plugins" state — don't wipe the cache.
                logger.warn(LogCategory.NETWORK, "System-plugins manifest fetch returned no rows; keeping current list")
                return
            }

            // Floor-protected view of the remote truth; both the cache and the
            // session list only ever hold merged rows, so a partial edit can't
            // shadow the fallback's bootstrap rows or version floors.
            val merged = mergeWithFallback(fetched)
            writeCache(merged)

            // Live application is additive-only (see class KDoc). Safe as a
            // read-modify-write: refreshMutex serializes all refreshes.
            val known = entries.value.map { it.pluginId }.toSet()
            val additions = merged.filter { it.pluginId !in known && it.enabled }
            if (additions.isNotEmpty()) {
                entries.value = entries.value + additions
                logger.info(
                    LogCategory.SYSTEM,
                    "New system plugins available",
                    mapOf(
                        "pluginIds" to additions.joinToString(",") { it.pluginId },
                    ),
                )
                try {
                    onAdditions?.invoke()
                } catch (e: Exception) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Installing new system plugins failed",
                        mapOf(
                            "error" to (e.message ?: e::class.simpleName),
                        ),
                    )
                }
            }
            logger.info(
                LogCategory.SYSTEM,
                "System-plugins manifest synced",
                mapOf(
                    "rows" to fetched.size,
                    "liveAdditions" to additions.size,
                ),
            )
        }

    private fun subscribeToChanges() {
        scope.launch {
            var backoffMs = 5_000L
            val maxBackoffMs = 60_000L

            while (isActive) {
                try {
                    val client = SupabaseConfig.client
                    val ch = client.channel("system-plugins-changes")
                    channel = ch
                    val changeFlow =
                        ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "system_plugins"
                        }
                    ch.subscribe()
                    backoffMs = 5_000L
                    logger.info(LogCategory.NETWORK, "Subscribed to system_plugins changes")

                    // Catch up on (re)connect: the startup fetch can lose the
                    // race with SupabaseConfig initialization, and changes can
                    // land while the subscription was down.
                    refreshFromRemote()

                    changeFlow.collect {
                        // Any change → refetch; refreshFromRemote applies the
                        // additive-only live policy and rewrites the cache.
                        refreshFromRemote()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(
                        LogCategory.NETWORK,
                        "system_plugins subscription lost, retrying in ${backoffMs}ms",
                        mapOf(
                            "error" to (e.message ?: e::class.simpleName),
                        ),
                    )
                    try {
                        channel?.unsubscribe()
                    } catch (_: Exception) {
                    }
                    channel = null
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    private fun loadCacheOrFallback(): List<SystemPluginManifestEntry> {
        return try {
            if (cacheFile.exists()) {
                val cached = json.decodeFromString<List<SystemPluginManifestEntry>>(cacheFile.readText())
                if (cached.isNotEmpty()) {
                    logger.info(
                        LogCategory.SYSTEM,
                        "System-plugins manifest loaded from cache",
                        mapOf(
                            "rows" to cached.size,
                        ),
                    )
                    // Re-merge on load: a cache written by an OLDER build must
                    // still honor THIS build's fallback floors/bootstrap rows.
                    return mergeWithFallback(cached)
                }
                FALLBACK
            } else {
                FALLBACK
            }
        } catch (e: Exception) {
            logger.warn(
                LogCategory.SYSTEM,
                "System-plugins cache unreadable; using built-in fallback",
                mapOf(
                    "error" to (e.message ?: e::class.simpleName),
                ),
            )
            FALLBACK
        }
    }

    private fun writeCache(list: List<SystemPluginManifestEntry>) {
        try {
            // Unique-temp atomic write: crash-safe, and concurrent writers
            // can't interleave or delete each other's output.
            cacheFile.atomicWriteText(json.encodeToString(list))
        } catch (e: Exception) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to persist system-plugins cache",
                mapOf(
                    "error" to (e.message ?: e::class.simpleName),
                ),
            )
        }
    }
}
