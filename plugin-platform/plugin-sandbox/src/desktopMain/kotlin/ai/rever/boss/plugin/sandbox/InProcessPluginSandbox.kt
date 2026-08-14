package ai.rever.boss.plugin.sandbox

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.sandbox.health.PluginHealthMetrics
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

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
    private val config: SandboxConfig = SandboxConfig(),
) : PluginSandbox {
    private val logger = BossLogger.forComponent("InProcessPluginSandbox")

    // State management
    private val _state = MutableStateFlow(SandboxState.STOPPED)
    override val state: StateFlow<SandboxState> = _state.asStateFlow()

    private val _healthMetrics = MutableStateFlow(PluginHealthMetrics.initial())
    override val healthMetrics: StateFlow<PluginHealthMetrics> = _healthMetrics.asStateFlow()

    // Thread pool - @Volatile for visibility across threads during restart
    @Volatile
    private var executor: ExecutorService = newExecutor()

    /**
     * Dispatcher indirection, so the backing thread pool can be replaced on
     * restart without replacing the dispatcher the plugin scope was built from.
     */
    private val dispatcher = SwappableDispatcher(executor.asCoroutineDispatcher())

    private val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            // Tag before anything else: a coroutine that fails here may still
            // reach the global uncaught handler (an undispatched rethrow, a
            // handler that itself fails), and by then this is the only place that
            // ever knew whose coroutine it was.
            PluginExecutionBoundary.tag(throwable, pluginId)
            logger.error(
                LogCategory.SYSTEM,
                "Uncaught exception in plugin sandbox",
                mapOf(
                    "pluginId" to pluginId,
                ),
                throwable,
            )
            recordError(throwable)
        }

    private val _sandboxScope = SandboxScope()

    /**
     * The coroutine scope for plugin operations.
     *
     * **This object is created once and never replaced.** Plugins read
     * `PluginContext.pluginScope` in `register()` and hand it to components,
     * view models and background jobs that outlive any single restart, so
     * handing out a *new* scope on restart left every one of those holding a
     * permanently cancelled one. `launch` on a cancelled scope neither runs nor
     * throws, so the plugin went silently inert - a panel would sit on a
     * spinner for ever with nothing in the logs - until the user reloaded it by
     * hand. What a restart cancels now is the scope's children; the scope
     * itself keeps working.
     *
     * **Thread Safety Note**: work launched during [SandboxState.RESTARTING]
     * may be cancelled along with the rest of the pre-restart children.
     * Callers should handle `CancellationException` gracefully.
     */
    override val sandboxScope: CoroutineScope
        get() = _sandboxScope

    private val isRunning = AtomicBoolean(false)

    // Lock for synchronizing executor/job recreation during restart
    private val restartLock = Any()

    // Heartbeat job for automatic heartbeat recording
    @Volatile
    private var heartbeatJob: Job? = null

    private fun newExecutor(): ExecutorService =
        Executors.newFixedThreadPool(config.maxThreads) { runnable ->
            Thread(runnable, "plugin-sandbox-$pluginId-${System.currentTimeMillis()}")
        }

    /**
     * A dispatcher whose backing pool can be swapped underneath it.
     *
     * A dispatch that reads the delegate just before a swap can land on a pool
     * that is shutting down. kotlinx's executor dispatcher already handles that
     * - it cancels the job and re-dispatches - so the race needs no handling of
     * its own here. Worth knowing what it re-dispatches *to*, though: kotlinx's
     * own shared executor, so for that one block the thread isolation this
     * sandbox exists to provide is not in force. The window predates this class
     * and is not widened by it.
     *
     * [Delay] is deliberately not forwarded. The pools here are plain fixed
     * thread pools, never scheduled ones, so the wrapped dispatcher had no
     * `Delay` to offer either and `delay()` inside plugin coroutines has always
     * used kotlinx's default. Swapping in a scheduled pool later would need
     * this revisited.
     */
    private class SwappableDispatcher(
        initial: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        @Volatile
        private var delegate: CoroutineDispatcher = initial

        fun swap(next: CoroutineDispatcher) {
            delegate = next
        }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = delegate.dispatch(context, block)
    }

    /**
     * The plugin-facing scope. Its identity is fixed for the life of the
     * sandbox; only the [Job] underneath it is replaced, so cancelling
     * everything a plugin has in flight does not cost it the ability to start
     * anything new.
     */
    private inner class SandboxScope : CoroutineScope {
        @Volatile
        private var job: CompletableJob = SupervisorJob()

        /**
         * Composed once per job generation rather than per read.
         *
         * Two reasons. Plugins launch a great deal of work on this scope and
         * every `launch` reads this, so rebuilding the context each time is
         * pure waste. More importantly a single volatile read cannot tear:
         * composing `dispatcher + job + exceptionHandler` on the fly let a
         * `launch` racing [resetJob] pick up the job that was about to be
         * cancelled, and a coroutine attached to an already-cancelled job does
         * not run and does not throw - the exact failure this class is being
         * changed to stop. It cannot be eliminated (a caller can always read
         * microseconds before the swap) but it is bounded to one restart rather
         * than composed of two different generations.
         */
        @Volatile
        private var context: CoroutineContext = dispatcher + job + exceptionHandler

        override val coroutineContext: CoroutineContext
            get() = context

        /** Cancel everything in flight and re-arm for new work. */
        fun resetJob() {
            job.cancel()
            install(SupervisorJob())
        }

        /** Cancel everything in flight, leaving the scope inert until re-armed. */
        fun cancelJob() {
            job.cancel()
        }

        /**
         * Re-arm after a [cancelJob], for a disable/enable cycle.
         *
         * Deliberately NOT called `ensureActive`: `CoroutineScope.ensureActive()`
         * is a kotlinx extension that *throws* when the job is cancelled, a
         * member of that name would win over it inside this class, and the two
         * meanings are opposites.
         */
        fun rearmIfCancelled() {
            if (!job.isActive) install(SupervisorJob())
        }

        private fun install(fresh: CompletableJob) {
            job = fresh
            context = dispatcher + fresh + exceptionHandler
        }
    }

    /**
     * Re-arm the pool and the scope's job if a previous [stop] retired them.
     *
     * Without this, disable-then-enable handed the plugin a scope whose job was
     * cancelled and whose executor was shut down, and the sandbox only ever
     * recovered by way of a watchdog restart.
     */
    private fun ensureRunnable() {
        synchronized(restartLock) {
            if (executor.isShutdown) {
                executor = newExecutor()
                dispatcher.swap(executor.asCoroutineDispatcher())
            }
            _sandboxScope.rearmIfCancelled()
        }
    }

    /**
     * Retire a pool, off whatever coroutine worker asked for it.
     *
     * [ExecutorService.awaitTermination] blocks for up to two seconds and both
     * callers are suspend functions reached from Dispatchers.Default, whose
     * workers are a small shared pool.
     */
    private suspend fun shutdownExecutor(target: ExecutorService) {
        withContext(Dispatchers.IO) {
            target.shutdown()
            try {
                if (!target.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Executor didn't terminate gracefully, forcing shutdown",
                        mapOf(
                            "pluginId" to pluginId,
                        ),
                    )
                    target.shutdownNow()
                }
            } catch (e: InterruptedException) {
                target.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }

    override suspend fun start(): Result<Unit> {
        return runCatching {
            if (isRunning.getAndSet(true)) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Sandbox already running",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                )
                return@runCatching
            }

            logger.info(
                LogCategory.SYSTEM,
                "Starting plugin sandbox",
                mapOf(
                    "pluginId" to pluginId,
                    "maxThreads" to config.maxThreads,
                ),
            )

            // A prior stop() retired the pool and the scope's job; bring both
            // back before anything is launched into them.
            ensureRunnable()

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
        val newJob =
            _sandboxScope.launch {
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
                logger.debug(
                    LogCategory.SYSTEM,
                    "Sandbox already stopped",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                )
                return@runCatching
            }

            logger.info(
                LogCategory.SYSTEM,
                "Stopping plugin sandbox",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )

            _state.value = SandboxState.STOPPED

            // Cancel heartbeat job
            heartbeatJob?.cancel()
            heartbeatJob = null

            // Cancel everything the plugin has in flight. The scope object
            // stays, inert, so a later start() can re-arm it in place rather
            // than handing the plugin a scope it will never read again.
            _sandboxScope.cancelJob()

            // Shutdown the executor and wait for termination
            shutdownExecutor(executor)
        }
    }

    override suspend fun restart(): Result<Unit> =
        runCatching {
            logger.info(
                LogCategory.SYSTEM,
                "Restarting plugin sandbox",
                mapOf(
                    "pluginId" to pluginId,
                    "restartAttempt" to (_healthMetrics.value.restartAttempts + 1),
                ),
            )

            _state.value = SandboxState.RESTARTING

            // Record the crash
            _healthMetrics.update { it.withCrash() }

            // Cancel heartbeat job
            heartbeatJob?.cancel()
            heartbeatJob = null

            // Synchronize executor/job swap to prevent other threads from accessing stale references
            val retiredExecutor: ExecutorService
            synchronized(restartLock) {
                // Cancel the plugin's in-flight coroutines and re-arm the scope
                // for new work. The scope object itself is deliberately kept -
                // see the note on [sandboxScope].
                _sandboxScope.resetJob()

                // Put the fresh pool in place before retiring the old one, so
                // nothing dispatched during the swap meets a dead executor.
                retiredExecutor = executor
                executor = newExecutor()
                dispatcher.swap(executor.asCoroutineDispatcher())
            }

            // Retire the old pool outside the lock: awaitTermination blocks for
            // seconds and would hold every other caller of restartLock behind it.
            shutdownExecutor(retiredExecutor)

            // Mark as running with successful restart metrics
            _healthMetrics.update { it.withSuccessfulRestart() }
            _state.value = SandboxState.RUNNING
            isRunning.set(true)

            // Start automatic heartbeat recording
            startHeartbeatJob()

            logger.info(
                LogCategory.SYSTEM,
                "Plugin sandbox restarted successfully",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )
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

        logger.warn(
            LogCategory.SYSTEM,
            "Recording error in plugin sandbox",
            mapOf(
                "pluginId" to pluginId,
                "consecutiveErrors" to (_healthMetrics.value.consecutiveErrors + 1),
                "errorType" to error.javaClass.simpleName,
            ),
            wrappedError,
        )

        _healthMetrics.update { it.withError() }

        // Binary incompatibility is deterministic — restart will never fix it.
        // Skip the restart loop and disable immediately.
        if (PluginErrorClassifier.isBinaryIncompatibility(error)) {
            logger.error(
                LogCategory.SYSTEM,
                "Binary incompatibility detected, disabling plugin",
                mapOf(
                    "pluginId" to pluginId,
                    "errorType" to error.javaClass.simpleName,
                ),
            )
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
            logger.warn(
                LogCategory.SYSTEM,
                "Marking plugin sandbox as unhealthy",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )
            _state.value = SandboxState.UNHEALTHY
            // Note: heartbeatJob intentionally continues running when unhealthy.
            // This allows: (1) the watchdog to detect heartbeat timeouts for restart decisions,
            // (2) health metrics to remain up-to-date during the unhealthy period,
            // (3) the plugin to potentially recover without a full restart.
        }
    }

    override fun resetHealth() {
        logger.info(
            LogCategory.SYSTEM,
            "Resetting plugin sandbox health",
            mapOf(
                "pluginId" to pluginId,
            ),
        )
        _healthMetrics.update {
            it.copy(
                consecutiveErrors = 0,
                restartAttempts = 0,
                lastHeartbeat = System.currentTimeMillis(),
            )
        }
        // If sandbox was unhealthy, mark it as running again
        if (_state.value == SandboxState.UNHEALTHY) {
            _state.value = SandboxState.RUNNING
        }
    }

    override fun resetRestartAttempts() {
        _healthMetrics.update { it.withRestartAttemptsCleared() }
    }

    /**
     * Mark the sandbox as disabled.
     * This is called by the PluginSandboxManager when a plugin is disabled.
     */
    fun setDisabled() {
        logger.info(
            LogCategory.SYSTEM,
            "Setting plugin sandbox as disabled",
            mapOf(
                "pluginId" to pluginId,
            ),
        )
        _state.value = SandboxState.DISABLED
    }

    /**
     * Set the state directly.
     * Used internally for state management.
     */
    internal fun setState(newState: SandboxState) {
        logger.debug(
            LogCategory.SYSTEM,
            "Setting sandbox state",
            mapOf(
                "pluginId" to pluginId,
                "oldState" to _state.value.name,
                "newState" to newState.name,
            ),
        )
        _state.value = newState
    }

    private companion object {
        const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2L
    }
}
