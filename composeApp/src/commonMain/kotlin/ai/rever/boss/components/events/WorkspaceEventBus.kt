package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event emitted when a workspace should be loaded.
 *
 * @property workspacePath Path to the workspace file
 * @property sourceWindowId The window that should load the workspace (required for multi-window support)
 */
data class WorkspaceLoadEvent(
    val workspacePath: String,
    val sourceWindowId: String
)

/**
 * Event bus for workspace-related events.
 *
 * Issue #506: Added sourceWindowId for multi-window support.
 */
object WorkspaceEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _workspaceLoadEvents = MutableSharedFlow<WorkspaceLoadEvent>(
        replay = 0,  // Don't replay past events to new subscribers (new windows)
        extraBufferCapacity = 10  // Buffer up to 10 events if collector not ready yet
    )
    val workspaceLoadEvents: SharedFlow<WorkspaceLoadEvent> = _workspaceLoadEvents.asSharedFlow()

    /**
     * Emit a workspace load event.
     *
     * @param workspacePath Path to the workspace file
     * @param sourceWindowId The window that should load the workspace (required for multi-window support)
     */
    suspend fun loadWorkspace(workspacePath: String, sourceWindowId: String) {
        val event = WorkspaceLoadEvent(workspacePath, sourceWindowId)
        _workspaceLoadEvents.emit(event)
        ipcBridge?.forward("WorkspaceLoadEvent", event, sourceWindowId)
    }
}
