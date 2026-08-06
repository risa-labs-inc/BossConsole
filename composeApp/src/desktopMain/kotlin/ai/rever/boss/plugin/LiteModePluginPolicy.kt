package ai.rever.boss.plugin

import ai.rever.boss.config.BossResourceMode
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The user's own additions to the reduced-tier allowlist. */
@Serializable
private data class LiteAllowlistFile(
    val pluginIds: List<String> = emptyList(),
)

/**
 * Decides which plugins load under a reduced [BossResourceMode].
 *
 * The gate is a **load** gate, not an install gate. Plugins stay downloaded so that leaving
 * the tier is a restart rather than a re-download, and so that Settings can list what was
 * skipped by name.
 *
 * Three sources can admit a plugin, checked in this order:
 *  1. [SystemPluginManifestService.BOOTSTRAP_PLUGIN_IDS] - the host cannot run without these,
 *     so no tier may drop them. Skipping the api plugin would leave every other plugin
 *     unable to link, and skipping the plugin manager would remove the UI needed to get back
 *     out of the tier.
 *  2. The manifest's `lite_eligible` column, which curates the shipped system plugins.
 *  3. The user's own allowlist, so someone who depends on a store plugin can keep it without
 *     abandoning the tier entirely.
 *
 * Anything else is skipped, which is the direction chosen deliberately: an uncurated plugin
 * costing memory on a machine that has none is how the app dies (PartitionAlloc aborts the
 * process rather than throwing), whereas an uncurated plugin missing from a reduced tier is
 * visible in Settings and one click from coming back.
 */
object LiteModePluginPolicy {
    private val logger = BossLogger.forComponent("LiteModePluginPolicy")

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private val allowlistFile: File by lazy { BossDirectories.resolve("lite-plugins.json") }

    @Volatile private var cachedUserAllowlist: Set<String>? = null

    /** Plugin ids the user has explicitly opted back in for reduced tiers. */
    fun userAllowlist(): Set<String> =
        cachedUserAllowlist ?: synchronized(this) {
            cachedUserAllowlist ?: readUserAllowlist().also { cachedUserAllowlist = it }
        }

    /** Replaces the user allowlist. Takes effect on the next launch, like the tier itself. */
    fun setUserAllowlist(pluginIds: Set<String>) {
        synchronized(this) {
            runCatching {
                allowlistFile.parentFile?.mkdirs()
                allowlistFile.writeText(
                    json.encodeToString(
                        LiteAllowlistFile.serializer(),
                        LiteAllowlistFile(pluginIds.sorted()),
                    ),
                )
                cachedUserAllowlist = pluginIds
            }.onFailure { e ->
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not persist the Lite plugin allowlist",
                    mapOf("error" to (e.message ?: "unknown")),
                )
            }
        }
    }

    /**
     * Whether [pluginId] may load under [mode].
     *
     * Pure given its inputs so the policy is testable without a manifest, a disk or a tier -
     * [shouldLoad] below is the one that reaches for the real ones.
     */
    internal fun isAllowed(
        pluginId: String,
        mode: BossResourceMode,
        liteEligibleIds: Set<String>,
        userAllowlist: Set<String>,
        bootstrapIds: Set<String> = SystemPluginManifestService.BOOTSTRAP_PLUGIN_IDS,
    ): Boolean =
        !mode.gatesPlugins ||
            pluginId in bootstrapIds ||
            pluginId in liteEligibleIds ||
            pluginId in userAllowlist

    /** [isAllowed] against the live manifest, user allowlist and resolved tier. */
    fun shouldLoad(
        pluginId: String,
        mode: BossResourceMode,
    ): Boolean =
        isAllowed(
            pluginId = pluginId,
            mode = mode,
            liteEligibleIds = SystemPluginManifestService.liteEligibleIds(),
            userAllowlist = userAllowlist(),
        )

    private fun readUserAllowlist(): Set<String> =
        runCatching {
            if (!allowlistFile.exists()) return@runCatching emptySet<String>()
            json
                .decodeFromString(LiteAllowlistFile.serializer(), allowlistFile.readText())
                .pluginIds
                .toSet()
        }.getOrElse { e ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not read the Lite plugin allowlist - treating it as empty",
                mapOf("error" to (e.message ?: "unknown")),
            )
            emptySet()
        }
}
