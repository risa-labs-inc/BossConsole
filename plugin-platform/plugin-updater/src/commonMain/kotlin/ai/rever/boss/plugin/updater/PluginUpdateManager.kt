package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.dependency.SemanticVersion
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Callback interface for update events.
 */
interface UpdateListener {
    /**
     * Called when updates are available.
     */
    fun onUpdatesAvailable(updates: List<UpdateInfo>) {}

    /**
     * Called when an update is being downloaded.
     */
    fun onUpdateDownloading(
        pluginId: String,
        progress: Float,
    ) {}

    /**
     * Called when an update installation starts.
     */
    fun onUpdateInstalling(pluginId: String) {}

    /**
     * Called when an update is completed.
     */
    fun onUpdateCompleted(
        pluginId: String,
        newVersion: String,
    ) {}

    /**
     * Called when an update fails.
     */
    fun onUpdateFailed(
        pluginId: String,
        error: String,
    ) {}

    /**
     * Called when a newer version was found but rejected because it is
     * incompatible with the host's IPC contract (not installed).
     */
    fun onUpdateRejectedAsIncompatible(notice: IncompatibleNotice) {}
}

/**
 * Manages plugin updates.
 *
 * Features:
 * - Check for updates from repositories
 * - Download new versions
 * - Update plugins (unload old, load new)
 * - Rollback on failure
 * - Periodic background checks
 */
class PluginUpdateManager(
    private val repositoryManager: PluginRepositoryManager,
    private val config: UpdateCheckerConfig = UpdateCheckerConfig(),
    /** Host IPC contract version, surfaced in incompatibility notices. */
    private val hostIpcVersion: String = "1.0.0",
    /**
     * Returns true if a plugin declaring [minIpcVersion] can be loaded by this
     * host. Injected by the host (which owns `IpcVersion`); defaults to "always
     * compatible" so a manager constructed without IPC awareness is a no-op
     * gate rather than blocking every update.
     */
    private val isIpcCompatible: (minIpcVersion: String) -> Boolean = { true },
    /**
     * The running host application version (e.g. "9.2.26"), compared against
     * each candidate's `minBossVersion` so an update requiring a newer host is
     * reported, never installed over a working older version (the loader would
     * reject it AFTER the jar swap, leaving the plugin broken — how Toolbox
     * 1.8.4 on BOSS 9.2.25 died). Blank ("") disables the gate.
     */
    private val hostBossVersion: String = "",
    /**
     * Returns the installed boss-plugin-api (runtime API layer) version, compared against each
     * candidate's `minApiVersion`. A lambda because the manager is constructed before the api
     * layer resolves at startup, so the value is read at check time.
     *
     * The three results mean different things and are treated differently by
     * [satisfiesMinApiVersion]:
     *
     * - `null`: the api layer has NOT resolved yet. The host passes
     *   `{ System.getProperty("boss.api.version") }` and the property is absent until
     *   `DynamicPluginManager.initializeApiLayer` publishes it.
     * - `""`: it resolved and found no api jar. `ApiClassLoader.apiVersion` is null on a first
     *   run offline, and the host publishes that as an empty property.
     * - a version: the api jar's `Implementation-Version`, falling back to its `plugin.json`.
     *
     * Defaults to `{ "" }` so a manager constructed without host awareness (dev builds, tests)
     * keeps the fail-open answer it has always had.
     */
    private val hostApiVersion: () -> String? = { "" },
) {
    private val logger = BossLogger.forComponent("PluginUpdateManager")

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var checkJob: Job? = null

    /**
     * Current update state.
     */
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Last check result.
     */
    private val _lastCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val lastCheckResult: StateFlow<UpdateCheckResult?> = _lastCheckResult.asStateFlow()

    /**
     * Available updates.
     */
    private val _availableUpdates = MutableStateFlow<List<UpdateInfo>>(emptyList())
    val availableUpdates: StateFlow<List<UpdateInfo>> = _availableUpdates.asStateFlow()

    /**
     * Newer versions that exist but the host can't load (IPC-incompatible).
     * Populated by [checkForUpdates]; surfaced by the plugin manager UI.
     */
    private val _incompatibleNotices = MutableStateFlow<List<IncompatibleNotice>>(emptyList())
    val incompatibleNotices: StateFlow<List<IncompatibleNotice>> = _incompatibleNotices.asStateFlow()

    /**
     * Update listeners.
     */
    private val listeners = mutableListOf<UpdateListener>()

    /**
     * Add an update listener.
     */
    fun addListener(listener: UpdateListener) {
        listeners.add(listener)
    }

    /**
     * Remove an update listener.
     */
    fun removeListener(listener: UpdateListener) {
        listeners.remove(listener)
    }

    /**
     * Start periodic update checks.
     */
    fun startPeriodicChecks() {
        if (config.checkIntervalMs <= 0) {
            logger.info(LogCategory.SYSTEM, "Periodic update checks disabled")
            return
        }

        checkJob?.cancel()
        checkJob =
            scope.launch {
                while (isActive) {
                    checkForUpdates(emptyMap())
                    delay(config.checkIntervalMs)
                }
            }

        logger.info(
            LogCategory.SYSTEM,
            "Started periodic update checks",
            mapOf(
                "intervalMs" to config.checkIntervalMs,
            ),
        )
    }

    /**
     * Stop periodic update checks.
     */
    fun stopPeriodicChecks() {
        checkJob?.cancel()
        checkJob = null
        logger.info(LogCategory.SYSTEM, "Stopped periodic update checks")
    }

    /**
     * Check for updates for installed plugins.
     *
     * @param installedPlugins Map of plugin ID to installed version
     * @return Update check result
     */
    suspend fun checkForUpdates(installedPlugins: Map<String, String>): UpdateCheckResult {
        _state.value = UpdateState.Checking

        logger.info(
            LogCategory.SYSTEM,
            "Checking for updates",
            mapOf(
                "pluginCount" to installedPlugins.size,
            ),
        )

        try {
            val updates = mutableListOf<UpdateInfo>()
            val failed = mutableMapOf<String, String>()
            val notices = mutableListOf<IncompatibleNotice>()

            // One read for the whole sweep. See the api branch below for why re-reading it
            // per use is wrong.
            val installedApi = hostApiVersion()

            for ((pluginId, installedVersion) in installedPlugins) {
                try {
                    val pluginResult = repositoryManager.getPlugin(pluginId)
                    val candidate = pluginResult.getOrNull()?.plugin

                    if (candidate != null && isNewerVersion(candidate.version, installedVersion)) {
                        if (!satisfiesMinBossVersion(candidate.minBossVersion)) {
                            // Newer version exists but requires a newer host app.
                            // Report it as an advisory; never auto-install.
                            val notice =
                                IncompatibleNotice(
                                    pluginId = pluginId,
                                    displayName = candidate.displayName,
                                    currentVersion = installedVersion,
                                    advertisedLatest = candidate.version,
                                    requiredBossVersion = candidate.minBossVersion,
                                    hostBossVersion = hostBossVersion,
                                )
                            notices.add(notice)
                            logger.info(
                                LogCategory.SYSTEM,
                                "Skipping plugin update requiring newer BOSS host",
                                mapOf(
                                    "pluginId" to pluginId,
                                    "advertisedLatest" to candidate.version,
                                    "requiredBossVersion" to candidate.minBossVersion,
                                    "hostBossVersion" to hostBossVersion,
                                ),
                            )
                            listeners.forEach { it.onUpdateRejectedAsIncompatible(notice) }
                        } else if (!satisfiesMinApiVersion(candidate.minApiVersion, installedApi)) {
                            // Newer version exists but the installed runtime API layer
                            // (boss-plugin-api jar) does not satisfy its floor, or could not be
                            // read at all. Report it as an advisory; never auto-install.
                            //
                            // installedApi is read once for the whole sweep: the api layer
                            // resolving is exactly the event this branch is about, so reading
                            // it again here could report a host version that would have
                            // satisfied the floor the check just failed.
                            val notice =
                                IncompatibleNotice(
                                    pluginId = pluginId,
                                    displayName = candidate.displayName,
                                    currentVersion = installedVersion,
                                    advertisedLatest = candidate.version,
                                    requiredApiVersion = candidate.minApiVersion,
                                    hostApiVersion = installedApi ?: "",
                                )
                            notices.add(notice)
                            // Two different reasons reach here and a user chasing "why is there
                            // no update" needs to tell them apart: the candidate really does
                            // want a newer api, or this host cannot say what api it has. The
                            // second is a host problem, can persist, and is a WARN.
                            if (installedApi == null || SemanticVersion.parse(installedApi) == null) {
                                logger.warn(
                                    LogCategory.SYSTEM,
                                    "Withholding plugin update: cannot read the installed API layer version",
                                    mapOf(
                                        "pluginId" to pluginId,
                                        "advertisedLatest" to candidate.version,
                                        "requiredApiVersion" to candidate.minApiVersion,
                                        "hostApiVersion" to (installedApi ?: "<unresolved>"),
                                    ),
                                )
                            } else {
                                logger.info(
                                    LogCategory.SYSTEM,
                                    "Skipping plugin update requiring newer API layer",
                                    mapOf(
                                        "pluginId" to pluginId,
                                        "advertisedLatest" to candidate.version,
                                        "requiredApiVersion" to candidate.minApiVersion,
                                        "hostApiVersion" to installedApi,
                                    ),
                                )
                            }
                            listeners.forEach { it.onUpdateRejectedAsIncompatible(notice) }
                        } else if (isIpcCompatible(candidate.minIpcVersion)) {
                            updates.add(createUpdateInfo(candidate, installedVersion))
                        } else {
                            // Newer version exists but the host can't load it.
                            // Report it as an advisory; never auto-install.
                            val notice =
                                IncompatibleNotice(
                                    pluginId = pluginId,
                                    displayName = candidate.displayName,
                                    currentVersion = installedVersion,
                                    advertisedLatest = candidate.version,
                                    requiredIpcVersion = candidate.minIpcVersion,
                                    hostIpcVersion = hostIpcVersion,
                                )
                            notices.add(notice)
                            logger.info(
                                LogCategory.SYSTEM,
                                "Skipping IPC-incompatible plugin update",
                                mapOf(
                                    "pluginId" to pluginId,
                                    "advertisedLatest" to candidate.version,
                                    "requiredIpcVersion" to candidate.minIpcVersion,
                                    "hostIpcVersion" to hostIpcVersion,
                                ),
                            )
                            listeners.forEach { it.onUpdateRejectedAsIncompatible(notice) }
                        }
                    }
                } catch (e: Exception) {
                    failed[pluginId] = e.message ?: "Unknown error"
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Failed to check plugin for updates",
                        mapOf(
                            "pluginId" to pluginId,
                            "error" to (e.message ?: "unknown"),
                        ),
                    )
                }
            }

            val result =
                UpdateCheckResult(
                    availableUpdates = updates,
                    failedChecks = failed,
                    incompatibleNotices = notices,
                )

            _lastCheckResult.value = result
            _availableUpdates.value = updates
            _incompatibleNotices.value = notices
            _state.value = UpdateState.Idle

            if (updates.isNotEmpty()) {
                logger.info(
                    LogCategory.SYSTEM,
                    "Updates available",
                    mapOf(
                        "count" to updates.size,
                        "critical" to updates.count { it.critical },
                    ),
                )
                listeners.forEach { it.onUpdatesAvailable(updates) }
            }

            return result
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to check for updates", error = e)
            _state.value = UpdateState.Idle
            return UpdateCheckResult(
                availableUpdates = emptyList(),
                failedChecks = installedPlugins.mapValues { "Check failed: ${e.message}" },
            )
        }
    }

    /**
     * Download an update.
     *
     * @param pluginId Plugin to update
     * @param targetPath Path to download the update to
     * @param onProgress Called with the download fraction (0.0 to 1.0) as bytes arrive.
     *   The listener callbacks below only ever report 0f and 1f, which is enough to
     *   know a download started but not enough to draw a bar.
     * @return Result with the downloaded file path
     */
    suspend fun downloadUpdate(
        pluginId: String,
        targetPath: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<String> {
        val update =
            _availableUpdates.value.find { it.pluginId == pluginId }
                ?: return Result.failure(Exception("No update available for plugin: $pluginId"))

        _state.value = UpdateState.Downloading(pluginId, 0f)
        listeners.forEach { it.onUpdateDownloading(pluginId, 0f) }

        return try {
            val result =
                repositoryManager.downloadPlugin(
                    pluginId = pluginId,
                    version = update.newVersion,
                    targetPath = targetPath,
                    onProgress = onProgress,
                )

            if (result.isSuccess) {
                _state.value = UpdateState.Idle
                listeners.forEach { it.onUpdateDownloading(pluginId, 1f) }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Download failed"
                _state.value = UpdateState.Failed(pluginId, error)
                listeners.forEach { it.onUpdateFailed(pluginId, error) }
            }

            result
        } catch (e: CancellationException) {
            // Ahead of the general clause, and rethrown rather than reported: the user
            // pressed Cancel. Reported as a failure it became "update failed:
            // StandaloneCoroutine was cancelled" in the Toolbox, AND it stopped the
            // cancellation propagating - so the caller's cleanup, which is what removes
            // the truncated jar, never ran.
            throw e
        } catch (e: Exception) {
            val error = e.message ?: "Download failed"
            _state.value = UpdateState.Failed(pluginId, error, e)
            listeners.forEach { it.onUpdateFailed(pluginId, error) }
            Result.failure(e)
        }
    }

    /**
     * Update a plugin.
     *
     * This coordinates the update process:
     * 1. Download new version
     * 2. Call the provided unloader to unload the old version
     * 3. Call the provided loader to load the new version
     * 4. Rollback on failure
     *
     * @param pluginId Plugin to update
     * @param downloadPath Path to download the new version
     * @param unloadPlugin Function to unload the old plugin
     * @param loadPlugin Function to load the new plugin
     * @param onProgress Called with the download fraction (0.0 to 1.0) during step 1
     * @param onInstalling Called once the download is done and the swap begins. The
     *   caller uses it to withdraw its Cancel: from here on, cancelling would leave
     *   the plugin unloaded.
     * @return Result indicating success or failure
     */
    suspend fun updatePlugin(
        pluginId: String,
        downloadPath: String,
        unloadPlugin: suspend (String) -> Result<Unit>,
        loadPlugin: suspend (String) -> Result<Unit>,
        onProgress: ((Float) -> Unit)? = null,
        onInstalling: (() -> Unit)? = null,
    ): Result<Unit> {
        val update =
            _availableUpdates.value.find { it.pluginId == pluginId }
                ?: return Result.failure(Exception("No update available for plugin: $pluginId"))

        logger.info(
            LogCategory.SYSTEM,
            "Starting plugin update",
            mapOf(
                "pluginId" to pluginId,
                "currentVersion" to update.currentVersion,
                "newVersion" to update.newVersion,
            ),
        )

        // Download new version
        val downloadResult = downloadUpdate(pluginId, downloadPath, onProgress)
        if (downloadResult.isFailure) {
            return Result.failure(downloadResult.exceptionOrNull() ?: Exception("Download failed"))
        }

        val downloadedPath = downloadResult.getOrThrow()

        // Install
        _state.value = UpdateState.Installing(pluginId)
        listeners.forEach { it.onUpdateInstalling(pluginId) }
        onInstalling?.invoke()

        // From here to the end of the load, NonCancellable. Cancellation is
        // cooperative, so a Cancel pressed between the last progress tick and this
        // point would otherwise throw out of `unloadPlugin` or `loadPlugin` and leave
        // the plugin unloaded with nothing in its place - exactly the harm the caller
        // withdraws its Cancel button to prevent. Withdrawing the button is necessary
        // and not sufficient: the button is UI state, this is the work.
        return withContext(NonCancellable) { swapPlugin(pluginId, update, downloadedPath, unloadPlugin, loadPlugin) }
    }

    /**
     * Unload the old version and load [downloadedPath], reporting either outcome.
     *
     * Split out so the whole swap can run under `NonCancellable` in one expression;
     * see the comment at the call site for why it must.
     */
    private suspend fun swapPlugin(
        pluginId: String,
        update: UpdateInfo,
        downloadedPath: String,
        unloadPlugin: suspend (String) -> Result<Unit>,
        loadPlugin: suspend (String) -> Result<Unit>,
    ): Result<Unit> {
        // Unload old version
        val unloadResult = unloadPlugin(pluginId)
        if (unloadResult.isFailure) {
            val error = unloadResult.exceptionOrNull()?.message ?: "Unload failed"
            _state.value = UpdateState.Failed(pluginId, error)
            listeners.forEach { it.onUpdateFailed(pluginId, error) }
            return unloadResult
        }

        // Load new version
        val loadResult = loadPlugin(downloadedPath)
        if (loadResult.isFailure) {
            // Rollback - try to reload the old version
            logger.warn(
                LogCategory.SYSTEM,
                "Update failed, attempting rollback",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )

            // Note: Actual rollback would require keeping track of the old JAR path
            // For now, we just report the failure

            val error = loadResult.exceptionOrNull()?.message ?: "Install failed"
            _state.value = UpdateState.Failed(pluginId, error)
            listeners.forEach { it.onUpdateFailed(pluginId, error) }
            return loadResult
        }

        // Success
        _state.value = UpdateState.Completed(pluginId, update.newVersion)
        listeners.forEach { it.onUpdateCompleted(pluginId, update.newVersion) }

        // Remove from available updates
        _availableUpdates.value = _availableUpdates.value.filter { it.pluginId != pluginId }

        logger.info(
            LogCategory.SYSTEM,
            "Plugin updated successfully",
            mapOf(
                "pluginId" to pluginId,
                "newVersion" to update.newVersion,
            ),
        )

        return Result.success(Unit)
    }

    /**
     * Update all plugins with available updates.
     *
     * @param downloadDir Directory to download updates to
     * @param unloadPlugin Function to unload plugins
     * @param loadPlugin Function to load plugins
     * @return Map of plugin ID to update result
     */
    suspend fun updateAll(
        downloadDir: String,
        unloadPlugin: suspend (String) -> Result<Unit>,
        loadPlugin: suspend (String) -> Result<Unit>,
    ): Map<String, Result<Unit>> {
        val results = mutableMapOf<String, Result<Unit>>()

        for (update in _availableUpdates.value) {
            val downloadPath = "$downloadDir/${update.pluginId}-${update.newVersion}.jar"
            results[update.pluginId] =
                updatePlugin(
                    pluginId = update.pluginId,
                    downloadPath = downloadPath,
                    unloadPlugin = unloadPlugin,
                    loadPlugin = loadPlugin,
                )
        }

        return results
    }

    /**
     * Dismiss an update (don't notify again for this version).
     */
    fun dismissUpdate(pluginId: String) {
        _availableUpdates.value = _availableUpdates.value.filter { it.pluginId != pluginId }
    }

    /**
     * Clear all available updates.
     */
    fun clearUpdates() {
        _availableUpdates.value = emptyList()
    }

    /**
     * Dispose the update manager.
     */
    fun dispose() {
        stopPeriodicChecks()
        scope.cancel()
        listeners.clear()
    }

    /**
     * Check if version1 is newer than version2.
     *
     * Fails CLOSED when either side is unparseable: an unreadable version
     * offers no update. This is the one comparator on the live update path
     * (Toolbox -> PluginUpdateBridge -> this class).
     *
     * The repository-side counterpart, `PluginRepositoryManager.isNewerVersion`
     * in plugin-repository, fails OPEN on an unparseable *installed* version:
     * a plugin whose recorded version is already broken should not be stranded
     * without updates. The two divergences are deliberate on each page; settling
     * which behaviour is the right one is a change of its own, and until then
     * this is the answer the user actually gets.
     */
    private fun isNewerVersion(
        version1: String,
        version2: String,
    ): Boolean {
        val v1 = SemanticVersion.parse(version1) ?: return false
        val v2 = SemanticVersion.parse(version2) ?: return false
        return v1 > v2
    }

    /**
     * True when this host satisfies a candidate's `minBossVersion`.
     *
     * Fails OPEN on missing/unparseable versions: a blank [hostBossVersion]
     * (manager constructed without host awareness, dev builds) or a candidate
     * without the field must not block every update — the loader's own
     * minBossVersion check remains the backstop for anything let through.
     */
    private fun satisfiesMinBossVersion(minBossVersion: String): Boolean =
        satisfiesFloor(required = minBossVersion, installed = hostBossVersion)

    /**
     * True when the installed runtime API layer satisfies a candidate's `minApiVersion`.
     *
     * Fails CLOSED when a floor IS declared but the installed API version cannot be
     * established, which is the opposite of [satisfiesVersionFloor] and deliberate.
     *
     * The shared helper answers true for a blank or unparseable `installed`, and that is right
     * for its other callers: the home grid would rather show a tile, and the retirement check
     * has its own fail-closed wrapper. It is wrong here. `hostApiVersion` is
     * `System.getProperty("boss.api.version")`, so an API layer that has not resolved yet
     * reads as blank, the floor is skipped, and the update is installed. The loader then
     * rejects it on the same floor, but only AFTER the jar has been swapped, which is how
     * Toolbox 1.8.4 on BOSS 9.2.25 left a broken plugin behind rather than no update.
     *
     * Declining to offer an update is recoverable: the next check re-reads the property, and
     * by then the API layer has resolved. Swapping a jar the host cannot load is not.
     *
     * An unparseable `minApiVersion` still fails open, via the shared helper. That value comes
     * from the store rather than from us, and one malformed row should withhold nothing.
     */
    // Guard clauses, for the same reason satisfiesVersionFloor carries this suppression: each
    // case below is a separate rule with a different reason, and folding them into one
    // expression would hide which of them withheld an update.
    @Suppress("ReturnCount")
    private fun satisfiesMinApiVersion(
        minApiVersion: String,
        installed: String?,
    ): Boolean {
        // `required` first, and via parse rather than isBlank: parse rejects blank, so a
        // candidate with no floor and a candidate with a MALFORMED floor both fail open here
        // unconditionally. Asking about `installed` first made the malformed case depend on
        // the host version, which contradicted this function's own documentation.
        SemanticVersion.parse(minApiVersion) ?: return true

        // Not resolved YET: fail closed. This is the case the gate exists for. Declining is
        // recoverable, because the next check re-reads the property and by then the api layer
        // has resolved; swapping a jar whose floor we could not check is not.
        if (installed == null) return false

        // Resolved, but no api jar was found. Unlike the case above this can persist for a
        // whole session, so failing closed here would withhold every floor-declaring update
        // indefinitely on such a host. Keep the answer this code has always given, which is
        // also what DynamicPluginLoader does: it skips minApiVersion validation outright when
        // currentApiVersion is null rather than rejecting.
        if (installed.isBlank()) return true

        // Resolved to something we cannot read: fail closed, and the caller logs this reason
        // separately, because it is a property of THIS host rather than of the candidate.
        if (SemanticVersion.parse(installed) == null) return false

        return satisfiesFloor(required = minApiVersion, installed = installed)
    }

    /**
     * True when [installed] satisfies the [required] floor.
     *
     * Delegates to the top-level [satisfiesVersionFloor], which the home screen's tool grid
     * also calls to decide whether a store row is worth offering as an install. See its KDoc
     * for the fail-open and prerelease rules.
     */
    private fun satisfiesFloor(
        required: String,
        installed: String,
    ): Boolean = satisfiesVersionFloor(required = required, installed = installed)

    /**
     * Create an UpdateInfo from a PluginInfo.
     */
    private fun createUpdateInfo(
        plugin: PluginInfo,
        currentVersion: String,
    ): UpdateInfo =
        UpdateInfo(
            pluginId = plugin.pluginId,
            displayName = plugin.displayName,
            currentVersion = currentVersion,
            newVersion = plugin.version,
            changelog = plugin.changelog,
            size = plugin.size,
            critical = false, // Would need to be specified in plugin metadata
            releaseDate = plugin.publishedAt,
            downloadUrl = plugin.downloadUrl,
            requiresRestart = false, // Dynamic plugins don't require restart
        )
}
