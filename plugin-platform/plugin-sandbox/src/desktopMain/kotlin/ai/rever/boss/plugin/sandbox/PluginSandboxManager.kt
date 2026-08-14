package ai.rever.boss.plugin.sandbox

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.sandbox.health.PluginHealthMonitor
import ai.rever.boss.plugin.sandbox.health.PluginHealthSummary
import ai.rever.boss.plugin.sandbox.health.PluginWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Callback interface for plugin cleanup operations.
 *
 * Components can register cleanup callbacks to be notified when
 * a plugin is being fully unloaded. This allows them to release
 * any references or cached data associated with the plugin.
 */
fun interface PluginCleanupCallback {
    /**
     * Called when a plugin is being fully unloaded.
     *
     * @param pluginId The ID of the plugin being unloaded
     */
    suspend fun onPluginUnloading(pluginId: String)
}

/**
 * Interface for managing plugin sandboxes.
 */
interface PluginSandboxManager {
    /**
     * Health summary across all sandboxes.
     */
    val healthSummary: StateFlow<PluginHealthSummary>

    /**
     * Create a new sandbox for a plugin.
     * @param pluginId Unique identifier for the plugin
     * @param config Optional configuration for the sandbox
     * @return The created sandbox
     */
    fun createSandbox(
        pluginId: String,
        config: SandboxConfig = SandboxConfig(),
    ): PluginSandbox

    /**
     * Get the sandbox for a specific plugin.
     * @param pluginId Plugin identifier
     * @return The sandbox, or null if not found
     */
    fun getSandbox(pluginId: String): PluginSandbox?

    /**
     * Remove and stop a sandbox.
     * @param pluginId Plugin identifier
     */
    suspend fun removeSandbox(pluginId: String)

    /**
     * Fully unload a plugin, running all cleanup callbacks.
     *
     * This is a comprehensive cleanup method that:
     * 1. Notifies all registered cleanup callbacks
     * 2. Stops the watchdog
     * 3. Stops the sandbox
     * 4. Removes all references
     * 5. Unregisters from health monitor
     *
     * Use this for dynamic plugin unloading to ensure complete cleanup.
     *
     * @param pluginId Plugin identifier
     * @return Result indicating success or failure
     */
    suspend fun fullyUnloadPlugin(pluginId: String): Result<Unit>

    /**
     * Register a cleanup callback to be called when plugins are unloaded.
     *
     * @param callback The callback to register
     */
    fun registerCleanupCallback(callback: PluginCleanupCallback)

    /**
     * Unregister a cleanup callback.
     *
     * @param callback The callback to unregister
     */
    fun unregisterCleanupCallback(callback: PluginCleanupCallback)

    /**
     * Restart a plugin's sandbox.
     * @param pluginId Plugin identifier
     * @return Result indicating success or failure
     */
    suspend fun restartPlugin(pluginId: String): Result<Unit>

    /**
     * Disable a plugin. It will be stopped and won't auto-restart.
     * @param pluginId Plugin identifier
     * @return Result indicating success or failure
     */
    suspend fun disablePlugin(pluginId: String): Result<Unit>

    /**
     * Enable a previously disabled plugin.
     * @param pluginId Plugin identifier
     * @return Result indicating success or failure
     */
    suspend fun enablePlugin(pluginId: String): Result<Unit>

    /**
     * Check if a plugin is disabled.
     * @param pluginId Plugin identifier
     * @return True if the plugin is disabled
     */
    fun isPluginDisabled(pluginId: String): Boolean

    /**
     * Get all disabled plugins.
     * @return Set of disabled plugin IDs
     */
    fun getDisabledPlugins(): Set<String>

    /**
     * Get all managed sandboxes.
     */
    fun getAllSandboxes(): Map<String, PluginSandbox>

    /**
     * Dispose of the manager and all sandboxes.
     */
    suspend fun dispose()
}

/**
 * Callback for plugin lifecycle events.
 */
interface PluginSandboxListener {
    /**
     * Called when a plugin is about to restart.
     */
    fun onPluginRestarting(pluginId: String) {}

    /**
     * Called when a plugin has successfully restarted.
     */
    fun onPluginRestarted(pluginId: String) {}

    /**
     * Called when a plugin has been disabled due to too many failures.
     */
    fun onPluginDisabled(pluginId: String) {}

    /**
     * Called when a plugin encounters an error.
     */
    fun onPluginError(
        pluginId: String,
        error: Throwable,
    ) {}
}

/**
 * Default implementation of PluginSandboxManager.
 */
class PluginSandboxManagerImpl(
    private val defaultConfig: SandboxConfig = SandboxConfig(),
) : PluginSandboxManager {
    private val logger = BossLogger.forComponent("PluginSandboxManager")

    // Manager's own scope for internal operations
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track disabled plugins (thread-safe)
    private val disabledPlugins = ConcurrentHashMap.newKeySet<String>()

    // Thread-safe collections for concurrent access from multiple coroutine scopes
    private val sandboxes = ConcurrentHashMap<String, InProcessPluginSandbox>()
    private val watchdogs = ConcurrentHashMap<String, PluginWatchdog>()

    /**
     * The config a plugin's sandbox was actually created with.
     *
     * Plugins can declare their own sandbox settings, so the restart budget and
     * backoff have to be read per plugin. They used to be enforced by the
     * watchdog, which is constructed with the plugin's own config; now that the
     * decision lives here, reading [defaultConfig] would quietly apply the
     * wrong thresholds to any plugin that asked for something else. Read off
     * the sandbox rather than a parallel map, so there is nothing to keep in
     * sync.
     */
    private fun configFor(pluginId: String): SandboxConfig = sandboxes[pluginId]?.config ?: defaultConfig

    // Weak references to prevent memory leaks from listeners that aren't removed
    private val listeners = CopyOnWriteArrayList<WeakReference<PluginSandboxListener>>()

    // Cleanup callbacks for dynamic plugin unloading
    private val cleanupCallbacks = CopyOnWriteArrayList<PluginCleanupCallback>()

    private val healthMonitor = PluginHealthMonitor(managerScope)
    override val healthSummary: StateFlow<PluginHealthSummary> = healthMonitor.healthSummary

    init {
        healthMonitor.start()
    }

    /**
     * Clean up dead weak references from the listeners list.
     */
    private fun cleanupDeadListeners() {
        listeners.removeIf { it.get() == null }
    }

    /**
     * Add a listener for plugin lifecycle events.
     * Uses weak references to prevent memory leaks.
     */
    fun addListener(listener: PluginSandboxListener) {
        cleanupDeadListeners()
        listeners.add(WeakReference(listener))
    }

    /**
     * Remove a listener.
     */
    fun removeListener(listener: PluginSandboxListener) {
        listeners.removeIf { it.get() == null || it.get() === listener }
    }

    /**
     * Notify all active listeners and clean up dead references.
     */
    private fun notifyListeners(action: (PluginSandboxListener) -> Unit) {
        listeners.removeIf { ref ->
            val listener = ref.get()
            if (listener != null) {
                action(listener)
                false // Keep reference
            } else {
                true // Remove dead reference
            }
        }
    }

    override fun createSandbox(
        pluginId: String,
        config: SandboxConfig,
    ): PluginSandbox {
        if (sandboxes.containsKey(pluginId)) {
            logger.warn(
                LogCategory.SYSTEM,
                "Sandbox already exists for plugin, returning existing",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )
            return sandboxes[pluginId]!!
        }

        logger.info(
            LogCategory.SYSTEM,
            "Creating sandbox for plugin",
            mapOf(
                "pluginId" to pluginId,
            ),
        )

        val sandbox = InProcessPluginSandbox(pluginId, config)
        sandboxes[pluginId] = sandbox
        healthMonitor.registerSandbox(sandbox)

        // Create and start watchdog
        val watchdog =
            PluginWatchdog(
                sandbox = sandbox,
                config = config,
                scope = managerScope,
                onRestartRequested = { id -> handleRestartRequest(id) },
            )
        watchdogs[pluginId] = watchdog
        watchdog.start()

        return sandbox
    }

    override fun getSandbox(pluginId: String): PluginSandbox? = sandboxes[pluginId]

    override suspend fun removeSandbox(pluginId: String) {
        logger.info(
            LogCategory.SYSTEM,
            "Removing sandbox",
            mapOf(
                "pluginId" to pluginId,
            ),
        )

        // Stop watchdog
        watchdogs[pluginId]?.stop()
        watchdogs.remove(pluginId)

        // Stop and remove sandbox
        sandboxes[pluginId]?.stop()
        sandboxes.remove(pluginId)

        // Unregister from health monitor
        healthMonitor.unregisterSandbox(pluginId)
    }

    override suspend fun fullyUnloadPlugin(pluginId: String): Result<Unit> =
        runCatching {
            logger.info(
                LogCategory.SYSTEM,
                "Fully unloading plugin",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )

            // 1. Notify all cleanup callbacks
            for (callback in cleanupCallbacks) {
                try {
                    callback.onPluginUnloading(pluginId)
                } catch (e: Exception) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Cleanup callback failed",
                        mapOf(
                            "pluginId" to pluginId,
                            "error" to (e.message ?: "unknown"),
                        ),
                    )
                }
            }

            // 2. Stop watchdog
            watchdogs[pluginId]?.stop()
            watchdogs.remove(pluginId)

            // 3. Stop sandbox
            sandboxes[pluginId]?.stop()
            sandboxes.remove(pluginId)

            // 4. Remove from disabled set if present
            disabledPlugins.remove(pluginId)

            // 5. Unregister from health monitor
            healthMonitor.unregisterSandbox(pluginId)

            logger.info(
                LogCategory.SYSTEM,
                "Plugin fully unloaded",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )
        }

    override fun registerCleanupCallback(callback: PluginCleanupCallback) {
        cleanupCallbacks.add(callback)
    }

    override fun unregisterCleanupCallback(callback: PluginCleanupCallback) {
        cleanupCallbacks.remove(callback)
    }

    override suspend fun restartPlugin(pluginId: String): Result<Unit> {
        val sandbox =
            sandboxes[pluginId]
                ?: return Result.failure(IllegalArgumentException("No sandbox found for plugin: $pluginId"))

        logger.info(
            LogCategory.SYSTEM,
            "Restarting plugin",
            mapOf(
                "pluginId" to pluginId,
            ),
        )

        notifyListeners { it.onPluginRestarting(pluginId) }

        return sandbox.restart().also { result ->
            if (result.isSuccess) {
                notifyListeners { it.onPluginRestarted(pluginId) }
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown restart failure")
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to restart plugin",
                    mapOf(
                        "pluginId" to pluginId,
                        "error" to (error.message ?: "unknown"),
                    ),
                )
                // Notify listeners about the restart failure for consistent event handling
                notifyListeners { it.onPluginError(pluginId, error) }
            }
        }
    }

    override suspend fun disablePlugin(pluginId: String): Result<Unit> =
        runCatching {
            logger.info(
                LogCategory.SYSTEM,
                "Disabling plugin",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )

            disabledPlugins.add(pluginId)

            val sandbox = sandboxes[pluginId]
            if (sandbox != null) {
                // Stop the watchdog to prevent auto-restart
                watchdogs[pluginId]?.stop()

                // Stop the sandbox and set state to DISABLED
                sandbox.stop()
                sandbox.setDisabled()
            }

            notifyListeners { it.onPluginDisabled(pluginId) }
        }

    override suspend fun enablePlugin(pluginId: String): Result<Unit> =
        runCatching {
            logger.info(
                LogCategory.SYSTEM,
                "Enabling plugin",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )

            disabledPlugins.remove(pluginId)

            val sandbox = sandboxes[pluginId]
            if (sandbox != null) {
                // Restart the watchdog
                val watchdog =
                    PluginWatchdog(
                        sandbox = sandbox,
                        config = configFor(pluginId),
                        scope = managerScope,
                        onRestartRequested = { id -> handleRestartRequest(id) },
                    )
                watchdogs[pluginId] = watchdog
                watchdog.start()

                // Start the sandbox
                sandbox.start()
            }
        }

    override fun isPluginDisabled(pluginId: String): Boolean = disabledPlugins.contains(pluginId)

    override fun getDisabledPlugins(): Set<String> = disabledPlugins.toSet()

    /**
     * What the watchdog asks for when a plugin looks dead. Internal rather than
     * private so the budget-exhaustion branch - the one path that disables a
     * plugin outright - is reachable from a test.
     */
    internal suspend fun handleRestartRequest(pluginId: String) {
        val sandbox = sandboxes[pluginId] ?: return
        val metrics = sandbox.healthMetrics.value
        val config = configFor(pluginId)

        // Check if we've exceeded max restarts.
        //
        // This is the ONLY place the restart budget is enforced. PluginWatchdog
        // used to hold a copy that ran first and so shadowed this one; it
        // stopped the sandbox without calling setDisabled(), which meant no
        // fallback UI, no notification and isPluginDisabled() still false. The
        // branch was unreachable while every restart zeroed restartAttempts,
        // and became reachable when the counter started surviving.
        if (metrics.restartAttempts >= config.maxRestartAttempts) {
            logger.error(
                LogCategory.SYSTEM,
                "Plugin exceeded max restart attempts, disabling",
                mapOf(
                    "pluginId" to pluginId,
                    "attempts" to metrics.restartAttempts,
                ),
            )
            // Order matters, and it is the opposite of what reads naturally.
            // This runs inside the watchdog's own coroutine (checkHealth ->
            // triggerRestart -> onRestartRequested), so stopping the watchdog
            // cancels the coroutine executing these very lines. Everything
            // after it that suspends is then skipped - and sandbox.stop()
            // suspends, in the withContext that retires the thread pool, with
            // the CancellationException swallowed by its own runCatching. Every
            // plugin disabled this way stranded a two-thread non-daemon pool
            // for the life of the process. So: tear the sandbox down first,
            // and stop the watchdog once nothing is left that needs to suspend.
            sandbox.stop()
            sandbox.setDisabled()
            disabledPlugins.add(pluginId)
            notifyListeners { it.onPluginDisabled(pluginId) }
            // Last: prevents further restart attempts, and cancels us.
            watchdogs[pluginId]?.stop()
            return
        }

        // Calculate backoff delay
        val backoffDelay = calculateBackoff(metrics.restartAttempts, config)
        logger.info(
            LogCategory.SYSTEM,
            "Scheduling plugin restart with backoff",
            mapOf(
                "pluginId" to pluginId,
                "backoffMs" to backoffDelay,
            ),
        )

        kotlinx.coroutines.delay(backoffDelay)
        restartPlugin(pluginId)
    }

    private fun calculateBackoff(
        attempt: Int,
        config: SandboxConfig,
    ): Long {
        // Exponential backoff: baseMs * 2^attempt (e.g., 1s, 2s, 4s, 8s... max 30s)
        // Uses bit shift (1L shl n) as efficient equivalent of 2^n
        // coerceIn handles both negative values and overflow prevention
        val safeAttempt = attempt.coerceIn(0, 30)
        val delay = config.restartBackoffBaseMs * (1L shl safeAttempt)
        return minOf(delay, config.restartBackoffMaxMs)
    }

    override fun getAllSandboxes(): Map<String, PluginSandbox> = sandboxes.toMap()

    override suspend fun dispose() {
        logger.info(LogCategory.SYSTEM, "Disposing sandbox manager")

        // Stop all watchdogs first (they use managerScope)
        watchdogs.values.forEach { it.stop() }
        watchdogs.clear()

        // Stop health monitor (uses managerScope)
        healthMonitor.stop()

        // Stop all sandboxes
        sandboxes.values.forEach { it.stop() }
        sandboxes.clear()

        // Brief delay to allow pending coroutines to complete before scope cancellation
        kotlinx.coroutines.delay(100)

        // Cancel manager scope last since watchdogs and monitor depend on it
        managerScope.cancel()
    }
}
