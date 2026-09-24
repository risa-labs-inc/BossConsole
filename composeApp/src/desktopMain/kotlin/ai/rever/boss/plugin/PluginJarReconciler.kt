package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.MicrokernelRuntime
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.ApiClassLoader
import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.pathutils.ManagedDirectories
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
 * winner. A full scan is startup-only; an in-session update must explicitly select its plugin ids.
 *
 * A loser is left in place rather than deleted when a live [PluginClassLoader]
 * still has it open (BossConsole#72) - not because the classloader NEEDS the
 * file (it already has its own open handle), but because something inside the
 * plugin might reopen that same path directly. pty4j is the concrete case:
 * it resolves its native helper by reopening its own jar by filename the
 * first time a PTY is created, so deleting the file out from under a plugin
 * that has not finished unloading breaks that lookup even though nothing
 * about the classloader itself changed. Deferred jars are swept on a later
 * reconcile, normally at the next launch. Closing a loader does not trigger a sweep.
 * Work that outlives classloader closure remains the separate BossConsole#207 issue.
 */
object PluginJarReconciler {
    private val logger = BossLogger.forComponent("PluginJarReconciler")

    data class ReconcileResult(
        /** One selected JAR per in-scope pluginId, plus unreadable/non-plugin JARs passed through. */
        val winners: List<File>,
        /** Loser filenames actually removed from disk. */
        val deleted: List<String>,
        /** Unparseable / non-plugin JARs left untouched. */
        val skipped: List<String>,
        /**
         * Loser filenames left in place because a live classloader still has them open
         * (BossConsole#72) - deletion deferred to a future reconcile, once that loader has
         * actually closed. Distinct from [skipped]: these ARE plugin jars this reconcile
         * identified as superseded, just not yet safe to remove.
         */
        val deferred: List<String> = emptyList(),
    )

    private data class Candidate(
        val file: File,
        val manifest: PluginManifest,
    )

    /**
     * Scan [pluginDir] and remove stale duplicate plugin JARs. Does NOT load
     * or unload anything. Deletes are best-effort: on Windows the JVM may hold
     * a lock on a previously-loaded JAR and `delete()` returns false — the
     * stale file lingers until the next reconcile.
     * [pluginIds] limits an in-session update to its own plugin. A full scan is startup-only:
     * other plugins may have staged newer JARs while their old JARs are still in use.
     * [selectVerifiedApiJar] resolves the trust-gated api winner; injectable for tests.
     */
    fun reconcilePluginDir(
        pluginDir: File,
        pluginIds: Set<String>?,
        selectVerifiedApiJar: (File) -> File? = { ApiClassLoader.selectApiJar(it)?.jar },
    ): ReconcileResult {
        // The scan is the gatekeeper for what becomes a winner: a symlinked
        // jar or an entry escaping the plugins root must never be selected -
        // it would repoint installed.json at an outside path.
        val jars =
            ManagedDirectories.listContainedRegularFiles(pluginDir) { file ->
                file.name.endsWith(".jar") && !isMicrokernelRuntimeName(file.name)
            }

        val skipped = mutableListOf<String>()
        val candidates = readCandidates(jars, pluginIds, skipped)

        val installedByPluginId = PluginPersistence.getInstalledPlugins().associateBy { it.pluginId }
        val winners = mutableListOf<File>()
        val deleted = mutableListOf<String>()
        val deferred = mutableListOf<String>()

        candidates.groupBy { it.manifest.pluginId }.forEach { (pluginId, group) ->
            val installedPath = installedByPluginId[pluginId]?.jarPath
            if (pluginId == ApiClassLoader.API_PLUGIN_ID) {
                reconcileApiGroup(
                    pluginDir,
                    pluginId,
                    group,
                    installedByPluginId[pluginId],
                    selectVerifiedApiJar,
                    winners,
                    deleted,
                    deferred,
                )
                return@forEach
            }
            val unordered = group.any { Version.parse(it.manifest.version) == null }
            val persistedCandidate = group.firstOrNull { it.file.absolutePath == installedPath }
            val winner =
                if (unordered && persistedCandidate != null) persistedCandidate else pickWinner(group, installedPath)
            winners.add(winner.file)
            // Unknown version precedence cannot justify deleting the selected next-launch artifact
            // or repointing its record back to a known older release.
            if (unordered) return@forEach

            for (loser in group.filterNot { it.file == winner.file }) {
                reconcileLoser(pluginId, loser, winner, deleted, deferred)
            }

            repointInstalledEntry(pluginId, installedByPluginId[pluginId], winner)
        }

        if (deleted.isNotEmpty() || deferred.isNotEmpty()) {
            logger.info(
                LogCategory.SYSTEM,
                "Plugin dir reconciled",
                mapOf(
                    "deleted" to deleted.size,
                    "deferred" to deferred.size,
                    "winners" to winners.size,
                    "skipped" to skipped.size,
                ),
            )
        }

        // Pass non-plugin JARs through so callers never see fewer files than the
        // existing scan would have attempted.
        winners.addAll(jars.filter { it.name in skipped })
        return ReconcileResult(winners, deleted, skipped, deferred)
    }

    /**
     * Delete one superseded candidate, unless a live classloader still has it open - see the
     * class doc and [PluginClassLoader.isPathOpenByLiveLoader] for why that check exists
     * (BossConsole#72). Extracted so the caller's loop has a single jump statement (`continue`
     * lives inside `filterNot`, not here) rather than two, which is its own detekt rule.
     */
    private fun reconcileLoser(
        pluginId: String,
        loser: Candidate,
        winner: Candidate,
        deleted: MutableList<String>,
        deferred: MutableList<String>,
    ) {
        if (PluginClassLoader.isPathOpenByLiveLoader(loser.file.absolutePath)) {
            deferred.add(loser.file.name)
            logger.info(
                LogCategory.SYSTEM,
                "Deferred removing a stale plugin JAR still open by a live classloader",
                mapOf("pluginId" to pluginId, "file" to loser.file.name, "kept" to winner.file.name),
            )
            return
        }

        val removed = runCatching { loser.file.delete() }.getOrDefault(false)
        if (removed) {
            deleted.add(loser.file.name)
            // A `.sig` must never outlive the JAR it describes: left behind it is an orphan
            // now, and a hard load failure later if a JAR of the same name lands on the path.
            runCatching { PluginSignatureSidecar.delete(loser.file.absolutePath) }
            // Retain bundled provenance while deferred; remove it once its JAR is actually deleted.
            runCatching { PluginBundledTrust.delete(loser.file.absolutePath) }
        }
        logger.info(
            LogCategory.SYSTEM,
            "Removed stale duplicate plugin JAR",
            mapOf(
                "pluginId" to pluginId,
                "file" to loser.file.name,
                "kept" to winner.file.name,
                "deleted" to removed,
            ),
        )
    }

    private fun readCandidates(
        jars: List<File>,
        pluginIds: Set<String>?,
        skipped: MutableList<String>,
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        for (jar in jars) {
            val manifest = runCatching { PluginManifestReader.readFromJar(jar.absolutePath) }.getOrNull()
            if (manifest == null || manifest.pluginId.isBlank()) {
                // Unreadable or non-plugin JAR: never delete, never group.
                skipped.add(jar.name)
            } else if (manifest.pluginId == MicrokernelRuntime.PLUGIN_ID) {
                skipped.add(jar.name)
            } else if (pluginIds == null || manifest.pluginId in pluginIds) {
                candidates.add(Candidate(jar, manifest))
            }
        }

        return candidates
    }

    /**
     * Reconcile the API plugin group. For the API layer an unverifiable jar must never win:
     * the load-time trust gate refuses it, but this reconciler runs FIRST and would otherwise
     * pick the newest jar by version and delete the verified older jar it shadows, leaving the
     * host with no loadable API at all. Select only among jars the gate accepts; every rejected
     * jar becomes a loser and is cleaned up here. When nothing verifies, touch nothing - an
     * empty API layer is the loader's degraded state to own, not a reason to delete files.
     */
    // The accumulators travel together; bundling them only to satisfy the count would
    // hide what the function actually threads through.
    @Suppress("LongParameterList")
    private fun reconcileApiGroup(
        pluginDir: File,
        pluginId: String,
        group: List<Candidate>,
        installed: PluginPersistence.InstalledPluginEntry?,
        selectVerifiedApiJar: (File) -> File?,
        winners: MutableList<File>,
        deleted: MutableList<String>,
        deferred: MutableList<String>,
    ) {
        val verifiedJar = selectVerifiedApiJar(pluginDir)
        if (verifiedJar == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "Skipping api jar reconciliation: no candidate passes the trust gate",
                mapOf("candidates" to group.joinToString { it.file.name }),
            )
            return
        }
        val winnerPath = verifiedJar.absolutePath
        val winner = group.firstOrNull { it.file.absolutePath == winnerPath }
        if (winner == null) {
            // The gate's scan and ours disagree (TOCTOU or naming mismatch); fail safe.
            logger.warn(
                LogCategory.SYSTEM,
                "Skipping api jar reconciliation: verified winner not in candidate set",
                mapOf("winner" to winnerPath),
            )
            return
        }
        winners.add(winner.file)
        for (loser in group.filterNot { it.file == winner.file }) {
            reconcileLoser(pluginId, loser, winner, deleted, deferred)
        }
        repointInstalledEntry(pluginId, installed, winner)
    }

    private fun repointInstalledEntry(
        pluginId: String,
        entry: PluginPersistence.InstalledPluginEntry?,
        winner: Candidate,
    ) {
        // Repoint installed.json if it referenced a non-winner path, so the
        // persisted-load path loads the kept JAR rather than a deleted one.
        if (entry != null && entry.jarPath != winner.file.absolutePath) {
            PluginPersistence.addInstalledPlugin(
                pluginId = pluginId,
                jarPath = winner.file.absolutePath,
                enabled = entry.enabled,
                sourceUrl = entry.sourceUrl,
                installedVersion = winner.manifest.version,
            )
            logger.info(
                LogCategory.SYSTEM,
                "Repointed installed.json at winner JAR",
                mapOf(
                    "pluginId" to pluginId,
                    "jarPath" to winner.file.name,
                ),
            )
        }
    }

    /**
     * Highest manifest version wins; ties (or unparseable versions, treated as
     * lowest) fall back to the installed.json path, then newest mtime, then
     * filename — always deterministic.
     */
    private fun pickWinner(
        group: List<Candidate>,
        installedPath: String?,
    ): Candidate =
        group.maxWithOrNull(
            compareBy<Candidate>(
                { Version.parse(it.manifest.version) ?: Version(0, 0, 0) },
                { it.file.absolutePath == installedPath },
                { it.file.lastModified() },
                { it.file.name },
            ),
        ) ?: group.first()

    private fun isMicrokernelRuntimeName(fileName: String): Boolean =
        fileName.startsWith(MicrokernelRuntime.ARTIFACT_PREFIX) ||
            fileName.startsWith(MicrokernelRuntime.PLUGIN_ID.replace('.', '_'))
}
