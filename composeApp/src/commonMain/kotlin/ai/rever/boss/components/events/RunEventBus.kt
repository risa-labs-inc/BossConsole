package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunExecuteEvent
import ai.rever.boss.plugin.run.RunStopEvent
import ai.rever.boss.plugin.run.RunScanEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Re-export event types via typealiases for backward compatibility
typealias RunExecuteEvent = ai.rever.boss.plugin.run.RunExecuteEvent
typealias RunStopEvent = ai.rever.boss.plugin.run.RunStopEvent
typealias RunScanEvent = ai.rever.boss.plugin.run.RunScanEvent

/**
 * Event bus for handling run-related events across all windows.
 *
 * Coordinates run configuration execution, stopping processes, and project scanning.
 * Each window's BossApp listens for events and the active window handles them.
 */
object RunEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _executeEvents = MutableSharedFlow<RunExecuteEvent>(
        replay = 0,
        extraBufferCapacity = 10
    )
    val executeEvents: SharedFlow<RunExecuteEvent> = _executeEvents.asSharedFlow()

    private val _stopEvents = MutableSharedFlow<RunStopEvent>(
        replay = 0,
        extraBufferCapacity = 10
    )
    val stopEvents: SharedFlow<RunStopEvent> = _stopEvents.asSharedFlow()

    private val _scanEvents = MutableSharedFlow<RunScanEvent>(
        replay = 0,
        extraBufferCapacity = 5
    )
    val scanEvents: SharedFlow<RunScanEvent> = _scanEvents.asSharedFlow()

    /**
     * Emit a run execute event.
     *
     * @param configuration The run configuration to execute
     * @param debug Whether to run in debug mode
     * @param sourceWindowId The window that initiated the run (required for multi-window support)
     */
    suspend fun execute(configuration: RunConfiguration, debug: Boolean = false, sourceWindowId: String) {
        val event = RunExecuteEvent(configuration, debug, sourceWindowId)
        _executeEvents.emit(event)
        ipcBridge?.forward("RunExecuteEvent", event, sourceWindowId)
    }

    /**
     * Emit a stop event.
     *
     * @param configId Optional config ID to stop, null means stop all
     * @param sourceWindowId The window that initiated the stop (required for multi-window support)
     */
    suspend fun stop(configId: String? = null, sourceWindowId: String) {
        val event = RunStopEvent(configId, sourceWindowId)
        _stopEvents.emit(event)
        ipcBridge?.forward("RunStopEvent", event, sourceWindowId)
    }

    /**
     * Emit a scan event to discover run configurations.
     *
     * @param projectPath The project path to scan
     * @param sourceWindowId The window that initiated the scan (required for multi-window support)
     */
    suspend fun scanProject(projectPath: String, sourceWindowId: String) {
        val event = RunScanEvent(projectPath, sourceWindowId)
        _scanEvents.emit(event)
        ipcBridge?.forward("RunScanEvent", event, sourceWindowId)
    }
}
