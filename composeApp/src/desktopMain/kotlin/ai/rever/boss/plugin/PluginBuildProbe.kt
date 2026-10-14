package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.PluginBuildInfo
import ai.rever.boss.plugin.launchpad.DevPluginArtifacts
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * The `installed.json` row a previous load left behind, or null when nothing has.
 *
 * [sourceUrl] rides along because it comes from the same row read and answers a different question
 * from the rest: the stamps describe the bytes at [jarPath], while the source describes where the
 * plugin came from and stays true even after someone overwrites that file.
 */
data class RecordedBuild(
    val jarPath: String,
    val buildStamp: Long?,
    val buildTag: String?,
    val sourceUrl: String? = null,
)

/**
 * Everything the probe needs from outside itself, with production defaults.
 *
 * Parameters rather than a filesystem override, for the reason `PersistCrashDisableTest` spells out:
 * [PluginPersistence] resolves its file from `PluginStoreSetup.getPluginDir()`, so a test that let it
 * do so would rewrite the developer's real `installed.json`.
 */
class BuildProbeHooks(
    val mtimeOf: (jarPath: String) -> Long? = { path ->
        runCatching { File(path).lastModified() }.getOrDefault(0L).takeIf { it > 0L }
    },
    val sidecarPresent: (jarPath: String) -> Boolean = { path ->
        runCatching { PluginSignatureSidecar.read(path) != null }.getOrDefault(false)
    },
    val recordedBuild: (pluginId: String) -> RecordedBuild? = { id ->
        PluginPersistence.getInstalledPlugin(id)?.let {
            RecordedBuild(it.jarPath, it.buildStamp, it.buildTag, it.sourceUrl)
        }
    },
    val record: (pluginId: String, jarPath: String, buildStamp: Long?, buildTag: String?, version: String) -> Unit =
        { id, jarPath, buildStamp, buildTag, version ->
            PluginPersistence.recordBuild(
                pluginId = id,
                jarPath = jarPath,
                buildStamp = buildStamp,
                buildTag = buildTag,
                installedVersion = version,
            )
        },
)

/** The plugin a probe is about. Grouped so the call stays one argument as the signals grow. */
data class ProbedPlugin(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val jarPath: String,
    val systemPlugin: Boolean = false,
)

/**
 * Decides which build of a plugin just loaded, and persists the verdict.
 *
 * Runs on every successful install, which is the one choke point cold start, update, reload and the
 * evolver's hot reload all share.
 *
 * Two signals, both already on disk - nothing new has to be tracked at runtime:
 *
 * - **A `<jar>.sig` sidecar means the store vetted these exact bytes.** A sidecar that is present
 *   but invalid hard-fails the load, so on a plugin that is *running*, presence implies verified.
 *   Locally built jars simply have none.
 * - **A jar whose mtime is newer than the stamp recorded at its last load was overwritten in
 *   place**, which is exactly what an in-place hot reload does. The stamp is persisted, so the
 *   verdict survives a restart - after one, the file is not modified again, so only the recorded tag
 *   can still say what happened.
 */
object PluginBuildProbe {
    private val logger = BossLogger.forComponent("PluginBuildProbe")

    const val TAG_DEBUG = "debug"
    const val TAG_HOT = "hot"

    fun probe(
        plugin: ProbedPlugin,
        hooks: BuildProbeHooks = BuildProbeHooks(),
    ): PluginBuildInfo {
        val pluginId = plugin.pluginId
        val jarPath = plugin.jarPath
        val mtime = hooks.mtimeOf(jarPath)
        val signedBytes = hooks.sidecarPresent(jarPath)
        val row = hooks.recordedBuild(pluginId)
        // Only a row for the SAME file can say anything about these bytes; a different jar is a
        // different install. The row's source is read separately, below, because it outlives the file.
        val previous = row?.takeIf { it.jarPath == jarPath }

        // A system plugin is a released build by definition - it ships with the app or comes from a
        // GitHub release, cannot be uninstalled, and its sidecar is backfilled from the store row
        // asynchronously, so on the launch where that backfill happens it would otherwise be tagged.
        val storeSourced = plugin.systemPlugin || !row?.sourceUrl.isNullOrBlank()

        val reloadStamp =
            resolveReloadStamp(
                signedBytes = signedBytes,
                jarMtime = mtime,
                recordedStamp = previous?.buildStamp,
                recordedTag = previous?.buildTag,
            )

        val info =
            PluginBuildInfo(
                pluginId = pluginId,
                displayName = plugin.displayName,
                version = plugin.version,
                signedBytes = signedBytes,
                storeSourced = storeSourced,
                reloadStamp = reloadStamp,
            )

        if (!DevPluginArtifacts
                .isDevPluginJar(File(jarPath))
        ) {
            hooks.record(pluginId, jarPath, mtime ?: previous?.buildStamp, tagFor(info), plugin.version)
        }

        if (info.isTagged) {
            logger.info(
                LogCategory.SYSTEM,
                "Plugin is not running the store build",
                mapOf(
                    "pluginId" to pluginId,
                    "version" to info.displayVersion,
                    "reason" to info.description,
                ),
            )
        }
        return info
    }

    /** The persisted form of a verdict. Null means a released build, which carries no tag. */
    fun tagFor(info: PluginBuildInfo): String? =
        when {
            info.reloadStamp != null -> TAG_HOT
            info.isLocalBuild -> TAG_DEBUG
            else -> null
        }

    /**
     * When these bytes replaced the ones a previous load recorded, and what to stamp it with.
     *
     * Pure, so the verdict is testable without a filesystem or an `installed.json` - the same shape
     * as `resolveReloadJarPath`.
     *
     * Gated on [signedBytes] alone, never on the weaker "came from the store" signal: that one
     * describes the plugin's origin and survives someone overwriting the jar, so using it here would
     * make a store-installed plugin the one case where a hot reload goes unreported.
     */
    fun resolveReloadStamp(
        signedBytes: Boolean,
        jarMtime: Long?,
        recordedStamp: Long?,
        recordedTag: String?,
    ): Long? =
        when {
            // A store-signed jar is a released build by construction: the signature covers this exact
            // hash, and a locally rebuilt jar cannot carry a valid one (a stale sidecar hard-fails
            // the load instead). So it can never be a hot reload - which is also what makes
            // installing the store version clear the tag.
            signedBytes -> null

            jarMtime == null || recordedStamp == null -> null

            // Overwritten since we last loaded it.
            jarMtime > recordedStamp -> jarMtime

            // The same bytes we already judged hot, seen again across a restart.
            recordedTag == TAG_HOT -> recordedStamp

            else -> null
        }
}
