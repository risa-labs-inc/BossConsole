package ai.rever.boss.plugin.sandbox

import ai.rever.boss.plugin.sandbox.health.PluginHealthMetrics
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process sandbox implementation for UI plugins.
 *
 * Provides crash isolation through:
 * - SupervisorJob: Child coroutine failures don't propagate to siblings
 * - Dedicated thread pool: Plugin execution doesn't block the main thread
 * - Exception handler: All uncaught exceptions are captured and recorded
 * - Health tracking: Heartbeats and errors are monitored
 */
class InProcessPluginSandbox(
    override val pluginId: String,
    private val config: SandboxConfig = SandboxConfig()
) : PluginSandbox {

    private val logger = BossLogger.forComponent("InProcessPluginSandbox")

    // State management
    private val _state = MutableStateFlow(SandboxState.STOPPED)
    override val state: StateFlow<SandboxState> = _state.asStateFlow()

    private val _healthMetrics = MutableStateFlow(PluginHealthMetrics.initial())
    override val healthMetrics: StateFlow<PluginHealthMetrics> = _healthMetrics.asStateFlow()

    // Thread pool and coroutine scope - @Volatile for visibility across threads during restart
    @Volatile
    private var executor = Executors.newFixedThreadPool(config.maxThreads) { runnable ->
        Thread(runnable, "plugin-sandbox-$pluginId-${System.currentTimeMillis()}")
    }
    @Volatile
    private var dispatcher = executor.asCoroutineDispatcher()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error(LogCategory.SYSTEM, "Uncaught exception in plugin sandbox", mapOf(
            "pluginId" to pluginId
        ), throwable)
        recordError(throwable)
    }

    @Volatile
    private var _sandboxScope: CoroutineScope = createScope()

    /**
     * The coroutine scope for plugin operations.
     *
     * **Thread Safety Note**: Access during [SandboxState.RESTARTING] may return a scope
     * that is being cancelled. Callers should check [state] before launching long-running
     * coroutines, or handle [CancellationException] gracefully.
     */
    override val sandboxScope: CoroutineScope
        get() = _sandboxScope

    private val isRunning = AtomicBoolean(false)

    // Lock for synchronizing executor/scope recreation during restart
    private val restartLock = Any()

    // Heartbeat job for automatic heartbeat recording
    @Volatile
    private var heartbeatJob: Job? = null

    private fun createScope(): CoroutineScope {
        return CoroutineScope(dispatcher + SupervisorJob() + exceptionHandler)
    }

    override suspend fun start(): Result<Unit> {
        return runCatching {
            if (isRunning.getAndSet(true)) {
                logger.debug(LogCategory.SYSTEM, "Sandbox already running", mapOf(
                    "pluginId" to pluginId
                ))
                return@runCatching
            }

            logger.info(LogCategory.SYSTEM, "Starting plugin sandbox", mapOf(
                "pluginId" to pluginId,
                "maxThreads" to config.maxThreads
            ))

            _state.value = SandboxState.RUNNING
            _healthMetrics.value = PluginHealthMetrics.initial()

            // Start automatic heartbeat recording
            startHeartbeatJob()
        }
    }

    /**
     * Start the automatic heartbeat job.
     * This ensures heartbeats are recorded even when UI is not visible.
     */
    private fun startHeartbeatJob() {
        heartbeatJob?.cancel()
        // Launch first, then assign - ensures we only hold reference to successfully created job
        val newJob = _sandboxScope.launch {
            while (isActive) {
                recordHeartbeat()
                delay(config.heartbeatIntervalMs)
            }
        }
        heartbeatJob = newJob
    }

    override suspend fun stop(): Result<Unit> {
        return runCatching {
            if (!isRunning.getAndSet(false)) {
                logger.debug(LogCategory.SYSTEM, "Sandbox already stopped", mapOf(
                    "pluginId" to pluginId
                ))
                return@runCatching
            }

            logger.info(LogCategory.SYSTEM, "Stopping plugin sandbox", mapOf(
                "pluginId" to pluginId
            ))

            _state.value = SandboxState.STOPPED

            // Cancel heartbeat job
            heartbeatJob?.cancel()
            heartbeatJob = null

            // Cancel the coroutine scope
            _sandboxScope.cancel()

            // Shutdown the executor and wait for termination
            executor.shutdown()
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.warn(LogCategory.SYSTEM, "Executor didn't terminate gracefully, forcing shutdown", mapOf(
                        "pluginId" to pluginId
                    ))
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }

    override suspend fun restart(): Result<Unit> {
        return runCatching {
            logger.info(LogCategory.SYSTEM, "Restarting plugin sandbox", mapOf(
                "pluginId" to pluginId,
                "restartAttempt" to (_healthMetrics.value.restartAttempts + 1)
            ))

            _state.value = SandboxState.RESTARTING

            // Record the crash
            _healthMetrics.update { it.withCrash() }

            // Cancel heartbeat job
            heartbeatJob?.cancel()
            heartbeatJob = null

            // Synchronize executor/scope swap to prevent other threads from accessing stale references
            synchronized(restartLock) {
                // Cancel existing scope
                _sandboxScope.cancel()

                // Shutdown old executor and wait for termination (consistent with stop())
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                    }
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Thread.currentThread().interrupt()
                }

                // Create new executor and scope atomically
                executor = Executors.newFixedThreadPool(config.maxThreads) { runnable ->
                    Thread(runnable, "plugin-sandbox-$pluginId-${System.currentTimeMillis()}")
                }
                dispatcher = executor.asCoroutineDispatcher()
                _sandboxScope = createScope()
            }

            // Mark as running with successful restart metrics
            _healthMetrics.update { it.withSuccessfulRestart() }
            _state.value = SandboxState.RUNNING
            isRunning.set(true)

            // Start automatic heartbeat recording
            startHeartbeatJob()

            logger.info(LogCategory.SYSTEM, "Plugin sandbox restarted successfully", mapOf(
                "pluginId" to pluginId
            ))
        }
    }

    override fun recordHeartbeat() {
        _healthMetrics.update { it.withHeartbeat() }
    }

    override fun recordSuccess() {
        _healthMetrics.update { it.withSuccess() }
    }

    override fun recordError(error: Throwable) {
        // Wrap the error with plugin attribution
        val wrappedError = PluginException.createByPlugin(pluginId, error)

        logger.warn(LogCategory.SYSTEM, "Recording error in plugin sandbox", mapOf(
            "pluginId" to pluginId,
            "consecutiveErrors" to (_healthMetrics.value.consecutiveErrors + 1),
            "errorType" to error.javaClass.simpleName
        ), wrappedError)

        _healthMetrics.update { it.withError() }

        // Binary incompatibility is deterministic — restart will never fix it.
        // Skip the restart loop and disable immediately.
        if (PluginErrorClassifier.isBinaryIncompatibility(error)) {
            logger.error(LogCategory.SYSTEM, "Binary incompatibility detected, disabling plugin", mapOf(
                "pluginId" to pluginId,
                "errorType" to error.javaClass.simpleName
            ))
            PluginCrashRegistry.markIncompatible(pluginId)
            _state.value = SandboxState.DISABLED
            return
        }

        // Check if we should mark as unhealthy
        if (_healthMetrics.value.consecutiveErrors >= config.maxConsecutiveErrors) {
            markUnhealthy()
        }
    }

    override fun markUnhealthy() {
        if (_state.value == SandboxState.RUNNING) {
            logger.warn(LogCategory.SYSTEM, "Marking plugin sandbox as unhealthy", mapOf(
                "pluginId" to pluginId
            ))
            _state.value = SandboxState.UNHEALTHY
            // Note: heartbeatJob intentionally continues running when unhealthy.
            // This allows: (1) the watchdog to detect heartbeat timeouts for restart decisions,
            // (2) health metrics to remain up-to-date during the unhealthy period,
            // (3) the plugin to potentially recover without a full restart.
        }
    }

    override fun resetHealth() {
        logger.info(LogCategory.SYSTEM, "Resetting plugin sandbox health", mapOf(
            "pluginId" to pluginId
        ))
        _healthMetrics.update {
            it.copy(
                consecutiveErrors = 0,
                lastHeartbeat = System.currentTimeMillis()
            )
        }
        // If sandbox was unhealthy, mark it as running again
        if (_state.value == SandboxState.UNHEALTHY) {
            _state.value = SandboxState.RUNNING
        }
    }

    /**
     * Mark the sandbox as disabled.
     * This is called by the PluginSandboxManager when a plugin is disabled.
     */
    fun setDisabled() {
        logger.info(LogCategory.SYSTEM, "Setting plugin sandbox as disabled", mapOf(
            "pluginId" to pluginId
        ))
        _state.value = SandboxState.DISABLED
    }

    /**
     * Set the state directly.
     * Used internally for state management.
     */
    internal fun setState(newState: SandboxState) {
        logger.debug(LogCategory.SYSTEM, "Setting sandbox state", mapOf(
            "pluginId" to pluginId,
            "oldState" to _state.value.name,
            "newState" to newState.name
        ))
        _state.value = newState
    }
}
