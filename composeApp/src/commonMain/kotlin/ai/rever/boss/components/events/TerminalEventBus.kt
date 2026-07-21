package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event emitted when a terminal tab should be opened
 *
 * @property command Optional initial command to run in the terminal
 * @property sourceWindowId The window that initiated this event (required for multi-window support)
 * @property workingDirectory Optional working directory for the terminal
 */
data class TerminalOpenEvent(
    val command: String?,
    val sourceWindowId: String,
    val workingDirectory: String? = null
)

/**
 * Event bus for handling terminal open requests across all windows
 *
 * When a CLI command requests a terminal (e.g., `boss terminal` or `boss terminal -c "ls"`),
 * this event bus coordinates the request. Each window's BossApp listens for events and the
 * active window handles the request by creating a new terminal tab.
 *
 * Similar to URLEventBus and FileEventBus but for terminal tabs.
 */
object TerminalEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _terminalOpenEvents = MutableSharedFlow<TerminalOpenEvent>(
        replay = 0,  // Don't replay past events to new subscribers (new windows)
        extraBufferCapacity = 10  // Buffer up to 10 events if collector not ready yet
    )
    val terminalOpenEvents: SharedFlow<TerminalOpenEvent> = _terminalOpenEvents.asSharedFlow()

    /**
     * Emit a terminal open event
     *
     * Only the window matching sourceWindowId will handle the event.
     *
     * @param command Optional command to run in the terminal
     * @param sourceWindowId The window that initiated this event (required for multi-window support)
     * @param workingDirectory Optional working directory for the terminal
     */
    suspend fun openTerminal(command: String? = null, sourceWindowId: String, workingDirectory: String? = null) {
        val event = TerminalOpenEvent(command, sourceWindowId, workingDirectory)
        _terminalOpenEvents.emit(event)
        ipcBridge?.forward("TerminalOpenEvent", event, sourceWindowId)
    }
}
