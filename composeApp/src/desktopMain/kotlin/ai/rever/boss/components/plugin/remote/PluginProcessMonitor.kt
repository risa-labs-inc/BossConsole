package ai.rever.boss.components.plugin.remote

import ai.rever.boss.components.plugin.OutOfProcessPluginSpawnerImpl
import ai.rever.boss.components.plugin.PluginStateBridge
import ai.rever.boss.plugin.api.PluginManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Health information for an out-of-process plugin.
 */
data class PluginHealthInfo(
    val pluginId: String,
    val displayName: String,
    val processState: PluginProcessState,
    val pid: Long? = null,
    val restartCount: Int = 0,
    val maxRestarts: Int = 3,
    val lastError: String? = null,
    val uptimeMs: Long = 0,
    val connected: Boolean = false,
)

/**
 * Monitors health of out-of-process plugin child processes.
 *
 * Periodically checks if plugin processes are alive, detects crashes,
 * and manages the crash-restart-fallback lifecycle.
 *
 * Integrates with:
 * - [OutOfProcessPluginSpawnerImpl] for process state and restart
 * - [PluginStateBridge] for connection state
 * - [PluginCrashFallbackUI] for crash visualization
 */
class PluginProcessMonitor(
    private val spawner: OutOfProcessPluginSpawnerImpl,
    private val checkIntervalMs: Long = 5_000,
) {
    private val logger = LoggerFactory.getLogger(PluginProcessMonitor::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Health state for all monitored plugins. */
    private val _healthStates = MutableStateFlow<Map<String, PluginHealthInfo>>(emptyMap())
    val healthStates: StateFlow<Map<String, PluginHealthInfo>> = _healthStates.asStateFlow()

    /** Plugins that have been switched to in-process fallback. Thread-safe. */
    private val inProcessFallbacks =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** Stored manifests and jar paths for re-spawning after crash. */
    private val pluginSpawnInfo = java.util.concurrent.ConcurrentHashMap<String, Pair<PluginManifest, String>>()

    /**
     * Start monitoring a plugin process.
     */
    fun monitor(
        pluginId: String,
        displayName: String,
        maxRestarts: Int = 3,
        manifest: PluginManifest? = null,
        jarPath: String? = null,
    ) {
        if (manifest != null && jarPath != null) {
            pluginSpawnInfo[pluginId] = manifest to jarPath
        }
        val info =
            PluginHealthInfo(
                pluginId = pluginId,
                displayName = displayName,
                processState = PluginProcessState.RUNNING,
                pid = spawner.getManagedProcess(pluginId)?.pid,
                maxRestarts = maxRestarts,
                connected = spawner.getStateBridge(pluginId)?.connected?.value == true,
            )
        _healthStates.value = _healthStates.value + (pluginId to info)
    }

    /**
     * Stop monitoring a plugin.
     */
    fun unmonitor(pluginId: String) {
        _healthStates.value = _healthStates.value - pluginId
        inProcessFallbacks.remove(pluginId)
    }

    /**
     * Start the periodic health check loop.
     */
    fun start() {
        scope.launch {
            while (isActive) {
                checkHealth()
                delay(checkIntervalMs)
            }
        }
    }

    /**
     * Request restart of a crashed plugin.
     */
    suspend fun restartPlugin(pluginId: String) {
        val current = _healthStates.value[pluginId] ?: return
        val spawnInfo = pluginSpawnInfo[pluginId]
        if (spawnInfo == null) {
            logger.error("Cannot restart plugin {}: no stored manifest/jarPath", pluginId)
            updateState(
                pluginId,
                current.copy(
                    processState = PluginProcessState.FAILED,
                    lastError = "No spawn info stored for restart",
                ),
            )
            return
        }

        updateState(pluginId, current.copy(processState = PluginProcessState.RESTARTING))
        logger.info("Restarting plugin: {}", pluginId)

        try {
            val (manifest, jarPath) = spawnInfo
            spawner.terminate(pluginId)
            spawner.spawn(manifest, jarPath).getOrThrow()
            updateState(
                pluginId,
                current.copy(
                    processState = PluginProcessState.RUNNING,
                    pid = spawner.getManagedProcess(pluginId)?.pid,
                    restartCount = current.restartCount + 1,
                    lastError = null,
                    connected = true,
                ),
            )
            logger.info("Plugin restarted successfully: {}", pluginId)
        } catch (e: Exception) {
            logger.error("Failed to restart plugin: {}", pluginId, e)
            updateState(
                pluginId,
                current.copy(
                    processState = PluginProcessState.FAILED,
                    lastError = e.message,
                ),
            )
        }
    }

    /**
     * Switch a plugin to in-process fallback mode.
     */
    fun switchToInProcess(pluginId: String) {
        val current = _healthStates.value[pluginId] ?: return
        inProcessFallbacks.add(pluginId)
        updateState(pluginId, current.copy(processState = PluginProcessState.IN_PROCESS_FALLBACK))
        logger.info("Plugin switched to in-process fallback: {}", pluginId)
    }

    /**
     * Check if a plugin is running in in-process fallback mode.
     */
    fun isInProcessFallback(pluginId: String): Boolean = pluginId in inProcessFallbacks

    fun dispose() {
        scope.cancel()
    }

    private fun checkHealth() {
        val states = _healthStates.value.toMutableMap()

        for ((pluginId, info) in states) {
            if (info.processState == PluginProcessState.IN_PROCESS_FALLBACK) continue

            val isAlive = spawner.isAlive(pluginId)
            val bridge = spawner.getStateBridge(pluginId)
            val isConnected = bridge?.connected?.value == true

            if (!isAlive && info.processState == PluginProcessState.RUNNING) {
                // Process died — transition to CRASHED
                val process = spawner.getManagedProcess(pluginId)
                val error = process?.lastError ?: "Process exited unexpectedly"
                val restartCount = info.restartCount + 1

                val newState =
                    if (restartCount >= info.maxRestarts) {
                        PluginProcessState.FAILED
                    } else {
                        PluginProcessState.CRASHED
                    }

                states[pluginId] =
                    info.copy(
                        processState = newState,
                        restartCount = restartCount,
                        lastError = error,
                        connected = false,
                    )

                logger.warn(
                    "Plugin process crashed: id={}, restarts={}/{}",
                    pluginId,
                    restartCount,
                    info.maxRestarts,
                )
            } else if (isAlive) {
                val process = spawner.getManagedProcess(pluginId)
                states[pluginId] =
                    info.copy(
                        processState = PluginProcessState.RUNNING,
                        pid = process?.pid,
                        connected = isConnected,
                        uptimeMs = process?.let { System.currentTimeMillis() - it.startTime } ?: 0,
                    )
            }
        }

        _healthStates.value = states
    }

    private fun updateState(
        pluginId: String,
        info: PluginHealthInfo,
    ) {
        _healthStates.value = _healthStates.value + (pluginId to info)
    }
}
