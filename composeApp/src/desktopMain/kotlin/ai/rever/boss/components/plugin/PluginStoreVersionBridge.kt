package ai.rever.boss.components.plugin

import ai.rever.boss.downloads.DownloadCenter
import ai.rever.boss.plugin.KeyedDetachedJobs
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginUnloadIntent
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import ai.rever.boss.plugin.repository.shortFailureReason
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext

/**
 * Desktop implementation of the store-version bridge.
 *
 * Goes to the REMOTE repository directly, never through [PluginStoreSetup.repositoryManager]. The
 * manager is local-first and its [ai.rever.boss.plugin.repository.LocalPluginRepository] synthesises
 * a row from the installed jar's own manifest, so asking it about an installed plugin answers with
 * the very local build we are trying to replace - and `downloadPlugin` resolves its source the same
 * way, so it would "download" the local file onto itself.
 *
 * The work itself is [StoreVersionInstaller]; this object is the wiring plus the detachment.
 */
actual object PluginStoreVersionBridge {
    private val logger = BossLogger.forComponent("PluginStoreVersionBridge")

    /**
     * Owner of detached swaps - deliberately never cancelled, exactly like the dependency
     * installer's. The prompt runs on a window's `rememberCoroutineScope`, so closing that window
     * mid-swap would otherwise cancel between the unload and the load and leave the plugin gone with
     * nothing in its place. Coalescing per plugin id also keeps two windows from racing one id.
     */
    private val SWAP_SCOPE = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val DETACHED_SWAPS = KeyedDetachedJobs<String, Result<String>>(SWAP_SCOPE)

    private val installer by lazy { StoreVersionInstaller(pluginDir = { PluginStoreSetup.getPluginDir() }) }

    actual suspend fun lookup(pluginId: String): StoreVersionLookup {
        val store =
            PluginStoreSetup.remoteRepository
                ?: return StoreVersionLookup.Unavailable(
                    "The plugin store is not available. Check your connection and try again.",
                )
        // Both failure shapes, flattened. This used to be `runCatching { … }` around the call with
        // `result.getOrNull()?.getOrNull()` inside, which looked like it distinguished a broken lookup
        // from an unpublished plugin and did not: `getPlugin` returns `Result.failure` instead of
        // throwing, so the inner `getOrNull()` swallowed the error, the outer `isFailure` was always
        // false, and every failure fell through to NotPublished. A single undecodable dependency
        // entry in a store row was reported to the user as "not published". The flatten covers both
        // arrival shapes because the `PluginRepository` interface permits either: the bundled
        // `RemotePluginRepository` rethrows a caller's cancellation while returning
        // `Result.failure` for a genuine store error, and a third-party implementation may fold
        // the cancellation into the returned `Result.failure` too.
        val lookup = runCatching { store.getPlugin(pluginId) }.getOrElse { Result.failure(it) }
        // The repository logs the detail; this only needs a line short enough to render.
        lookup.exceptionOrNull()?.let { failure ->
            return StoreVersionLookup.Unavailable(
                "Could not reach the plugin store: ${shortFailureReason(failure)}",
            )
        }
        val info =
            lookup.getOrNull()
                // A successful lookup that found nothing is the ordinary case for a plugin that was
                // built locally and never published, so it is reported as absence, not error.
                ?: return StoreVersionLookup.NotPublished
        val version = info.version.takeIf { it.isNotBlank() } ?: return StoreVersionLookup.NotPublished
        return StoreVersionLookup.Available(
            displayName = info.displayName,
            version = version,
            sourceUrl = info.downloadUrl.ifBlank { null },
        )
    }

    actual suspend fun installStoreVersion(
        pluginId: String,
        version: String,
        sourceUrl: String?,
        manager: DynamicPluginManager,
    ): Result<String> {
        // Outside the detached job on purpose: the question belongs to the window the user is
        // looking at, and detaching it would let the swap start before anyone had answered.
        // Same reasoning as PluginUpdateBridge - this path forces the unload too, so it never
        // met the veto and never restarted the dependents.
        //
        // Skipped entirely for a not-hot-reloadable plugin (BossConsole#71): StoreVersionInstaller
        // defers that swap to a restart rather than unloading anything, so nothing depending on
        // it is touched either, and asking would be a confusing prompt about an unload that is
        // not going to happen.
        val displayName = manager.getPluginInfo(pluginId)?.manifest?.displayName ?: pluginId
        if (!HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId) &&
            !confirmDependentRestart(pluginId, displayName, PluginUnloadIntent.UPDATE, manager)
        ) {
            return Result.failure(DependentRestartDeclinedException(pluginId))
        }
        return DETACHED_SWAPS.run(
            key = pluginId,
            onDetachedFailure = { error ->
                // The window closed while this ran, so nothing is left to show the failure to.
                logger.error(LogCategory.SYSTEM, "Detached store-version install failed", error = error)
            },
        ) {
            val store =
                PluginStoreSetup.remoteRepository
                    ?: return@run Result.failure(
                        IllegalStateException(
                            "The plugin store is not available. Check your connection and try again.",
                        ),
                    )
            // The center's row is opened inside the detached job, so the Cancel it
            // offers cancels THAT job - the one actually downloading. Cancelling the
            // window's coroutine would only abandon the wait: the swap is detached
            // precisely so closing a window cannot stop it mid-flight.
            val job = currentCoroutineContext()[Job]
            val ownsTransfer =
                DownloadCenter.begin(
                    id = pluginId,
                    title = displayName,
                    kind = TransferKind.PLUGIN_INSTALL,
                    detail = "Store version v$version",
                    onCancel = { job?.cancel() },
                )
            try {
                installer.install(
                    store = store,
                    request =
                        StoreVersionRequest(
                            pluginId = pluginId,
                            version = version,
                            sourceUrl = sourceUrl,
                            runningJarPath = manager.getPluginInfo(pluginId)?.jarPath,
                            hasLiveInstance = manager.getPluginInfo(pluginId)?.state == PluginState.LOADED,
                        ),
                    unload = { id -> manager.uninstallPlugin(id, force = true).map { } },
                    load = { path ->
                        manager.installPlugin(path, enabled = true).map { it.state == PluginState.LOADED }
                    },
                    onProgress = { DownloadCenter.progress(pluginId, it) },
                    onInstalling = { DownloadCenter.phase(pluginId, TransferPhase.INSTALLING) },
                )
            } finally {
                if (ownsTransfer) DownloadCenter.end(pluginId)
            }
        }
    }
}
