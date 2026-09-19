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
 * @property requiresConfirmation True when the request came from somewhere other than the
 *   operator's own invocation of BOSS (see `DeepLinkOrigin`), so any terminal commands the
 *   Space carries must be shown to the operator before they reach a shell. Defaults to false
 *   so a caller that is the operator clicking something stays direct.
 */
data class WorkspaceLoadEvent(
    val workspacePath: String,
    val sourceWindowId: String,
    val requiresConfirmation: Boolean = false,
)

/**
 * Event emitted when a workspace tab should be switched.
 *
 * @property workspaceName Name or ID of the workspace tab to switch to
 * @property sourceWindowId The window that should switch workspace
 */
data class WorkspaceSwitchEvent(
    val workspaceName: String,
    val sourceWindowId: String,
)

/**
 * Event bus for workspace-related events.
 *
 * Issue #506: Added sourceWindowId for multi-window support.
 */
object WorkspaceEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _workspaceLoadEvents =
        MutableSharedFlow<WorkspaceLoadEvent>(
            replay = 0, // Don't replay past events to new subscribers (new windows)
            extraBufferCapacity = 10, // Buffer up to 10 events if collector not ready yet
        )
    val workspaceLoadEvents: SharedFlow<WorkspaceLoadEvent> = _workspaceLoadEvents.asSharedFlow()

    private val _workspaceSwitchEvents =
        MutableSharedFlow<WorkspaceSwitchEvent>(
            replay = 0,
            extraBufferCapacity = 10,
        )
    val workspaceSwitchEvents: SharedFlow<WorkspaceSwitchEvent> = _workspaceSwitchEvents.asSharedFlow()

    /**
     * Emit a workspace load event.
     *
     * @param workspacePath Path to the workspace file
     * @param sourceWindowId The window that should load the workspace (required for multi-window support)
     * @param requiresConfirmation See [WorkspaceLoadEvent.requiresConfirmation]
     */
    suspend fun loadWorkspace(
        workspacePath: String,
        sourceWindowId: String,
        requiresConfirmation: Boolean = false,
    ) {
        val event = WorkspaceLoadEvent(workspacePath, sourceWindowId, requiresConfirmation)
        _workspaceLoadEvents.emit(event)
        ipcBridge?.forward("WorkspaceLoadEvent", event, sourceWindowId)
    }

    /**
     * Emit a workspace switch event.
     *
     * @param workspaceName Name or ID of the workspace
     * @param sourceWindowId The window that should switch workspace
     */
    suspend fun switchWorkspace(
        workspaceName: String,
        sourceWindowId: String,
    ) {
        val event = WorkspaceSwitchEvent(workspaceName, sourceWindowId)
        _workspaceSwitchEvents.emit(event)
        ipcBridge?.forward("WorkspaceSwitchEvent", event, sourceWindowId)
    }
}
