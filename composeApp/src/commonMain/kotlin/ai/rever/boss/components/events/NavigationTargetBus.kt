package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Navigation target event for cursor positioning after file opens.
 *
 * @property filePath Absolute path to the file
 * @property line Line number (1-based)
 * @property column Column number (1-based)
 * @property sourceWindowId The window that initiated the navigation (required for multi-window support)
 */
data class NavigationTargetEvent(
    val filePath: String,
    val line: Int,
    val column: Int,
    val sourceWindowId: String,
)

/**
 * Event bus for navigation targets.
 *
 * When a file is opened via Cmd+Click navigation, this bus is used to
 * communicate the target line/column to the editor so it can position
 * the cursor appropriately.
 *
 * Uses replay = 1 to ensure late subscribers (editor loading after file opens)
 * still receive the navigation target.
 */
object NavigationTargetBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _targets =
        MutableSharedFlow<NavigationTargetEvent>(
            replay = 1, // Replay last event for late subscribers
            extraBufferCapacity = 5,
        )
    val targets: SharedFlow<NavigationTargetEvent> = _targets.asSharedFlow()

    /**
     * Emit a navigation target for the given file.
     * The corresponding editor will position its cursor to the specified line/column.
     *
     * @param filePath Absolute path to the target file
     * @param line Target line (1-based), must be > 0
     * @param column Target column (1-based)
     * @param sourceWindowId The window that initiated the navigation (required for multi-window support)
     */
    suspend fun navigateTo(
        filePath: String,
        line: Int,
        column: Int,
        sourceWindowId: String,
    ) {
        if (line > 0) {
            val event = NavigationTargetEvent(filePath, line, column, sourceWindowId)
            _targets.emit(event)
            ipcBridge?.forward("NavigationTargetEvent", event, sourceWindowId)
        }
    }

    /**
     * Clear the replay cache. Call when navigation is complete to avoid
     * stale targets being replayed to new editors.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearCache() {
        _targets.resetReplayCache()
    }
}
