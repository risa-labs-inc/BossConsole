package ai.rever.boss.run

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Desktop implementation of RunExecutionService.
 * Executes run configurations using RunnerTerminalService which respects
 * the runner settings for sidebar vs main panel terminal.
 */
actual object RunExecutionService {
    private val logger = BossLogger.forComponent("RunExecutionService")
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _runningProcesses = MutableStateFlow<List<RunningProcess>>(emptyList())
    actual val runningProcesses: StateFlow<List<RunningProcess>> = _runningProcesses.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    actual val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    init {
        // Update isRunning based on running processes
        scope.launch {
            _runningProcesses.collect { processes ->
                _isRunning.value = processes.any {
                    it.status == ProcessStatus.STARTING || it.status == ProcessStatus.RUNNING
                }
            }
        }
    }

    /**
     * Execute a run configuration using RunnerTerminalService.
     * Respects runner settings for sidebar vs main panel terminal.
     *
     * @param windowId The window ID that initiated the run (Issue #498)
     */
    actual suspend fun execute(config: RunConfiguration, debug: Boolean, windowId: String): RunningProcess? {
        try {
            val processId = UUID.randomUUID().toString()

            // Build the full command (for display/tracking only)
            val command = buildFullCommand(config, debug)

            // Create running process entry
            val process = RunningProcess(
                id = processId,
                configId = config.id,
                configName = config.name,
                command = command,
                startTime = System.currentTimeMillis(),
                status = ProcessStatus.STARTING
            )

            // Add to running processes
            _runningProcesses.value = _runningProcesses.value + process

            // Use RunnerTerminalService which respects sidebar/main panel setting
            logger.debug(LogCategory.TERMINAL, "Executing via RunnerTerminalService", mapOf("command" to command))
            RunnerTerminalService.openRunnerTerminal(config, windowId)

            // Update status to running
            updateProcessStatus(processId, ProcessStatus.RUNNING)

            return process
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to execute", error = e)
            return null
        }
    }

    /**
     * Build the full command including cd to working directory.
     */
    private fun buildFullCommand(config: RunConfiguration, debug: Boolean): String {
        val baseCommand = if (debug) {
            // Add debug flags for supported languages (future feature)
            config.command
        } else {
            config.command
        }

        // If working directory is specified and different from current, cd first
        return ShellUtils.buildCommandWithWorkingDirectory(baseCommand, config.workingDirectory)
    }

    /**
     * Stop a running process.
     * Note: Since we're using terminals, this sends a signal to request stop.
     * The actual stop happens when the terminal process exits.
     */
    actual suspend fun stop(processId: String) {
        val process = _runningProcesses.value.find { it.id == processId }
        if (process != null) {
            updateProcessStatus(processId, ProcessStatus.STOPPING)
            // Note: We can't directly stop terminal processes from here
            // The user needs to use Ctrl+C in the terminal
            // This is mainly for tracking state
            logger.debug(LogCategory.TERMINAL, "Stop requested for process", mapOf("configName" to process.configName))
        }
    }

    /**
     * Stop all running processes.
     */
    actual suspend fun stopAll() {
        _runningProcesses.value
            .filter { it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.STARTING }
            .forEach { process ->
                stop(process.id)
            }
    }

    /**
     * Mark a process as completed.
     */
    actual fun markCompleted(processId: String, failed: Boolean) {
        val status = if (failed) ProcessStatus.FAILED else ProcessStatus.STOPPED
        updateProcessStatus(processId, status)

        // Clean up old completed processes after a delay
        scope.launch {
            kotlinx.coroutines.delay(5000)
            _runningProcesses.value = _runningProcesses.value.filter {
                it.id != processId || it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.STARTING
            }
        }
    }

    private fun updateProcessStatus(processId: String, status: ProcessStatus) {
        _runningProcesses.value = _runningProcesses.value.map { process ->
            if (process.id == processId) {
                process.copy(status = status)
            } else {
                process
            }
        }
    }
}
