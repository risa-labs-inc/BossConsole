package ai.rever.boss.plugin.sandbox

import ai.rever.boss.plugin.sandbox.health.PluginHealthMetrics
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Out-of-process sandbox implementation.
 *
 * Delegates lifecycle (start / stop / restart) to caller-supplied callbacks,
 * keeping the sandbox itself free of any direct ProcessBuilder or gRPC
 * dependency. The caller (e.g. AppProcessManager or PluginProcessRouter)
 * supplies lambdas that spawn and terminate the child JVM/native process.
 *
 * Health metrics are tracked locally. Heartbeats from the remote process
 * are expected to arrive via `recordHeartbeat()` calls forwarded over IPC.
 *
 * The `sandboxScope` provided here is only for in-process bookkeeping
 * coroutines — the plugin code itself runs in the child process.
 */
class OutOfProcessPluginSandbox(
    override val pluginId: String,
    private val config: SandboxConfig = SandboxConfig(),
    /** Called when the sandbox should spawn the child process. */
    private val onStart: suspend (pluginId: String) -> Result<Unit>,
    /** Called when the sandbox should terminate the child process. */
    private val onStop: suspend (pluginId: String) -> Result<Unit>,
    /** Called when the sandbox should kill-and-respawn the child process. */
    private val onRestart: suspend (pluginId: String) -> Result<Unit>,
) : PluginSandbox {

    private val logger = BossLogger.forComponent("OutOfProcessPluginSandbox")

    private val _state = MutableStateFlow(SandboxState.STOPPED)
    override val state: StateFlow<SandboxState> = _state.asStateFlow()

    private val _healthMetrics = MutableStateFlow(PluginHealthMetrics.initial())
    override val healthMetrics: StateFlow<PluginHealthMetrics> = _healthMetrics.asStateFlow()

    /** Bookkeeping scope for health/watchdog coroutines in the kernel process. */
    override val sandboxScope: CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob())

    override suspend fun start(): Result<Unit> {
        if (_state.value == SandboxState.RUNNING) {
            logger.debug(LogCategory.SYSTEM, "OutOfProcess sandbox already running", mapOf("pluginId" to pluginId))
            return Result.success(Unit)
        }
        logger.info(LogCategory.SYSTEM, "Starting out-of-process sandbox", mapOf("pluginId" to pluginId))
        return onStart(pluginId).also { result ->
            if (result.isSuccess) {
                _state.value = SandboxState.RUNNING
                _healthMetrics.value = PluginHealthMetrics.initial()
            }
        }
    }

    override suspend fun stop(): Result<Unit> {
        if (_state.value == SandboxState.STOPPED) {
            logger.debug(LogCategory.SYSTEM, "OutOfProcess sandbox already stopped", mapOf("pluginId" to pluginId))
            return Result.success(Unit)
        }
        logger.info(LogCategory.SYSTEM, "Stopping out-of-process sandbox", mapOf("pluginId" to pluginId))
        return onStop(pluginId).also { result ->
            if (result.isSuccess) {
                _state.value = SandboxState.STOPPED
            }
        }
    }

    override suspend fun restart(): Result<Unit> {
        logger.info(
            LogCategory.SYSTEM, "Restarting out-of-process sandbox",
            mapOf("pluginId" to pluginId, "restartAttempt" to (_healthMetrics.value.restartAttempts + 1))
        )
        _state.value = SandboxState.RESTARTING
        _healthMetrics.update { it.withCrash() }
        return onRestart(pluginId).also { result ->
            if (result.isSuccess) {
                _healthMetrics.update { it.withSuccessfulRestart() }
                _state.value = SandboxState.RUNNING
            } else {
                _state.value = SandboxState.CRASHED
            }
        }
    }

    override fun recordHeartbeat() {
        _healthMetrics.update { it.withHeartbeat() }
    }

    override fun recordSuccess() {
        _healthMetrics.update { it.withSuccess() }
    }

    override fun recordError(error: Throwable) {
        logger.warn(
            LogCategory.SYSTEM, "Error in out-of-process plugin",
            mapOf("pluginId" to pluginId, "errorType" to error.javaClass.simpleName), error
        )
        _healthMetrics.update { it.withError() }
        if (_healthMetrics.value.consecutiveErrors >= config.maxConsecutiveErrors) {
            markUnhealthy()
        }
    }

    override fun markUnhealthy() {
        if (_state.value == SandboxState.RUNNING) {
            logger.warn(
                LogCategory.SYSTEM, "Marking out-of-process sandbox as unhealthy",
                mapOf("pluginId" to pluginId)
            )
            _state.value = SandboxState.UNHEALTHY
        }
    }

    override fun resetHealth() {
        logger.info(LogCategory.SYSTEM, "Resetting out-of-process sandbox health", mapOf("pluginId" to pluginId))
        _healthMetrics.update {
            it.copy(
                consecutiveErrors = 0,
                lastHeartbeat = System.currentTimeMillis()
            )
        }
        if (_state.value == SandboxState.UNHEALTHY) {
            _state.value = SandboxState.RUNNING
        }
    }
}
