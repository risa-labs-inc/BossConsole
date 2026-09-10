package ai.rever.boss.components.plugin.remote

import ai.rever.boss.components.plugin.OutOfProcessPluginSpawnerImpl
import ai.rever.boss.components.plugin.PluginStateBridge
import ai.rever.boss.kernel.isReaping
import ai.rever.boss.process.ManagedProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

internal interface PluginProcessMonitorBackend {
    fun getManagedProcess(pluginId: String): ManagedProcess?

    fun isAlive(pluginId: String): Boolean

    fun isConnected(pluginId: String): Boolean

    fun isRestartAllowed(): Boolean
}

private class SpawnerPluginProcessMonitorBackend(
    private val spawner: OutOfProcessPluginSpawnerImpl,
) : PluginProcessMonitorBackend {
    override fun getManagedProcess(pluginId: String): ManagedProcess? = spawner.getManagedProcess(pluginId)

    override fun isAlive(pluginId: String): Boolean = spawner.isAlive(pluginId)

    override fun isConnected(pluginId: String): Boolean = spawner.getStateBridge(pluginId)?.connected?.value == true

    override fun isRestartAllowed(): Boolean = !isReaping()
}

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
@Suppress("TooManyFunctions")
class PluginProcessMonitor internal constructor(
    private val backend: PluginProcessMonitorBackend,
    private val checkIntervalMs: Long = 5_000,
) {
    constructor(
        spawner: OutOfProcessPluginSpawnerImpl,
        checkIntervalMs: Long = 5_000,
    ) : this(
        backend = SpawnerPluginProcessMonitorBackend(spawner),
        checkIntervalMs = checkIntervalMs,
    )

    private val logger = LoggerFactory.getLogger(PluginProcessMonitor::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val disposed =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val monitoringStarted =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** Health state for all monitored plugins. */
    private val _healthStates = MutableStateFlow<Map<String, PluginHealthInfo>>(emptyMap())
    val healthStates: StateFlow<Map<String, PluginHealthInfo>> = _healthStates.asStateFlow()

    /** Plugins that have been switched to in-process fallback. Thread-safe. */
    private val inProcessFallbacks =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** Restart action captured for each monitored plugin. */
    private val restartActions =
        java.util.concurrent.ConcurrentHashMap<String, suspend () -> Result<Unit>>()

    private val terminalFailureActions =
        java.util.concurrent.ConcurrentHashMap<String, suspend () -> Unit>()

    private val restartingPlugins =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    private data class RestartRequest(
        val pluginId: String,
        val previousHealth: PluginHealthInfo,
        val attempt: Int,
        val action: suspend () -> Result<Unit>,
    )

    /**
     * Start monitoring a plugin process.
     */
    fun monitor(
        pluginId: String,
        displayName: String,
        maxRestarts: Int = 3,
        restartAction: (suspend () -> Result<Unit>)? = null,
        terminalFailureAction: (suspend () -> Unit)? = null,
    ) {
        if (disposed.get()) return

        if (restartAction != null) {
            restartActions[pluginId] = restartAction
        }
        if (terminalFailureAction != null) {
            terminalFailureActions[pluginId] = terminalFailureAction
        }
        val info =
            PluginHealthInfo(
                pluginId = pluginId,
                displayName = displayName,
                processState = PluginProcessState.RUNNING,
                pid = backend.getManagedProcess(pluginId)?.pid,
                maxRestarts = maxRestarts,
                connected = backend.isConnected(pluginId),
            )
        _healthStates.update { states -> states + (pluginId to info) }
    }

    /**
     * Stop monitoring a plugin.
     */
    fun unmonitor(pluginId: String) {
        _healthStates.update { states -> states - pluginId }
        inProcessFallbacks.remove(pluginId)
        restartActions.remove(pluginId)
        terminalFailureActions.remove(pluginId)
    }

    /**
     * Start the periodic health check loop.
     */
    fun start() {
        if (disposed.get() || !monitoringStarted.compareAndSet(false, true)) return

        scope.launch {
            while (isActive) {
                runHealthCheckSafely()
                delay(checkIntervalMs)
            }
        }
    }

    private suspend fun runHealthCheckSafely() {
        try {
            checkHealth()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Plugin health check failed", e)
        }
    }

    /**
     * Request restart of a crashed plugin.
     */
    suspend fun restartPlugin(pluginId: String) {
        if (!disposed.get() && restartingPlugins.add(pluginId)) {
            try {
                prepareRestart(pluginId)?.let { request ->
                    executeRestart(request)
                }
            } finally {
                restartingPlugins.remove(pluginId)
            }
        }
    }

    private suspend fun prepareRestart(pluginId: String): RestartRequest? {
        val current = _healthStates.value[pluginId]
        val restartAction = restartActions[pluginId]

        return when {
            current == null -> {
                null
            }

            !current.isRestartable() -> {
                null
            }

            current.restartCount >= current.maxRestarts -> {
                if (current.processState != PluginProcessState.FAILED) {
                    runTerminalFailureAction(pluginId)
                }
                updateState(
                    pluginId,
                    current.copy(processState = PluginProcessState.FAILED),
                )
                null
            }

            restartAction == null -> {
                if (current.processState != PluginProcessState.FAILED) {
                    runTerminalFailureAction(pluginId)
                }

                logger.error("Cannot restart plugin {}: no restart action stored", pluginId)
                updateState(
                    pluginId,
                    current.copy(
                        processState = PluginProcessState.FAILED,
                        lastError = "No restart action stored",
                    ),
                )
                null
            }

            else -> {
                RestartRequest(
                    pluginId = pluginId,
                    previousHealth = current,
                    attempt = current.restartCount + 1,
                    action = restartAction,
                )
            }
        }
    }

    private suspend fun runTerminalFailureAction(pluginId: String) {
        try {
            terminalFailureActions[pluginId]?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to clean terminal plugin resources: {}", pluginId, e)
        }
    }

    private suspend fun executeRestart(request: RestartRequest) {
        val pluginId = request.pluginId
        val previousHealth = request.previousHealth

        updateState(
            pluginId,
            previousHealth.copy(
                processState = PluginProcessState.RESTARTING,
                restartCount = request.attempt,
                connected = false,
            ),
        )
        logger.info(
            "Restarting plugin: id={}, attempt={}/{}",
            pluginId,
            request.attempt,
            previousHealth.maxRestarts,
        )

        try {
            request.action().getOrThrow()

            if (isMonitored(pluginId)) {
                completeRestart(request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isMonitored(pluginId)) {
                recordRestartFailure(request, e)
            }
        }
    }

    private fun completeRestart(request: RestartRequest) {
        val pluginId = request.pluginId
        val process = backend.getManagedProcess(pluginId)
        val latest = _healthStates.value[pluginId] ?: request.previousHealth

        updateState(
            pluginId,
            latest.copy(
                processState = PluginProcessState.RUNNING,
                pid = process?.pid,
                restartCount = request.attempt,
                lastError = null,
                connected = backend.isConnected(pluginId),
                uptimeMs = 0,
            ),
        )
        logger.info("Plugin restarted successfully: {}", pluginId)
    }

    private suspend fun recordRestartFailure(
        request: RestartRequest,
        error: Exception,
    ) {
        val pluginId = request.pluginId
        val previousHealth = request.previousHealth
        val latest = _healthStates.value[pluginId] ?: previousHealth
        val failureState =
            if (request.attempt >= previousHealth.maxRestarts) {
                PluginProcessState.FAILED
            } else {
                PluginProcessState.CRASHED
            }

        if (failureState == PluginProcessState.FAILED) {
            runTerminalFailureAction(pluginId)
        }

        updateState(
            pluginId,
            latest.copy(
                processState = failureState,
                restartCount = request.attempt,
                lastError = error.message ?: "Plugin restart failed",
                connected = false,
            ),
        )
        logger.error(
            "Failed to restart plugin: id={}, attempt={}/{}",
            pluginId,
            request.attempt,
            previousHealth.maxRestarts,
            error,
        )
    }

    private fun PluginHealthInfo.isRestartable(): Boolean =
        processState == PluginProcessState.CRASHED ||
            processState == PluginProcessState.FAILED

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

    fun isMonitored(pluginId: String): Boolean = pluginId in _healthStates.value

    fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            scope.cancel()
            restartActions.clear()
            terminalFailureActions.clear()
            restartingPlugins.clear()
            inProcessFallbacks.clear()
            _healthStates.value = emptyMap()
        }
    }

    internal suspend fun checkHealthNow() = checkHealth()

    private suspend fun checkHealth() {
        if (disposed.get() || !backend.isRestartAllowed()) return

        val pluginIds = _healthStates.value.keys.toList()

        pluginIds.forEach { pluginId ->
            _healthStates.value[pluginId]?.let { info ->
                checkPluginHealth(pluginId, info)
            }
        }
    }

    private suspend fun checkPluginHealth(
        pluginId: String,
        info: PluginHealthInfo,
    ) {
        when (info.processState) {
            PluginProcessState.CRASHED -> restartPlugin(pluginId)

            PluginProcessState.RUNNING -> inspectRunningPlugin(pluginId, info)

            PluginProcessState.RESTARTING,
            PluginProcessState.FAILED,
            PluginProcessState.IN_PROCESS_FALLBACK,
            -> Unit
        }
    }

    private suspend fun inspectRunningPlugin(
        pluginId: String,
        info: PluginHealthInfo,
    ) {
        if (backend.isAlive(pluginId)) {
            refreshRunningPlugin(pluginId, info)
        } else {
            recordPluginCrash(pluginId, info)
        }
    }

    private suspend fun recordPluginCrash(
        pluginId: String,
        info: PluginHealthInfo,
    ) {
        val process = backend.getManagedProcess(pluginId)
        val error = process?.lastError ?: "Process exited unexpectedly"

        updateState(
            pluginId,
            info.copy(
                processState = PluginProcessState.CRASHED,
                lastError = error,
                connected = false,
            ),
        )
        logger.warn(
            "Plugin process crashed: id={}, completed restarts={}/{}",
            pluginId,
            info.restartCount,
            info.maxRestarts,
        )

        restartPlugin(pluginId)
    }

    private fun refreshRunningPlugin(
        pluginId: String,
        info: PluginHealthInfo,
    ) {
        val process = backend.getManagedProcess(pluginId)
        updateState(
            pluginId,
            info.copy(
                processState = PluginProcessState.RUNNING,
                pid = process?.pid,
                connected = backend.isConnected(pluginId),
                uptimeMs =
                    process?.let {
                        System.currentTimeMillis() - it.startTime
                    } ?: 0,
            ),
        )
    }

    private fun updateState(
        pluginId: String,
        info: PluginHealthInfo,
    ) {
        while (true) {
            val states = _healthStates.value
            if (pluginId !in states) return

            val updated = states + (pluginId to info)
            if (_healthStates.compareAndSet(states, updated)) return
        }
    }
}
