package ai.rever.boss.run

import ai.rever.boss.components.events.RunProcessEvent
import ai.rever.boss.components.events.RunProcessEventBus
import ai.rever.boss.components.events.RunProcessStatus
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

    private const val MAX_RETAINED_FINISHED_PROCESSES = 64

    private val _runningProcesses = MutableStateFlow<List<RunningProcess>>(emptyList())
    actual val runningProcesses: StateFlow<List<RunningProcess>> = _runningProcesses.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    actual val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    init {
        // Update isRunning based on running processes
        scope.launch {
            _runningProcesses.collect { processes ->
                _isRunning.value =
                    processes.any {
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
    actual suspend fun execute(
        config: RunConfiguration,
        debug: Boolean,
        windowId: String,
    ): RunningProcess? {
        try {
            val processId = UUID.randomUUID().toString()

            // Build the full command (for display/tracking only)
            val command = buildFullCommand(config, debug)

            // Create running process entry
            val process =
                RunningProcess(
                    id = processId,
                    configId = config.id,
                    configName = config.name,
                    command = command,
                    startTime = System.currentTimeMillis(),
                    status = ProcessStatus.STARTING,
                    windowId = windowId,
                )

            // Add the process while keeping finished history bounded.
            addProcess(process)

            // Use RunnerTerminalService which respects sidebar/main panel setting
            logger.debug(LogCategory.TERMINAL, "Executing via RunnerTerminalService", mapOf("command" to command))
            val terminalId =
                RunnerTerminalService.openRunnerTerminal(
                    config = config,
                    windowId = windowId,
                    processId = processId,
                )

            _runningProcesses.value =
                _runningProcesses.value.map {
                    if (it.id == processId) {
                        it.copy(terminalId = terminalId)
                    } else {
                        it
                    }
                }

            // Update status to running
            updateProcessStatus(processId, ProcessStatus.RUNNING)

            RunProcessEventBus.emit(
                RunProcessEvent(
                    processId = processId,
                    configId = config.id,
                    configName = config.name,
                    windowId = windowId,
                    terminalId = terminalId,
                    status = RunProcessStatus.STARTED,
                ),
            )

            return process
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to execute", error = e)
            return null
        }
    }

    /**
     * Re-run a configuration with a fresh execution identity.
     */
    actual suspend fun rerun(
        config: RunConfiguration,
        windowId: String,
    ): RunningProcess? {
        try {
            val processId = UUID.randomUUID().toString()
            val command = buildFullCommand(config, debug = false)

            val process =
                RunningProcess(
                    id = processId,
                    configId = config.id,
                    configName = config.name,
                    command = command,
                    startTime = System.currentTimeMillis(),
                    status = ProcessStatus.STARTING,
                    windowId = windowId,
                )

            _runningProcesses.value = _runningProcesses.value + process

            logger.debug(
                LogCategory.TERMINAL,
                "Re-running via RunnerTerminalService",
                mapOf("command" to command),
            )

            val terminalId =
                RunnerTerminalService.rerunRunner(
                    config = config,
                    windowId = windowId,
                    processId = processId,
                )

            _runningProcesses.value =
                _runningProcesses.value.map {
                    if (it.id == processId) {
                        it.copy(terminalId = terminalId)
                    } else {
                        it
                    }
                }

            updateProcessStatus(processId, ProcessStatus.RUNNING)

            RunProcessEventBus.emit(
                RunProcessEvent(
                    processId = processId,
                    configId = config.id,
                    configName = config.name,
                    windowId = windowId,
                    terminalId = terminalId,
                    status = RunProcessStatus.STARTED,
                ),
            )

            return _runningProcesses.value.firstOrNull { it.id == processId }
        } catch (e: Exception) {
            logger.warn(
                LogCategory.TERMINAL,
                "Failed to rerun",
                error = e,
            )
            return null
        }
    }

    /**
     * Build the full command including cd to working directory.
     */
    private fun buildFullCommand(
        config: RunConfiguration,
        debug: Boolean,
    ): String {
        val baseCommand =
            if (debug) {
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
        val process = _runningProcesses.value.find { it.id == processId } ?: return

        val terminalId = process.terminalId
        if (terminalId == null) {
            logger.debug(
                LogCategory.TERMINAL,
                "Stop requested but process has no terminal identity",
                mapOf("processId" to processId, "configName" to process.configName),
            )
            return
        }

        updateProcessStatus(processId, ProcessStatus.STOPPING)

        val interrupted =
            ai.rever.boss.services.terminal.TerminalAPIAccess.sendInterrupt(
                windowId = process.windowId,
                terminalId = terminalId,
            )

        logger.debug(
            LogCategory.TERMINAL,
            "Stop requested for exact process terminal",
            mapOf(
                "processId" to processId,
                "configName" to process.configName,
                "windowId" to process.windowId,
                "terminalId" to terminalId,
                "interruptSent" to interrupted,
            ),
        )
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
    actual fun markCompleted(
        processId: String,
        failed: Boolean,
    ) {
        val status = if (failed) ProcessStatus.FAILED else ProcessStatus.STOPPED
        updateProcessStatus(processId, status)
    }

    private fun addProcess(process: RunningProcess) {
        _runningProcesses.value = _runningProcesses.value + process
        trimFinishedProcesses()
    }

    private fun trimFinishedProcesses() {
        val processes = _runningProcesses.value
        val finished =
            processes.filter {
                it.status == ProcessStatus.FAILED ||
                    it.status == ProcessStatus.STOPPED
            }

        if (finished.size <= MAX_RETAINED_FINISHED_PROCESSES) {
            return
        }

        val removeCount = finished.size - MAX_RETAINED_FINISHED_PROCESSES
        val idsToRemove = finished.take(removeCount).map { it.id }.toSet()

        _runningProcesses.value =
            processes.filterNot { it.id in idsToRemove }
    }

    private fun updateProcessStatus(
        processId: String,
        status: ProcessStatus,
    ) {
        _runningProcesses.value =
            _runningProcesses.value.map { process ->
                if (process.id == processId) {
                    process.copy(status = status)
                } else {
                    process
                }
            }
        trimFinishedProcesses()
    }
}
