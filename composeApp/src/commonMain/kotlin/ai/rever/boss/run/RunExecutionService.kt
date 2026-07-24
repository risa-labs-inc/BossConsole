package ai.rever.boss.run

import kotlinx.coroutines.flow.StateFlow

/**
 * Expect declaration for RunExecutionService.
 * Handles execution of run configurations.
 * Platform-specific implementations handle process management.
 */
expect object RunExecutionService {
    /**
     * List of currently running processes.
     */
    val runningProcesses: StateFlow<List<RunningProcess>>

    /**
     * Whether any process is currently running.
     */
    val isRunning: StateFlow<Boolean>

    /**
     * Execute a run configuration.
     * Opens a terminal and runs the command.
     *
     * @param config The configuration to execute
     * @param debug Whether to run in debug mode (future feature)
     * @param windowId The window ID that initiated the run (Issue #498)
     * @return The running process info
     */
    suspend fun execute(
        config: RunConfiguration,
        debug: Boolean = false,
        windowId: String,
    ): RunningProcess?

    /**
     * Stop a running process by ID.
     *
     * @param processId The process ID to stop
     */
    suspend fun stop(processId: String)

    /**
     * Stop all running processes.
     */
    suspend fun stopAll()

    /**
     * Mark a process as completed.
     * Called when terminal exits.
     *
     * @param processId The process ID that completed
     * @param failed Whether the process failed
     */
    fun markCompleted(
        processId: String,
        failed: Boolean = false,
    )
}
