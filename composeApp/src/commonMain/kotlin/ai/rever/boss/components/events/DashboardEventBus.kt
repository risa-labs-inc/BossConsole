package ai.rever.boss.components.events

import ai.rever.boss.dashboard.SplitTemplate
import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Event data classes with sourceWindowId for multi-window support (Issue #506)
data class DashboardOpenFileEvent(
    val path: String,
    val sourceWindowId: String,
)

data class DashboardOpenUrlEvent(
    val url: String,
    val sourceWindowId: String,
)

data class DashboardNewTabEvent(
    val sourceWindowId: String,
)

data class DashboardNewTerminalEvent(
    val sourceWindowId: String,
)

data class DashboardShowProjectDialogEvent(
    val sourceWindowId: String,
)

data class DashboardShowFileDialogEvent(
    val sourceWindowId: String,
)

data class DashboardShowNewProjectEvent(
    val sourceWindowId: String,
)

data class DashboardApplySplitTemplateEvent(
    val template: SplitTemplate,
    val sourceWindowId: String,
)

data class DashboardActivatePluginEvent(
    val pluginId: String,
    val sourceWindowId: String,
)

/**
 * Event bus for Dashboard actions triggered from Fluck tabs.
 * When a Fluck tab shows Dashboard (empty URL), actions are emitted here
 * and handled by BossApp to perform the actual operations.
 *
 * Issue #506: All events include sourceWindowId for multi-window filtering.
 */
object DashboardEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    // File operations
    private val _openFileEvents = MutableSharedFlow<DashboardOpenFileEvent>(extraBufferCapacity = 10)
    val openFileEvents: SharedFlow<DashboardOpenFileEvent> = _openFileEvents.asSharedFlow()

    // URL navigation (opens in new tab, not current Fluck tab)
    private val _openUrlInNewTabEvents = MutableSharedFlow<DashboardOpenUrlEvent>(extraBufferCapacity = 10)
    val openUrlInNewTabEvents: SharedFlow<DashboardOpenUrlEvent> = _openUrlInNewTabEvents.asSharedFlow()

    // Tab operations
    private val _newTabEvents = MutableSharedFlow<DashboardNewTabEvent>(extraBufferCapacity = 10)
    val newTabEvents: SharedFlow<DashboardNewTabEvent> = _newTabEvents.asSharedFlow()

    private val _newTerminalEvents = MutableSharedFlow<DashboardNewTerminalEvent>(extraBufferCapacity = 10)
    val newTerminalEvents: SharedFlow<DashboardNewTerminalEvent> = _newTerminalEvents.asSharedFlow()

    // Dialog triggers
    private val _showProjectDialogEvents = MutableSharedFlow<DashboardShowProjectDialogEvent>(extraBufferCapacity = 10)
    val showProjectDialogEvents: SharedFlow<DashboardShowProjectDialogEvent> = _showProjectDialogEvents.asSharedFlow()

    private val _showFileDialogEvents = MutableSharedFlow<DashboardShowFileDialogEvent>(extraBufferCapacity = 10)
    val showFileDialogEvents: SharedFlow<DashboardShowFileDialogEvent> = _showFileDialogEvents.asSharedFlow()

    private val _showNewProjectEvents = MutableSharedFlow<DashboardShowNewProjectEvent>(extraBufferCapacity = 10)
    val showNewProjectEvents: SharedFlow<DashboardShowNewProjectEvent> = _showNewProjectEvents.asSharedFlow()

    // Split templates
    private val _applySplitTemplateEvents = MutableSharedFlow<DashboardApplySplitTemplateEvent>(extraBufferCapacity = 10)
    val applySplitTemplateEvents: SharedFlow<DashboardApplySplitTemplateEvent> = _applySplitTemplateEvents.asSharedFlow()

    // Plugin activation
    private val _activatePluginEvents = MutableSharedFlow<DashboardActivatePluginEvent>(extraBufferCapacity = 10)
    val activatePluginEvents: SharedFlow<DashboardActivatePluginEvent> = _activatePluginEvents.asSharedFlow()

    // Emit functions with sourceWindowId parameter (required for multi-window support)
    suspend fun openFile(
        path: String,
        sourceWindowId: String,
    ) {
        val event = DashboardOpenFileEvent(path, sourceWindowId)
        _openFileEvents.emit(event)
        ipcBridge?.forward("DashboardOpenFileEvent", event, sourceWindowId)
    }

    suspend fun openUrlInNewTab(
        url: String,
        sourceWindowId: String,
    ) {
        val event = DashboardOpenUrlEvent(url, sourceWindowId)
        _openUrlInNewTabEvents.emit(event)
        ipcBridge?.forward("DashboardOpenUrlEvent", event, sourceWindowId)
    }

    suspend fun newTab(sourceWindowId: String) {
        val event = DashboardNewTabEvent(sourceWindowId)
        _newTabEvents.emit(event)
        ipcBridge?.forward("DashboardNewTabEvent", event, sourceWindowId)
    }

    suspend fun newTerminal(sourceWindowId: String) {
        val event = DashboardNewTerminalEvent(sourceWindowId)
        _newTerminalEvents.emit(event)
        ipcBridge?.forward("DashboardNewTerminalEvent", event, sourceWindowId)
    }

    suspend fun showProjectDialog(sourceWindowId: String) {
        val event = DashboardShowProjectDialogEvent(sourceWindowId)
        _showProjectDialogEvents.emit(event)
        ipcBridge?.forward("DashboardShowProjectDialogEvent", event, sourceWindowId)
    }

    suspend fun showFileDialog(sourceWindowId: String) {
        val event = DashboardShowFileDialogEvent(sourceWindowId)
        _showFileDialogEvents.emit(event)
        ipcBridge?.forward("DashboardShowFileDialogEvent", event, sourceWindowId)
    }

    suspend fun showNewProject(sourceWindowId: String) {
        val event = DashboardShowNewProjectEvent(sourceWindowId)
        _showNewProjectEvents.emit(event)
        ipcBridge?.forward("DashboardShowNewProjectEvent", event, sourceWindowId)
    }

    suspend fun applySplitTemplate(
        template: SplitTemplate,
        sourceWindowId: String,
    ) {
        val event = DashboardApplySplitTemplateEvent(template, sourceWindowId)
        _applySplitTemplateEvents.emit(event)
        ipcBridge?.forward("DashboardApplySplitTemplateEvent", event, sourceWindowId)
    }

    suspend fun activatePlugin(
        pluginId: String,
        sourceWindowId: String,
    ) {
        val event = DashboardActivatePluginEvent(pluginId, sourceWindowId)
        _activatePluginEvents.emit(event)
        ipcBridge?.forward("DashboardActivatePluginEvent", event, sourceWindowId)
    }
}
