package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.repository.remote.PluginStoreEvent
import ai.rever.boss.plugin.updater.PluginUpdateManager
import ai.rever.boss.plugin.updater.UpdateInfo
import ai.rever.boss.updater.UpdateSettings
import ai.rever.boss.updater.UpdateSettingsManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Process-owned: replacing Toolbox or closing its window must not cancel an update. */
internal object AutomaticPluginUpdater {
    private val logger = BossLogger.forComponent("AutomaticPluginUpdater")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean()
    private val refreshNeeded = AtomicBoolean(true)
    private val nextRefreshAt = AtomicLong()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pending = emptyList<UpdateInfo>()
    private val _status = MutableStateFlow("No automatic plugin updates yet")
    val status = _status.asStateFlow()
    private val retryAfter = mutableMapOf<String, Long>()

    @Suppress("TooGenericExceptionCaught") // Isolate third-party/network failures at the process worker boundary.
    fun start() {
        UpdateSettingsManager.ensureLoaded()
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            UpdateSettings.automaticPluginUpdates.collect { enabled ->
                if (enabled) requestRefresh()
            }
        }
        scope.launch {
            UpdateSettings.pluginAutoUpdateOptOuts.collect { requestRefresh() }
        }
        scope.launch {
            PluginStoreSetup.realtimeService?.events?.collect { event ->
                if (event !is PluginStoreEvent.ConnectionStateChanged || event.connected) requestRefresh()
            }
        }
        scope.launch {
            var ticks = 0
            while (true) {
                delay(30_000)
                // Realtime is primary; periodic checks cover disconnected/offline sessions.
                if (++ticks % 720 == 0) refreshNeeded.set(true)
                wake.trySend(Unit)
            }
        }
        scope.launch {
            while (wake.receiveCatching().isSuccess) {
                if (!UpdateSettings.autoPluginUpdatesEnabled) continue
                try {
                    processUpdates()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Automatic plugin update failed", error = e)
                    _status.value = "Plugin update check failed. Will retry."
                    deferRefresh()
                }
            }
        }
    }

    private fun requestRefresh() {
        nextRefreshAt.set(0)
        refreshNeeded.set(true)
        wake.trySend(Unit)
    }

    private suspend fun processUpdates() {
        val managers = DynamicPluginManager.activeManagers()
        if (managers.isEmpty()) return
        val updater = PluginStoreSetup.updateManager ?: return
        if (System.currentTimeMillis() >= nextRefreshAt.get() && refreshNeeded.getAndSet(false)) {
            refreshUpdates(managers, updater)
        }
        val remaining = mutableListOf<UpdateInfo>()
        for (update in pending) {
            if (applyPendingUpdate(update, managers)) remaining.add(update)
        }
        pending = remaining
        if (remaining.isNotEmpty() && remaining.none { (retryAfter[it.pluginId] ?: 0L) > System.currentTimeMillis() }) {
            _status.value = "Waiting for views to close for ${remaining.size} plugin update(s)"
        }
    }

    private fun deferRefresh() {
        nextRefreshAt.set(System.currentTimeMillis() + 5 * 60_000)
        refreshNeeded.set(true)
    }

    private suspend fun refreshUpdates(
        managers: List<DynamicPluginManager>,
        updater: PluginUpdateManager,
    ) {
        val installed = managers.flatMap { it.getInstalledPlugins() }.distinctBy { it.manifest.pluginId }
        val persisted = PluginPersistence.getInstalledPlugins().associateBy { it.pluginId }
        val versions =
            installed
                .filterNot {
                    it.manifest.pluginId in PluginDependencyResolution.NOT_USER_INSTALLABLE
                }.associate {
                    val id = it.manifest.pluginId
                    id to (persisted[id]?.installedVersion ?: it.manifest.version)
                }
        if (versions.isEmpty()) {
            deferRefresh()
            return
        }
        val result = updater.checkForUpdates(versions)
        pending = result.availableUpdates
        if (result.failedChecks.isNotEmpty()) {
            _status.value = "Some plugin update checks failed. Will retry."
            deferRefresh()
        }
    }

    @Suppress("ReturnCount") // Distinct eligibility guards preserve pending work without touching plugins.
    private suspend fun applyPendingUpdate(update: UpdateInfo, managers: List<DynamicPluginManager>): Boolean {
        if (!UpdateSettings.isPluginAutomaticUpdateEnabled(update.pluginId)) {
            return false
        }
        val owner = managers.firstOrNull { it.getPluginInfo(update.pluginId) != null } ?: return false
        if (automaticPluginUpdatePlan(update.pluginId, owner) == AutomaticPluginUpdatePlan.WAIT ||
            System.currentTimeMillis() < (retryAfter[update.pluginId] ?: 0L)
        ) {
            return true
        }
        val persistedVersion =
            PluginPersistence
                .getInstalledPlugins()
                .firstOrNull { it.pluginId == update.pluginId }
                ?.installedVersion
        if (persistedVersion == update.newVersion ||
            owner.getPluginInfo(update.pluginId)?.manifest?.version != update.currentVersion
        ) {
            return false
        }
        _status.value = "Updating ${update.displayName}..."
        val result = PluginUpdateBridge.performAutomaticUpdate(update, owner)
        if (result.isFailure) {
            retryAfter[update.pluginId] = System.currentTimeMillis() + 5 * 60_000
            val errorMessage = result.exceptionOrNull()?.message ?: "Update failed"
            _status.value = "${update.displayName}: $errorMessage. Will retry."
            logger.warn(
                LogCategory.SYSTEM,
                "Could not automatically update plugin",
                mapOf("pluginId" to update.pluginId),
                error = result.exceptionOrNull(),
            )
        } else {
            retryAfter.remove(update.pluginId)
            _status.value =
                if (owner.getPluginInfo(update.pluginId)?.manifest?.version == update.newVersion) {
                    "${update.displayName} updated to v${update.newVersion}"
                } else {
                    "${update.displayName} v${update.newVersion} will apply on your next manual start"
                }
        }
        return result.isFailure
    }
}
