package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.KeyedDetachedJobs
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.Version
import ai.rever.boss.plugin.loader.ApiClassLoader
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.updater.UpdateManager
import ai.rever.boss.updater.UpdateState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Resolves what can be offered for a [PluginLoadGate], and carries out the choice.
 *
 * [remediesFor] decides *what* to offer and is deliberately pure. This is the other half: the three
 * facts it needs, and the actions behind each button. Kept separate so the decision stays testable
 * without an updater, a store or a plugins directory - and so this file can be read as "what does
 * each button actually do", which is the question a reviewer will have.
 */
internal object PluginLoadGateRecovery {
    internal val logger = BossLogger.forComponent("PluginLoadGateRecovery")

    /**
     * Remedies run DETACHED from the window that asked, and coalesced per plugin id.
     *
     * AGENTS.md states the rule for the missing-dependency installer, and it applies here for the
     * same two reasons now that a remedy downloads:
     *
     *  - The dialog is driven from a window's `rememberCoroutineScope`, so closing that window
     *    mid-download would abort the install. `StoreVersionInstaller` cleans up its `.part` file
     *    through `getOrElse`, which does NOT run for a CancellationException - so the partial
     *    download would be orphaned. Worse, a cancellation between `unload` and `load` leaves the
     *    plugin unloaded with a promoted jar and no `installed.json` entry.
     *  - `PluginLoadGateRegistry.gates` is a StateFlow and the dialog host is per-window, so every
     *    open window renders this modal for the same gate with a live button. Two clicks would
     *    otherwise race two installs on the same `.part` and target paths.
     *
     * A process-wide scope, matching PluginLoadGateRegistry: the refusal is recorded during startup
     * plugin loading, long before any window exists to own it.
     */
    private val detachedRemedies =
        KeyedDetachedJobs<String, Result<String>>(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    /**
     * Whether [candidate] meets [required], by the loader's own rule.
     *
     * `Version.parse` plus `>=` is exactly what `DynamicPluginLoader.isBossVersionCompatible` does,
     * including failing OPEN on an unparseable version - which matters for consistency rather than
     * convenience: if the loader would let the plugin through, a remedy that assumed otherwise
     * would hide a button that works.
     */
    fun satisfies(
        required: String,
        candidate: String,
    ): Boolean {
        val requiredVersion = Version.parse(required)
        val candidateVersion = Version.parse(candidate)
        // Either side unparseable fails open, which is the loader's own behaviour.
        return requiredVersion == null || candidateVersion == null || candidateVersion >= requiredVersion
    }

    /**
     * The app version an update would install, or null when there is none.
     *
     * Read off the state the updater already holds rather than triggering a check. A dialog that
     * kicked off a network round trip before it could render would appear late or not at all, and
     * "no update available" is a perfectly good thing to say when the answer is not known yet -
     * reverting is still offered, and the periodic check will change the answer on its own.
     */
    fun hostUpdateVersion(): String? =
        (UpdateManager.instance.updateState.value as? UpdateState.UpdateAvailable)
            ?.updateInfo
            ?.latestVersion
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    /**
     * The api-layer version the store publishes, or null when it cannot be asked.
     *
     * A live lookup, unlike the host update, because nothing polls the api plugin's version in the
     * background - and unlike an app update it costs one request rather than a download.
     */
    suspend fun apiUpdateVersion(): String? = publishedVersion(ApiClassLoader.API_PLUGIN_ID, "the api version")

    /** The version kept aside for [pluginId], or null when nothing was kept. */
    fun revertVersion(pluginId: String): String? =
        runCatching {
            PluginRollbackStore.availableVersion(PluginStoreSetup.getPluginDir(), pluginId)
        }.getOrNull()

    /**
     * Carry out [remedy], returning what to tell the user on success.
     *
     * Each branch ends by clearing the gate, because leaving it recorded would put the dialog back
     * in front of the user for a problem they just fixed. The exception is a failure, which keeps
     * the gate so the dialog can stay open with the reason and the remaining options.
     */
    suspend fun apply(
        gate: PluginLoadGate,
        remedy: PluginLoadRemedy,
        manager: DynamicPluginManager,
    ): Result<String> =
        detachedRemedies.run(
            key = gate.pluginId,
            onDetachedFailure = { cause ->
                // The caller is gone, so this is the only trace left of how the remedy ended.
                logger.error(
                    LogCategory.SYSTEM,
                    "A plugin load remedy failed after the window that started it went away",
                    mapOf("pluginId" to gate.pluginId),
                    error = cause,
                )
            },
        ) {
            applyNow(gate, remedy, manager)
        }

    private suspend fun applyNow(
        gate: PluginLoadGate,
        remedy: PluginLoadRemedy,
        manager: DynamicPluginManager,
    ): Result<String> =
        when (remedy) {
            is PluginLoadRemedy.UpdateHost -> updateHost(remedy)

            is PluginLoadRemedy.UpdateApi -> updateApi(gate, remedy, manager)

            is PluginLoadRemedy.RevertPlugin -> revert(gate, remedy, manager)

            is PluginLoadRemedy.ReinstallFromStore -> reinstallFromStore(gate, remedy, manager)

            // Not reachable from a button: the dialog renders this as a sentence. Handled rather
            // than thrown so a future caller cannot turn it into a crash.
            is PluginLoadRemedy.NothingAvailable -> Result.failure(IllegalStateException(remedy.reason))
        }

    /**
     * Download the app update and hand off to the existing install flow.
     *
     * Deliberately does NOT clear the gate. The plugin is still unloadable until the new app runs,
     * so clearing it would remove the only explanation of why the plugin is missing during the
     * window between downloading and restarting.
     */
    // In-flight states acknowledge the existing update; the gate stays recorded until restart.
    private suspend fun updateHost(remedy: PluginLoadRemedy.UpdateHost): Result<String> {
        val updater = UpdateManager.instance
        return applyHostUpdateRemedy(remedy, updater.updateState.value, updater::downloadUpdateInBackground)
    }

    /**
     * Install a newer api plugin, which is a hot swap rather than a restart.
     *
     * Routed through `installPlugin`, because that is where the api id is recognised and turned
     * into an unload-all / swap / reload-all - and the reload-all is what gives the refused plugin
     * its second chance without the user doing anything else.
     */
    private suspend fun updateApi(
        gate: PluginLoadGate,
        remedy: PluginLoadRemedy.UpdateApi,
        manager: DynamicPluginManager,
    ): Result<String> {
        val store =
            PluginStoreSetup.remoteRepository
                ?: return Result.failure(IllegalStateException("The plugin store is not available."))
        val installer = StoreVersionInstaller(pluginDir = { PluginStoreSetup.getPluginDir() })
        val result =
            installer.install(
                store = store,
                request =
                    StoreVersionRequest(
                        pluginId = ApiClassLoader.API_PLUGIN_ID,
                        version = remedy.availableVersion,
                        sourceUrl = null,
                        runningJarPath = manager.getPluginInfo(ApiClassLoader.API_PLUGIN_ID)?.jarPath,
                        hasLiveInstance = false,
                    ),
                unload = { id -> manager.uninstallPlugin(id, force = true).map { } },
                load = { path -> manager.installPlugin(path).map { true } },
            )
        return result.map {
            PluginLoadGateRegistry.clear(gate.pluginId)
            "Plugin API updated to ${remedy.availableVersion}."
        }
    }

    /**
     * Fetch the artifact the store signed and load that instead.
     *
     * Two things make this different from [updateApi], which otherwise does the same shape of work.
     *
     * FIRST, the unload is lenient. A refused plugin never loaded, so the ordinary
     * `uninstallPlugin(force = true)` fails - and [StoreVersionInstaller.install] treats a failed
     * unload as fatal and returns before downloading anything. That dead end is exactly what this
     * remedy exists to break, so "there was nothing to unload" is success here. It is still
     * ATTEMPTED, because a good older jar may well be loaded while the newer file on disk is the
     * refused one.
     *
     * SECOND, the refused jar is deleted afterwards. The install writes a version-named file, which
     * for a store version different from the refused one is a different name - leaving two jars
     * declaring one pluginId in the directory. The startup scan can pick either, so the next launch
     * could come back refused again with nothing on screen to explain why. The stale `.sig` goes
     * too: a sidecar describing a file that no longer exists is the state that hard-fails a load.
     *
     * Cleanup happens only after a SUCCESSFUL install, and never touches the jar that is now
     * loaded. A failed install leaves everything exactly as it was, which keeps the gate honest -
     * the plugin is still broken and the dialog still says so.
     */
    private suspend fun reinstallFromStore(
        gate: PluginLoadGate,
        remedy: PluginLoadRemedy.ReinstallFromStore,
        manager: DynamicPluginManager,
    ): Result<String> {
        val store =
            PluginStoreSetup.remoteRepository
                ?: return Result.failure(IllegalStateException("The plugin store is not available."))
        val dir = PluginStoreSetup.getPluginDir()
        // The bytes the loader refused, found by manifest rather than by name. Passed as
        // `runningJarPath` below, which is what keeps the install from writing over it.
        val refused = refusedJarFor(dir, gate.pluginId)?.absolutePath
        val installer = StoreVersionInstaller(pluginDir = { dir })
        val result =
            installer.install(
                store = store,
                request =
                    StoreVersionRequest(
                        pluginId = gate.pluginId,
                        version = remedy.version,
                        sourceUrl = null,
                        // The REFUSED jar, even though nothing is running from it. Not naming it
                        // looked defensible - there is no live plugin to protect - and it was
                        // wrong twice over.
                        //
                        // `targetFor` only avoids a name collision when this is non-null, so a
                        // null made the download target `<pluginId>_<version>.jar` unconditionally.
                        // That is a name this same path writes, so a plugin previously installed
                        // from the store and then replaced by hand resolves to the SAME file - and
                        // `activate` discards the target when the load fails, deleting the plugin's
                        // only jar. `installed.json` then points at nothing, the next launch fails
                        // with not-found instead of a signature error, `loadGateFor` returns null,
                        // and no dialog is ever shown again: the silent disappearance this whole
                        // change removes, made permanent.
                        //
                        // The restore-on-failure worry was also unfounded. `restore` calls `load`,
                        // and loading the refused jar fails verification exactly as it did at
                        // startup - so it cannot put bad bytes back into service. It only leaves
                        // them on disk, which is what makes the gate fire again next launch and is
                        // the outcome we want from a failed repair.
                        runningJarPath = refused,
                        hasLiveInstance = false,
                    ),
                unload = { id ->
                    runCatching { manager.uninstallPlugin(id, force = true) }
                    // Deliberately unconditional. See the KDoc: a refused plugin has nothing to
                    // unload, and reporting that as a failure would abort the install.
                    Result.success(Unit)
                },
                load = { path -> manager.installPlugin(path).map { true } },
            )
        return result.map { installedVersion ->
            dropRefusedArtifacts(dir, gate.pluginId, keep = manager.getPluginInfo(gate.pluginId)?.jarPath)
            PluginLoadGateRegistry.clear(gate.pluginId)
            "${gate.displayName} reinstalled at version $installedVersion."
        }
    }

    /**
     * Put the kept jar back and load it.
     *
     * The one remedy that resolves the problem outright rather than starting something: no
     * download, no restart, and the plugin is present again when it returns.
     */
    private suspend fun revert(
        gate: PluginLoadGate,
        remedy: PluginLoadRemedy.RevertPlugin,
        manager: DynamicPluginManager,
    ): Result<String> {
        val pluginDir = PluginStoreSetup.getPluginDir()
        // The refused plugin never loaded, so `getPluginInfo` is usually empty for it and the jar
        // to remove has to be found on disk. It matters: leaving the refused jar there means the
        // next launch scans it, refuses it again, and the plugin directory holds two versions of
        // one plugin id.
        val brokenJar =
            manager.getPluginInfo(gate.pluginId)?.jarPath
                ?: refusedJarFor(pluginDir, gate.pluginId)?.absolutePath
        val restored =
            PluginRollbackStore.restore(pluginDir, gate.pluginId, brokenJar)
                ?: return Result.failure(
                    IllegalStateException("Version ${remedy.toVersion} is no longer available to restore."),
                )
        return manager
            .installPlugin(restored.absolutePath)
            .map { info ->
                PluginLoadGateRegistry.clear(gate.pluginId)
                "${info.manifest.displayName} is back on version ${remedy.toVersion}."
            }.onFailure { e ->
                logger.error(
                    LogCategory.SYSTEM,
                    "Restored the previous plugin jar but it did not load",
                    mapOf("pluginId" to gate.pluginId, "version" to remedy.toVersion),
                    error = e,
                )
            }
    }
}

/**
 * The jar in [pluginDir] declaring [pluginId], read from each manifest rather than guessed.
 *
 * By manifest, not by filename. Jars arrive under at least three conventions - the host's
 * `<pluginId>-<version>.jar`, the Toolbox's `<plugin_id>_<version>.jar` and whatever a sideloaded
 * file is called - so a name match would miss exactly the case a user is stuck in. This is the
 * same identification `PluginJarReconciler` performs for the same reason.
 */
private fun refusedJarFor(
    pluginDir: java.io.File,
    pluginId: String,
): java.io.File? =
    pluginDir
        .listFiles { f: java.io.File -> f.isFile && f.name.endsWith(".jar") }
        ?.firstOrNull { jar ->
            runCatching {
                ai.rever.boss.plugin.loader.PluginManifestReader
                    .readFromJar(jar.absolutePath)
                    .pluginId
            }.getOrNull() == pluginId
        }

/**
 * How long a store lookup may hold the dialog back before we give up and show the offline copy.
 *
 * Well under the client's own 30s request timeout, on purpose: the user is looking at a missing
 * plugin, and a few seconds of nothing is the most this may cost them.
 */
private const val STORE_LOOKUP_TIMEOUT_MS = 6_000L

/**
 * The version the store publishes for [pluginId], or null when it cannot be asked.
 *
 * THE REMOTE REPOSITORY, not `repositoryManager`, and that is the whole correctness of both
 * callers. The manager merges local and remote, and the local copy is the jar already
 * installed - for an api floor that is the version failing to satisfy it, and for a signature
 * refusal it is the very file that cannot be trusted. Either way the merged answer is the one
 * we must not act on.
 */
private suspend fun publishedVersion(
    pluginId: String,
    what: String,
): String? {
    val store = PluginStoreSetup.remoteRepository ?: return null
    // BOUNDED, because the dialog does not render until this returns. The CIO client allows a
    // 30s requestTimeout, and this whole change exists to stop a refusal being invisible - so
    // an unreachable store must not replace a silent failure with half a minute of nothing.
    // Timing out reads as "could not be asked", which is already a state with sensible copy.
    val lookup =
        withTimeoutOrNull(STORE_LOOKUP_TIMEOUT_MS) {
            // Both failure shapes, flattened: `getPlugin` returns `Result.failure` for store
            // errors (and the bundled repository rethrows a caller's cancellation, which this
            // runCatching catches) - and the `PluginRepository` interface permits either shape -
            // so runCatching alone would report success with a null inside.
            runCatching { store.getPlugin(pluginId) }.getOrElse { Result.failure(it) }
        }
    // Null covers two different outcomes, and each gets its own line so the log distinguishes
    // "the store is slow" from "the store said no".
    if (lookup == null) PluginLoadGateRecovery.logger.warn(LogCategory.SYSTEM, "Timed out asking the store for $what")
    lookup?.exceptionOrNull()?.let { e ->
        PluginLoadGateRecovery.logger.warn(LogCategory.SYSTEM, "Could not ask the store for $what: ${e.message}")
    }
    return lookup?.getOrNull()?.version?.takeIf { it.isNotBlank() }
}

/** The version the store serves for [pluginId], for the signature-reinstall remedy. */
internal suspend fun storeVersion(pluginId: String): String? = publishedVersion(pluginId, "a replacement copy")

/**
 * Delete every jar declaring [pluginId] except [keep], and each one's `.sig` sidecar.
 *
 * `keep` is the jar the plugin is loaded from after a successful reinstall. Null means the install
 * reported success but the manager has no record of it - a state we should not act on, so nothing is
 * deleted: removing files while unsure which one is live is how a working plugin disappears.
 *
 * Sidecars go with their jar. A `.sig` whose jar is gone is harmless on its own (nothing reads it),
 * but leaving one that a LATER install of the same filename would inherit is the present-but-wrong
 * signature that hard-fails a load - the exact failure being recovered from here.
 *
 * LOGGED, both outcomes. Deleting a jar that failed signature verification is worth a record on its
 * own, and a delete can fail from a lingering handle - not exotic on Windows in a directory of jars
 * that were just loaded and unloaded. When it does, the two-jars-one-pluginId state this exists to
 * prevent survives, and silence would be the only trace.
 *
 * `internal` so it can be tested against a temp directory. It reaches the filesystem directly
 * rather than through [StoreVersionHooks], which is the seam the installer uses - the asymmetry is
 * deliberate for now, since the hooks interface is shaped around one target jar rather than a sweep.
 */
internal fun dropRefusedArtifacts(
    pluginDir: File,
    pluginId: String,
    keep: String?,
) {
    if (keep == null) return
    val keepPath = runCatching { File(keep).absolutePath }.getOrNull() ?: return
    val jars =
        pluginDir
            .listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
            ?.filter { jar ->
                jar.absolutePath != keepPath &&
                    runCatching {
                        PluginManifestReader.readFromJar(jar.absolutePath).pluginId
                    }.getOrNull() == pluginId
            }.orEmpty()
    for (jar in jars) {
        val sidecar = File("${jar.absolutePath}.sig")
        val jarGone = runCatching { jar.delete() }.getOrDefault(false)
        val sidecarGone = runCatching { !sidecar.exists() || sidecar.delete() }.getOrDefault(false)
        if (jarGone && sidecarGone) {
            PluginLoadGateRecovery.logger.info(
                LogCategory.SYSTEM,
                "Removed a plugin jar that failed signature verification",
                mapOf("pluginId" to pluginId, "jarPath" to jar.absolutePath),
            )
        } else {
            // Not fatal - the reinstall already succeeded and the good jar is loaded - but the
            // leftover is what a later startup scan could pick instead, so say so.
            PluginLoadGateRecovery.logger.warn(
                LogCategory.SYSTEM,
                "Could not remove the refused plugin jar; a later startup may pick it up",
                mapOf(
                    "pluginId" to pluginId,
                    "jarPath" to jar.absolutePath,
                    "jarDeleted" to jarGone.toString(),
                    "sidecarDeleted" to sidecarGone.toString(),
                ),
            )
        }
    }
}

/**
 * The desktop half of [PluginLoadRemedyResolver], wired to the real updater, store and disk.
 *
 * A thin adapter over [PluginLoadGateRecovery] so the host composable in `commonMain` can be
 * mounted without knowing any of that exists.
 */
object DesktopPluginLoadRemedyResolver : PluginLoadRemedyResolver {
    override suspend fun resolve(gate: PluginLoadGate): List<PluginLoadRemedy> =
        remediesFor(
            gate = gate,
            options =
                RemedyOptions(
                    // Each lookup is asked only for the gate it can fix: a store request whose
                    // answer nothing would read is a round trip spent on nothing. hostUpdate is a
                    // state read rather than a request, so it costs nothing - but it is gated too,
                    // because signatureRemedies ignores it and an ungated read here would make the
                    // comment above false for one of the three.
                    hostUpdate =
                        when (gate) {
                            is PluginLoadGate.NeedsNewerHost -> PluginLoadGateRecovery.hostUpdateVersion()
                            else -> null
                        },
                    apiUpdate =
                        when (gate) {
                            is PluginLoadGate.NeedsNewerApi -> PluginLoadGateRecovery.apiUpdateVersion()
                            else -> null
                        },
                    revertTo =
                        when (gate) {
                            // Never for a signature refusal: signatureRemedies would ignore it,
                            // and reading the rollback store is pointless work.
                            is PluginLoadGate.VersionFloor -> {
                                PluginLoadGateRecovery.revertVersion(gate.pluginId)
                            }

                            else -> {
                                null
                            }
                        },
                    storeVersion =
                        when (gate) {
                            is PluginLoadGate.SignatureRejected -> {
                                storeVersion(gate.pluginId)
                            }

                            else -> {
                                null
                            }
                        },
                ),
            satisfies = PluginLoadGateRecovery::satisfies,
        )

    override suspend fun apply(
        gate: PluginLoadGate,
        remedy: PluginLoadRemedy,
        manager: DynamicPluginManager,
    ): Result<String> = PluginLoadGateRecovery.apply(gate, remedy, manager)
}
