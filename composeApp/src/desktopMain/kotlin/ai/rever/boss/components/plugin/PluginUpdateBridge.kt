package ai.rever.boss.components.plugin

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.downloads.DownloadCenter
import ai.rever.boss.plugin.MissingDependencyReporter
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginUnloadIntent
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.readDeferredPluginManifest
import ai.rever.boss.plugin.updater.UpdateInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.File

/**
 * Desktop implementation of the plugin update bridge. Delegates to the PluginUpdateManager created
 * in [PluginStoreSetup] (which is gated by host IPC compatibility, so `availableUpdates` only ever
 * contains versions the running BOSS can load) and to [DynamicPluginManager] for unload/load.
 */
@Suppress("TooManyFunctions")
actual object PluginUpdateBridge {
    private val logger = BossLogger.forComponent("PluginUpdateBridge")

    // DynamicPluginManager instances are window-scoped, but every window updates the
    // same plugin directory. Admission therefore belongs to this process-wide bridge.
    private val updates = ExclusivePluginUpdates()

    actual suspend fun refreshAll(installed: List<InstalledPluginRef>) {
        if (installed.isEmpty()) return
        val mgr = PluginStoreSetup.updateManager ?: return
        val byId = installed.associateBy { it.pluginId }
        // Use the check's own result rather than reading mgr.availableUpdates afterwards:
        // that shared flow is replaced wholesale by every checkForUpdates() call, so a
        // concurrent single-plugin checkOne() could shrink it to one entry between our
        // check and the read.
        val result =
            try {
                mgr.checkForUpdates(installed.associate { it.pluginId to it.version })
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Plugin update check failed: ${e.message}")
                return
            }
        PluginUpdateRegistry.putAll(
            result.availableUpdates.map { u ->
                AvailablePluginUpdate(
                    pluginId = u.pluginId,
                    displayName = byId[u.pluginId]?.displayName ?: u.displayName,
                    currentVersion = u.currentVersion,
                    newVersion = u.newVersion,
                )
            },
        )
    }

    actual suspend fun checkOne(ref: InstalledPluginRef): UpdateCheckOutcome {
        val mgr =
            PluginStoreSetup.updateManager
                ?: return UpdateCheckOutcome.Error("Plugin store not initialized")
        return try {
            // Read this check's own result (not the shared mgr flows, which a concurrent
            // refreshAll/checkOne may have overwritten since).
            val result = mgr.checkForUpdates(mapOf(ref.pluginId to ref.version))
            val failure = result.failedChecks[ref.pluginId]
            val available = result.availableUpdates.firstOrNull { it.pluginId == ref.pluginId }
            when {
                failure != null -> {
                    // Don't clear a previously-known update on a transient check failure.
                    UpdateCheckOutcome.Error(failure)
                }

                available != null -> {
                    PluginUpdateRegistry.put(
                        AvailablePluginUpdate(ref.pluginId, ref.displayName, available.currentVersion, available.newVersion),
                    )
                    UpdateCheckOutcome.Available(ref.displayName, available.currentVersion, available.newVersion)
                }

                else -> {
                    PluginUpdateRegistry.clear(ref.pluginId)
                    val incompatible = result.incompatibleNotices.firstOrNull { it.pluginId == ref.pluginId }
                    if (incompatible != null) {
                        UpdateCheckOutcome.Incompatible(incompatible.advertisedLatest)
                    } else {
                        UpdateCheckOutcome.UpToDate
                    }
                }
            }
        } catch (e: Exception) {
            UpdateCheckOutcome.Error(e.message ?: "Unknown error")
        }
    }

    actual suspend fun performUpdate(
        pluginId: String,
        manager: DynamicPluginManager,
    ): Result<String> = updates.run(pluginId) { performAdmittedUpdate(pluginId, manager) }

    // Guard returns preserve the distinct preflight failures before any destructive update stage.
    @Suppress("ReturnCount")
    private suspend fun performAdmittedUpdate(
        pluginId: String,
        manager: DynamicPluginManager,
    ): Result<String> {
        val mgr =
            PluginStoreSetup.updateManager
                ?: return Result.failure(Exception("Plugin store not initialized"))
        val update =
            mgr.availableUpdates.value.firstOrNull { it.pluginId == pluginId }
                ?: return Result.failure(Exception("No update available"))

        // This plugin owns a native OS peer bound to the classloader that created it - force-
        // unloading that loader to swap in the update leaves every open (and every future)
        // surface unable to attach a view (BossConsole#71). Computed up front because it also
        // gates the dependent-restart question below: a deferred update never closes this
        // plugin's classloader, so nothing depending on it is affected either, and asking would
        // be a confusing prompt about an unload that is not going to happen.
        val deferHotReload =
            manager.getPluginInfo(pluginId)?.state == PluginState.LOADED &&
                HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId)

        // Ask before downloading anything. This path unloads with `force = true`, so it never
        // met the dependents veto - and never restarted the dependents either, which left them
        // holding a handle into the classloader this update is about to close. Asked here rather
        // than inside `updatePlugin`'s unload lambda so a decline costs no download, and so the
        // question arrives before the "Updating…" status message stops making sense.
        if (!deferHotReload &&
            !confirmDependentRestart(pluginId, update.displayName, PluginUnloadIntent.UPDATE, manager)
        ) {
            return Result.failure(DependentRestartDeclinedException(pluginId))
        }

        val pluginDir = PluginStoreSetup.getPluginDir()
        val targetFile =
            downloadTargetIn(pluginDir, pluginId, update.newVersion)
                ?: return Result.failure(Exception("Refusing to download update outside the plugin directory"))
        val targetPath = targetFile.absolutePath

        // Keep the jar this update is about to make unreachable, BEFORE anything downloads.
        //
        // This path does not overwrite - it writes a new version-named file and then calls
        // `PluginJarReconciler.reconcilePluginDir`, which deletes every other jar for this plugin
        // id. So the version that currently works stops existing the moment the update succeeds,
        // and if the new one then fails its version floor at load there is nothing on disk to go
        // back to. That is precisely what happened to fluck-browser 1.2.22 on a 9.4.22 host: the
        // browser tab was gone and the recovery was to find the previous release by hand.
        //
        // Taken here rather than inside `mgr.updatePlugin` so it happens once, before the unload
        // closes the classloader, and so a failure to keep the copy cannot fail the update.
        val runningJarPath = manager.getPluginInfo(pluginId)?.jarPath
        runningJarPath?.let { installedJar ->
            PluginRollbackStore.snapshot(pluginDir, pluginId, installedJar)
        }

        val ownsTransfer = beginTransfer(pluginId, update, currentCoroutineContext()[Job])
        // Set from `onInstalling`; see discardPartialDownload for what it gates.
        var swapStarted = false
        val result =
            try {
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = targetPath,
                    unloadPlugin = { id -> vettedUnload(id, targetPath, deferHotReload, manager) },
                    loadPlugin = { path ->
                        activateUpdate(pluginId, path, manager, deferHotReload)
                    },
                    onProgress = { DownloadCenter.progress(pluginId, it) },
                    onInstalling = {
                        swapStarted = true
                        DownloadCenter.phase(pluginId, TransferPhase.INSTALLING)
                    },
                )
            } catch (e: CancellationException) {
                discardIfUnswapped(swapStarted, targetFile)
                throw e
            } finally {
                if (ownsTransfer) DownloadCenter.end(pluginId)
            }
        return if (result.isSuccess) {
            PluginUpdateRegistry.clear(pluginId)
            reconcileUpdatedPlugin(pluginDir, pluginId, deferHotReload)
            Result.success(update.newVersion)
        } else {
            discardIfUnswapped(swapStarted, targetFile)
            Result.failure(result.exceptionOrNull() ?: Exception("Update failed"))
        }
    }

    internal fun reconcileUpdatedPlugin(
        pluginDir: File,
        pluginId: String,
        deferred: Boolean,
    ) {
        if (deferred) return
        runCatching {
            ai.rever.boss.plugin.PluginJarReconciler
                .reconcilePluginDir(pluginDir, pluginIds = setOf(pluginId))
        }.onFailure { logger.warn(LogCategory.SYSTEM, "Post-update plugin reconcile failed", error = it) }
    }

    /**
     * The unload step of an admitted update, gated on the identity vet running
     * BEFORE the force-unload (#927's prescribed placement): a refusal throws,
     * which aborts the swap with the running plugin still loaded for the
     * session, while the failure path discards the downloaded jar. The
     * activateUpdate vet stays as belt-and-braces.
     */
    private suspend fun vettedUnload(
        id: String,
        targetPath: String,
        deferHotReload: Boolean,
        manager: DynamicPluginManager,
    ): Result<Unit> {
        vetUpdateJarIdentity(id, targetPath)?.let { refusal ->
            throw IllegalStateException(
                "Refusing the update swap: the downloaded jar is not $id ($refusal); the running plugin stays loaded.",
            )
        }
        return if (deferHotReload) {
            Result.success(Unit)
        } else {
            manager.uninstallPlugin(id, force = true).map { }
        }
    }

    /**
     * Vet a downloaded update jar's declared identity before it is loaded - the
     * same two conditions the store installers enforce
     * (StoreVersionInstaller/StoreMissingDependencyInstaller): nothing binds a
     * store row to the plugin id its jar declares, and installPlugin acts on
     * the incoming manifest, so a jar declaring ai.rever.boss.plugin.api would
     * trigger a process-wide API hot swap from the Update button. Returns null
     * when the jar may activate, or the refusal reason when it may not.
     */
    internal fun vetUpdateJarIdentity(
        pluginId: String,
        path: String,
    ): String? {
        val declaredId =
            try {
                PluginManifestReader.readFromJar(path).pluginId
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Update jar manifest unreadable - refusing to activate",
                    mapOf("pluginId" to pluginId, "path" to path),
                    error = e,
                )
                return "unreadable manifest"
            }
        return when {
            declaredId != pluginId || declaredId in PluginDependencyResolution.NOT_USER_INSTALLABLE -> {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Refusing an update jar that declares a different plugin",
                    mapOf("expected" to pluginId, "declared" to declaredId),
                )
                "declared id $declaredId"
            }

            else -> {
                null
            }
        }
    }

    private suspend fun activateUpdate(
        pluginId: String,
        path: String,
        manager: DynamicPluginManager,
        deferHotReload: Boolean,
    ): Result<Unit> {
        // Vet before loading (see vetUpdateJarIdentity). The unload lambda has
        // already run by the time this is called, so a refusal here must fail
        // the update loudly rather than register whatever the jar declares.
        vetUpdateJarIdentity(pluginId, path)?.let { refusal ->
            // The refused jar must not survive at its version-named, scannable
            // path: the next launch's directory scan would register whatever id
            // it declares (null-signature is warn-and-allowed during the #102
            // rollout), so the identity attack would survive the session.
            discardPartialDownload(File(path))
            return Result.failure(
                Exception(
                    "The downloaded update did not declare itself as $pluginId ($refusal). Refusing to activate it.",
                ),
            )
        }
        return if (deferHotReload) {
            runCatching { stageForRestart(pluginId, path, manager) }
                .onFailure { discardIfUnswapped(false, File(path)) }
        } else {
            manager.installPlugin(path).map { info ->
                if (info.state == PluginState.LOADED) {
                    MissingDependencyReporter.forManager(manager).report(info.manifest)
                }
            }
        }
    }

    /**
     * Records [jarPath] as [pluginId]'s installed jar without touching the running instance, and
     * tells the user a restart is needed to actually pick it up (BossConsole#71).
     *
     * Validate the manifest and record its actual version because this bypasses installPlugin.
     */
    private fun stageForRestart(
        pluginId: String,
        jarPath: String,
        manager: DynamicPluginManager,
    ) {
        val manifest = readDeferredPluginManifest(pluginId, jarPath)
        val existing = manager.getPluginInfo(pluginId)
        PluginPersistence.addInstalledPlugin(
            pluginId = pluginId,
            jarPath = jarPath,
            enabled = existing?.enabled ?: true,
            sourceUrl = PluginPersistence.getSourceUrl(pluginId),
            installedVersion = manifest.version,
        )
        logger.info(
            LogCategory.SYSTEM,
            "Deferred a plugin update to the next restart - this plugin owns a native surface",
            mapOf("pluginId" to pluginId, "jarPath" to jarPath),
        )
        val displayName = existing?.manifest?.displayName ?: pluginId
        StatusMessageManager.showMessage("$displayName was updated - restart BOSS to apply it", durationMs = 5000)
    }

    /**
     * Open this update's row in the bottom bar, cancellable while it is still bytes.
     *
     * Cancel is [job]'s: `performUpdate` runs inside whatever coroutine pressed the
     * button, so cancelling that is what abandons the download. It stops being
     * offered on its own once the swap starts, because the center withdraws Cancel
     * for [ai.rever.boss.plugin.api.TransferPhase.INSTALLING].
     *
     * @return whether this call created the row, and so must end it.
     */
    private fun beginTransfer(
        pluginId: String,
        update: UpdateInfo,
        job: Job?,
    ): Boolean =
        DownloadCenter.begin(
            id = pluginId,
            title = update.displayName,
            kind = TransferKind.PLUGIN_UPDATE,
            detail = "v${update.currentVersion} \u2192 v${update.newVersion}",
            onCancel = { job?.cancel() },
        )

    /**
     * Where this update's jar goes, or null if that would leave [pluginDir].
     *
     * `newVersion` comes from the remote store manifest, and `SemanticVersion.parse`
     * does NOT reject path separators in prerelease or build metadata - so the name is
     * sanitised and the resolved path is checked, rather than trusted.
     */
    private fun downloadTargetIn(
        pluginDir: File,
        pluginId: String,
        newVersion: String,
    ): File? {
        val safeName = "$pluginId-$newVersion".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(pluginDir, "$safeName.jar")
        return target.takeIf { it.canonicalPath.startsWith(pluginDir.canonicalPath + File.separator) }
    }

    /**
     * Discard the download unless the swap has begun.
     *
     * Both non-success paths need this, not only the cancellation: this is the one
     * host update path that streams onto a scannable `<pluginId>-<version>.jar`
     * rather than a `.part` sibling, and `reconcilePluginDir` runs only on success -
     * so a mid-stream network failure leaves a truncated jar for the next launch to
     * load with no cancellation anywhere in sight. The two store installers discard
     * in both their failure branch and a catch; this one now matches.
     *
     * And not once the swap has begun: the jar may already be loaded and recorded,
     * and deleting it then leaves `installed.json` pointing at nothing.
     */
    private fun discardIfUnswapped(
        swapStarted: Boolean,
        jar: File,
    ) {
        if (!swapStarted) discardPartialDownload(jar)
    }

    /**
     * Remove a jar a cancelled download left behind, and its signature sidecar.
     *
     * This path streams straight onto a version-named jar in the plugin directory
     * (not a `.part` sibling, as the two store installers do), so a cancelled or
     * failed download leaves a truncated file at a name the next directory scan would
     * try to load. Nothing else deletes it: the update never reached the reconciler.
     *
     * Only called while `swapStarted` is false. Past `onInstalling` the jar may
     * already be loaded and recorded, and deleting it then leaves `installed.json`
     * pointing at nothing for the next launch to fail on.
     *
     * Both, and in that order: `PluginSignatureSidecar` is written next to the path
     * the download was given, and a sidecar that outlives its jar meets the next
     * download's fresh bytes and hard-fails the load - which is worse than being
     * unsigned. Best-effort by design; a file that cannot be deleted here is
     * reported, not raised, because the cancellation is what the caller is waiting on.
     */
    private fun discardPartialDownload(jar: File) {
        // Absence is the postcondition, not a delete that returned true: `delete()`
        // returns false rather than throwing when a Windows lock holds the file, so
        // runCatching alone never reported the case this warning exists for - and an
        // already-absent file returns false too, which is success here.
        val gone = runCatching { !jar.exists() || jar.delete() }.getOrDefault(false)
        if (!gone) {
            logger.warn(
                LogCategory.SYSTEM,
                "A cancelled update download could not be removed; the next launch would try to load it",
                mapOf("path" to jar.absolutePath),
            )
        }
        runCatching { PluginSignatureSidecar.delete(jar.absolutePath) }
    }
}
