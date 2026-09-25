package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.MicrokernelRuntime
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.ApiClassLoader
import ai.rever.boss.plugin.loader.FileHashing
import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureEnforcement
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.loader.PluginSignatureVerifier
import ai.rever.boss.plugin.loader.PluginStoreTrust
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

    /** Load-time-equivalent signature check. Injectable because the pinned key is unavailable to tests. */
    @Volatile
    internal var signatureVerifier: PluginSignatureVerifier =
        PluginSignatureVerifier(PluginStoreTrust.TRUSTED_KEYS)

    private data class Candidate(
        val file: File,
        val manifest: PluginManifest,
    ) {
        val trust: CandidateTrust by lazy { candidateTrust(file, manifest) }
    }

    private enum class CandidateTrust { INVALID, UNKNOWN, UNSIGNED, TRUSTED }

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
        val jars =
            pluginDir
                .listFiles { file ->
                    file.isFile && file.name.endsWith(".jar") && !isMicrokernelRuntimeName(file.name)
                }?.toList() ?: emptyList()

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
            // A failed sidecar read or JAR hash is not proof of an invalid signature.
            // Leave the whole group untouched until a later scan can classify it.
            if (group.any { it.trust == CandidateTrust.UNKNOWN }) {
                winners.add((persistedCandidate ?: group.first()).file)
                return@forEach
            }
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
        if (loser.trust == CandidateTrust.UNKNOWN) return
        val loserVersion = Version.parse(loser.manifest.version)
        val winnerVersion = Version.parse(winner.manifest.version)
        // During the warn-only signature rollout, a newer unsigned store update can
        // legitimately win. Preserve the older signed artifact for recovery. Under
        // strict enforcement, preserve a newer unsigned artifact for investigation.
        val preserveTrustedFallback = loser.trust == CandidateTrust.TRUSTED && winner.trust == CandidateTrust.UNSIGNED
        val preserveNewerUnsigned =
            winner.trust == CandidateTrust.TRUSTED &&
                loser.trust == CandidateTrust.UNSIGNED &&
                loserVersion != null && winnerVersion != null && loserVersion > winnerVersion
        if (preserveTrustedFallback || preserveNewerUnsigned) {
            logger.warn(
                LogCategory.SYSTEM,
                "Preserved plugin JAR across signature rollout",
                mapOf("pluginId" to pluginId, "file" to loser.file.name, "kept" to winner.file.name),
            )
            return
        }

        deleteOrDeferLoser(pluginId, loser, winner, deleted, deferred)
    }

    /**
     * The ordinary superseded-jar path once the trust guard has passed: defer while
     * a live classloader holds the loser open, otherwise delete it with its markers.
     */
    private fun deleteOrDeferLoser(
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

    /**
     * Classify [file] for reconciliation: a store sidecar
     * signature that verifies for `pluginId|version|sha256` of these exact
     * bytes (the same anchor the load path verifies), or a bundled-trust
     * marker bound to them. A present-but-invalid signature is NOT trust -
     * it is exactly the artifact this check exists to demote. Mirrors the
     * loader: bundled trust only exempts a MISSING sidecar, never an invalid
     * one. An I/O failure leaves the group untouched because it cannot justify deletion.
     */
    private fun candidateTrust(
        file: File,
        manifest: PluginManifest,
    ): CandidateTrust {
        // A sidecar that exists but cannot be read is not "missing" - the load
        // path propagates that failure, so it must not fall through to the
        // bundled-trust exemption here either.
        val signature =
            runCatching { PluginSignatureSidecar.read(file.absolutePath) }
                .onFailure { e ->
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Could not read plugin signature sidecar - treating candidate as untrusted",
                        mapOf("file" to file.name, "error" to (e.message ?: "unknown")),
                    )
                }
        val sidecar = signature.getOrNull()
        return when {
            signature.isFailure -> {
                CandidateTrust.UNKNOWN
            }

            sidecar == null -> {
                if (PluginBundledTrust.isTrusted(file.absolutePath)) CandidateTrust.TRUSTED else CandidateTrust.UNSIGNED
            }

            else -> {
                when (verifiesAnchor(file, manifest, sidecar)) {
                    true -> CandidateTrust.TRUSTED
                    false -> CandidateTrust.INVALID
                    null -> CandidateTrust.UNKNOWN
                }
            }
        }
    }

    /**
     * Whether the sidecar [signature] verifies for `pluginId|version|sha256` of
     * [file]'s current bytes - the same anchor the load path verifies.
     */
    private fun verifiesAnchor(
        file: File,
        manifest: PluginManifest,
        signature: String,
    ): Boolean? {
        val sha256 = runCatching { FileHashing.sha256(file) }.getOrNull() ?: return null
        val anchor = PluginStoreTrust.versionAnchor(manifest.pluginId, manifest.version, sha256)
        return signatureVerifier.verifySignedMessage(anchor, signature).isVerified
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
     * Invalid signatures always lose. While unsigned store releases are allowed,
     * version wins over trust so updates cannot be rolled back on every launch.
     * When enforcement is strict, trust wins over version. Ties prefer trust,
     * the installed path, modification time, then filename.
     */
    private fun pickWinner(
        group: List<Candidate>,
        installedPath: String?,
    ): Candidate {
        val enforceUnsigned = PluginSignatureEnforcement.enforceUnsigned
        return group.maxWithOrNull(
            compareBy<Candidate>(
                {
                    when {
                        it.trust == CandidateTrust.INVALID -> -1
                        enforceUnsigned && it.trust == CandidateTrust.TRUSTED -> 1
                        else -> 0
                    }
                },
                { Version.parse(it.manifest.version) ?: Version(0, 0, 0) },
                { it.trust.ordinal },
                { it.file.absolutePath == installedPath },
                { it.file.lastModified() },
                { it.file.name },
            ),
        ) ?: group.first()
    }

    private fun isMicrokernelRuntimeName(fileName: String): Boolean =
        fileName.startsWith(MicrokernelRuntime.ARTIFACT_PREFIX) ||
            fileName.startsWith(MicrokernelRuntime.PLUGIN_ID.replace('.', '_'))
}
