package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.MicrokernelRuntime
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Reconciles the plugins directory so it holds at most one JAR per pluginId.
 *
 * Different writers use different filename conventions (host updates write
 * `$pluginId-$version.jar`, the plugin-manager store writes
 * `${pluginId.replace('.','_')}_$version.jar`, GitHub installs keep arbitrary
 * asset names), so multiple versions of the same plugin can accumulate. At
 * startup the directory scan loads whichever JAR the OS lists first — an older
 * version can shadow a newer one ("Plugin already loaded" for the rest).
 *
 * This reconciler groups JARs by their manifest `pluginId`, keeps the highest
 * version, best-effort deletes the rest, and repoints `installed.json` at the
 * winner. It is idempotent and safe to run at startup and after updates.
 */
object PluginJarReconciler {
    private val logger = BossLogger.forComponent("PluginJarReconciler")

    data class ReconcileResult(
        /** One JAR per pluginId (highest version), plus non-plugin JARs passed through. */
        val winners: List<File>,
        /** Loser filenames actually removed from disk. */
        val deleted: List<String>,
        /** Unparseable / non-plugin JARs left untouched. */
        val skipped: List<String>
    )

    private data class Candidate(val file: File, val manifest: PluginManifest)

    /**
     * Scan [pluginDir] and remove stale duplicate plugin JARs. Does NOT load
     * or unload anything. Deletes are best-effort: on Windows the JVM may hold
     * a lock on a previously-loaded JAR and `delete()` returns false — the
     * stale file lingers until the next reconcile.
     */
    fun reconcilePluginDir(pluginDir: File): ReconcileResult {
        val jars = pluginDir.listFiles { file ->
            file.isFile && file.name.endsWith(".jar") && !isMicrokernelRuntimeName(file.name)
        }?.toList() ?: emptyList()

        val skipped = mutableListOf<String>()
        val candidates = mutableListOf<Candidate>()
        for (jar in jars) {
            val manifest = runCatching { PluginManifestReader.readFromJar(jar.absolutePath) }.getOrNull()
            if (manifest == null || manifest.pluginId.isBlank()) {
                // Unreadable or non-plugin JAR: never delete, never group.
                skipped.add(jar.name)
            } else if (manifest.pluginId == MicrokernelRuntime.PLUGIN_ID) {
                skipped.add(jar.name)
            } else {
                candidates.add(Candidate(jar, manifest))
            }
        }

        val installedByPluginId = PluginPersistence.getInstalledPlugins().associateBy { it.pluginId }
        val winners = mutableListOf<File>()
        val deleted = mutableListOf<String>()

        candidates.groupBy { it.manifest.pluginId }.forEach { (pluginId, group) ->
            val installedPath = installedByPluginId[pluginId]?.jarPath
            val winner = pickWinner(group, installedPath)
            winners.add(winner.file)

            for (loser in group) {
                if (loser.file == winner.file) continue
                val removed = runCatching { loser.file.delete() }.getOrDefault(false)
                if (removed) deleted.add(loser.file.name)
                logger.info(LogCategory.SYSTEM, "Removed stale duplicate plugin JAR", mapOf(
                    "pluginId" to pluginId,
                    "file" to loser.file.name,
                    "kept" to winner.file.name,
                    "deleted" to removed
                ))
            }

            // Repoint installed.json if it referenced a non-winner path, so the
            // persisted-load path loads the kept JAR rather than a deleted one.
            val entry = installedByPluginId[pluginId]
            if (entry != null && entry.jarPath != winner.file.absolutePath) {
                PluginPersistence.addInstalledPlugin(
                    pluginId = pluginId,
                    jarPath = winner.file.absolutePath,
                    enabled = entry.enabled,
                    sourceUrl = entry.sourceUrl,
                    installedVersion = winner.manifest.version
                )
                logger.info(LogCategory.SYSTEM, "Repointed installed.json at winner JAR", mapOf(
                    "pluginId" to pluginId,
                    "jarPath" to winner.file.name
                ))
            }
        }

        if (deleted.isNotEmpty()) {
            logger.info(LogCategory.SYSTEM, "Plugin dir reconciled", mapOf(
                "deleted" to deleted.size,
                "winners" to winners.size,
                "skipped" to skipped.size
            ))
        }

        // Pass non-plugin JARs through so callers never see fewer files than the
        // existing scan would have attempted.
        winners.addAll(jars.filter { it.name in skipped })
        return ReconcileResult(winners, deleted, skipped)
    }

    /**
     * Highest manifest version wins; ties (or unparseable versions, treated as
     * lowest) fall back to the installed.json path, then newest mtime, then
     * filename — always deterministic.
     */
    private fun pickWinner(group: List<Candidate>, installedPath: String?): Candidate =
        group.maxWithOrNull(
            compareBy<Candidate>(
                { Version.parse(it.manifest.version) ?: Version(0, 0, 0) },
                { it.file.absolutePath == installedPath },
                { it.file.lastModified() },
                { it.file.name }
            )
        ) ?: group.first()

    private fun isMicrokernelRuntimeName(fileName: String): Boolean =
        fileName.startsWith(MicrokernelRuntime.ARTIFACT_PREFIX) ||
            fileName.startsWith(MicrokernelRuntime.PLUGIN_ID.replace('.', '_'))
}
