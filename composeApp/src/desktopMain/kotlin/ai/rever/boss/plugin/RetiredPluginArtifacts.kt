package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import java.io.File

/**
 * The two questions `RetiredPlugins.sweep` asks about the filesystem, as functions rather than
 * lambdas at the call site.
 *
 * Both were inline in `PluginStoreSetup`, where nothing could test them - and a typo in either
 * silently disables a guard rather than failing. They live here, next to
 * [PluginArtifactCleanup], because they are about the same directory.
 */

/**
 * Deletes every jar in [pluginDir] whose manifest declares [pluginId], with its signature
 * sidecar, and reports whether the directory is clean of that plugin afterwards.
 *
 * **Matching on the manifest id, not the recorded path or a filename prefix**, for two reasons.
 * `PluginStoreSetup` already does exactly this when retiring an old system-plugin version, and
 * for the same first reason: artifact prefixes can be prefixes of each other, so
 * `boss-plugin-terminal` would match `boss-plugin-terminal-tab-*.jar`. The second is specific to
 * a retirement: `installed.json`'s `jarPath` can be stale (the reconciler and the updater both
 * rewrite paths) while a *differently named* jar for the same id is still in the directory. A
 * delete keyed on the recorded path would report success and leave that one behind.
 *
 * **Why the return value matters at all.** `DefaultPlugin.loadExternalPlugins` scans this same
 * directory after the persisted pass, in the same launch, and installs every jar it finds that
 * the manager does not know about. So a retirement that drops the `installed.json` row while a
 * jar survives does not merely leak a file: the scan reinstalls the plugin, `PluginBuildProbe`
 * writes a fresh row on load, and the next launch sweeps, announces and fails again - forever.
 * That is the copy-then-delete loop the bundled/system veto prevents, arriving by another door.
 * The sweep therefore refuses the retirement outright when this returns false, leaving the row
 * alone so the plugin keeps working and the next launch can retry.
 *
 * Deleting nothing is success: absence is the postcondition, not the delete count.
 */
internal fun purgeJarsFor(
    pluginId: String,
    pluginDir: File,
    manifestIdOf: (File) -> String?,
    deleteJar: (File) -> Boolean = { it.delete() },
    deleteSidecar: (File) -> Unit = {
        runCatching { PluginSignatureSidecar.delete(it.absolutePath) }
        runCatching { PluginBundledTrust.delete(it.absolutePath) }
    },
): Boolean {
    fun jars() =
        pluginDir
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar") && manifestIdOf(it) == pluginId }
            ?: emptyList()

    jars().forEach { jar ->
        deleteSidecar(jar)
        deleteJar(jar)
    }
    // Re-listed rather than trusting the delete results: a Windows lock makes `delete()` return
    // false silently, and a jar that was already gone returns false too. Absence is the question.
    return jars().isEmpty()
}

/**
 * Why [pluginId] would come back on its own after being uninstalled, or null if it would not.
 *
 * A bundled jar is re-copied by `copyBundledPluginsToPluginDir` and a system plugin
 * re-downloaded by `ensureSystemPluginsInstalled`, both of which run *before* the sweep - so
 * retiring one is a copy-then-delete loop on every launch, notice included.
 */
internal fun restoredAtNextLaunchReason(
    pluginId: String,
    bundledVeto: String?,
    systemPluginIds: Set<String>,
): String? =
    when {
        bundledVeto != null -> bundledVeto
        pluginId in systemPluginIds -> "is a system plugin and would be reinstalled at the next launch"
        else -> null
    }
