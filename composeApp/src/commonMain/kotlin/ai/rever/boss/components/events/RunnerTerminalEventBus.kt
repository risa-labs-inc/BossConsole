package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import ai.rever.boss.plugin.run.RunnerTerminalCloseEvent
import ai.rever.boss.plugin.run.RunnerTerminalOpenEvent
import ai.rever.boss.plugin.run.RunnerTerminalStopEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Re-export event types via typealiases for backward compatibility
typealias RunnerTerminalOpenEvent = ai.rever.boss.plugin.run.RunnerTerminalOpenEvent
typealias RunnerTerminalStopEvent = ai.rever.boss.plugin.run.RunnerTerminalStopEvent
typealias RunnerTerminalCloseEvent = ai.rever.boss.plugin.run.RunnerTerminalCloseEvent

/**
 * Event bus for runner terminal operations.
 *
 * Issue #347: Runner should open in terminal sidebar panel with run/stop state management
 */
object RunnerTerminalEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _openEvents =
        MutableSharedFlow<RunnerTerminalOpenEvent>(
            replay = 0,
            extraBufferCapacity = 10,
        )
    val openEvents: SharedFlow<RunnerTerminalOpenEvent> = _openEvents.asSharedFlow()

    private val _stopEvents =
        MutableSharedFlow<RunnerTerminalStopEvent>(
            replay = 0,
            extraBufferCapacity = 10,
        )
    val stopEvents: SharedFlow<RunnerTerminalStopEvent> = _stopEvents.asSharedFlow()

    private val _closeEvents =
        MutableSharedFlow<RunnerTerminalCloseEvent>(
            replay = 0,
            extraBufferCapacity = 10,
        )
    val closeEvents: SharedFlow<RunnerTerminalCloseEvent> = _closeEvents.asSharedFlow()

    data class OpenRequest(
        val terminalId: String,
        val command: String,
        val configId: String,
        val configName: String,
        val workingDirectory: String?,
        val isRerun: Boolean,
        val sourceWindowId: String,
        val processId: String? = null,
    )

    /**
     * Emit event to open a runner terminal.
     */
    suspend fun openRunnerTerminal(request: OpenRequest) {
        val event =
            RunnerTerminalOpenEvent(
                terminalId = request.terminalId,
                command = request.command,
                configId = request.configId,
                configName = request.configName,
                workingDirectory = request.workingDirectory,
                isRerun = request.isRerun,
                sourceWindowId = request.sourceWindowId,
                processId = request.processId,
            )
        _openEvents.emit(event)
        ipcBridge?.forward("RunnerTerminalOpenEvent", event, request.sourceWindowId)
    }

    /**
     * Emit event to stop a runner terminal (Ctrl+C request).
     * @param sourceWindowId Window that initiated the stop (required for multi-window support)
     */
    suspend fun stopRunnerTerminal(
        terminalId: String,
        configId: String,
        sourceWindowId: String,
    ) {
        val event = RunnerTerminalStopEvent(terminalId, configId, sourceWindowId)
        _stopEvents.emit(event)
        ipcBridge?.forward("RunnerTerminalStopEvent", event, sourceWindowId)
    }

    /**
     * Emit event to close a runner terminal tab.
     * @param sourceWindowId Window that initiated the close (required for multi-window support)
     */
    suspend fun closeRunnerTerminal(
        terminalId: String,
        sourceWindowId: String,
    ) {
        val event = RunnerTerminalCloseEvent(terminalId, sourceWindowId)
        _closeEvents.emit(event)
        ipcBridge?.forward("RunnerTerminalCloseEvent", event, sourceWindowId)
    }
}
