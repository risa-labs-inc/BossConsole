package ai.rever.boss.plugin.sandbox.health

import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.SandboxState
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Summary of overall plugin health across all sandboxes.
 */
data class PluginHealthSummary(
    /**
     * Total number of plugins being monitored.
     */
    val totalPlugins: Int = 0,

    /**
     * Number of plugins currently running healthy.
     */
    val healthyPlugins: Int = 0,

    /**
     * Number of plugins in unhealthy state.
     */
    val unhealthyPlugins: Int = 0,

    /**
     * Number of plugins that have crashed.
     */
    val crashedPlugins: Int = 0,

    /**
     * Number of plugins currently restarting.
     */
    val restartingPlugins: Int = 0,

    /**
     * Number of plugins that have been stopped/disabled.
     */
    val stoppedPlugins: Int = 0,

    /**
     * IDs of plugins that are currently unhealthy or crashed.
     */
    val problematicPluginIds: List<String> = emptyList()
)

/**
 * Aggregates health information across all plugin sandboxes.
 *
 * Provides a global view of plugin health for monitoring and dashboard purposes.
 */
class PluginHealthMonitor(
    private val scope: CoroutineScope
) {
    private val logger = BossLogger.forComponent("PluginHealthMonitor")
    // Thread-safe: accessed from multiple coroutine scopes (register/unregister/update)
    private val sandboxes = ConcurrentHashMap<String, PluginSandbox>()
    // Track state observer jobs for cleanup on unregister
    private val stateObserverJobs = ConcurrentHashMap<String, Job>()

    private val _healthSummary = MutableStateFlow(PluginHealthSummary())
    val healthSummary: StateFlow<PluginHealthSummary> = _healthSummary.asStateFlow()

    private var monitorJob: Job? = null

    /**
     * Register a sandbox to be monitored.
     * Sets up a state observer that auto-unregisters when sandbox is stopped/disabled.
     */
    fun registerSandbox(sandbox: PluginSandbox) {
        val pluginId = sandbox.pluginId
        logger.debug(LogCategory.SYSTEM, "Registering sandbox for monitoring", mapOf(
            "pluginId" to pluginId
        ))
        sandboxes[pluginId] = sandbox

        // Set up state observer for auto-cleanup when sandbox stops
        val observerJob = scope.launch {
            sandbox.state.collect { state ->
                if (state == SandboxState.STOPPED || state == SandboxState.DISABLED) {
                    logger.debug(LogCategory.SYSTEM, "Auto-unregistering stopped sandbox", mapOf(
                        "pluginId" to pluginId,
                        "state" to state.name
                    ))
                    unregisterSandbox(pluginId)
                }
            }
        }
        stateObserverJobs[pluginId] = observerJob

        updateHealthSummary()
    }

    /**
     * Unregister a sandbox from monitoring.
     */
    fun unregisterSandbox(pluginId: String) {
        logger.debug(LogCategory.SYSTEM, "Unregistering sandbox from monitoring", mapOf(
            "pluginId" to pluginId
        ))
        // Cancel and remove the state observer job
        stateObserverJobs.remove(pluginId)?.cancel()
        sandboxes.remove(pluginId)
        updateHealthSummary()
    }

    /**
     * Start periodic health summary updates.
     */
    fun start(updateIntervalMs: Long = 5000) {
        if (monitorJob?.isActive == true) {
            return
        }

        logger.info(LogCategory.SYSTEM, "Starting health monitor", mapOf(
            "updateIntervalMs" to updateIntervalMs
        ))

        monitorJob = scope.launch {
            while (isActive) {
                updateHealthSummary()
                delay(updateIntervalMs)
            }
        }
    }

    /**
     * Stop health monitoring and clean up all observer jobs.
     */
    fun stop() {
        logger.info(LogCategory.SYSTEM, "Stopping health monitor")
        monitorJob?.cancel()
        monitorJob = null
        // Cancel all state observer jobs
        stateObserverJobs.values.forEach { it.cancel() }
        stateObserverJobs.clear()
    }

    /**
     * Get current health metrics for a specific plugin.
     */
    fun getPluginHealth(pluginId: String): PluginHealthMetrics? {
        return sandboxes[pluginId]?.healthMetrics?.value
    }

    /**
     * Get the current state of a specific plugin sandbox.
     */
    fun getPluginState(pluginId: String): SandboxState? {
        return sandboxes[pluginId]?.state?.value
    }

    /**
     * Force an immediate health summary update.
     */
    fun updateHealthSummary() {
        val states = sandboxes.values.map { it.state.value }
        val problematic = sandboxes.filter {
            val state = it.value.state.value
            state == SandboxState.UNHEALTHY || state == SandboxState.CRASHED
        }.keys.toList()

        _healthSummary.value = PluginHealthSummary(
            totalPlugins = sandboxes.size,
            healthyPlugins = states.count { it == SandboxState.RUNNING },
            unhealthyPlugins = states.count { it == SandboxState.UNHEALTHY },
            crashedPlugins = states.count { it == SandboxState.CRASHED },
            restartingPlugins = states.count { it == SandboxState.RESTARTING },
            stoppedPlugins = states.count { it == SandboxState.STOPPED },
            problematicPluginIds = problematic
        )

        if (problematic.isNotEmpty()) {
            logger.debug(LogCategory.SYSTEM, "Health summary updated with issues", mapOf(
                "problematicPlugins" to problematic.joinToString(",")
            ))
        }
    }
}
