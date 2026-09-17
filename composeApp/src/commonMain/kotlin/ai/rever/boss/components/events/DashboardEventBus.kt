package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import ai.rever.boss.plugin.api.TabTypeId
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
    val requestedType: TabTypeId? = null,
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

/**
 * Apply a whole layout to this window, named by [workspaceId].
 *
 * An id, not the workspace: the handler resolves it against `WorkspaceManager.workspaces`,
 * which is what makes the home screen's cards, the top bar's menu and the app menu act on
 * one list. The event this replaced carried a `SplitTemplate` by value, from a parallel
 * list of the same layouts that only the home screen read.
 */
data class DashboardApplyWorkspaceEvent(
    val workspaceId: String,
    val sourceWindowId: String,
)

/**
 * Open a tab by the [ai.rever.boss.plugin.api.TabTypeId] a plugin registered it under.
 *
 * The verb the home screen's tool grid needs and the bus previously lacked. Everything else
 * here names a fixed action; this one names a *registration*, so the grid can offer whatever
 * is installed - Arcade, Flow, Jupyter - without the host holding a list of them.
 *
 * [input] is what the New Tab dialog would have collected (a URL, a file path). A type whose
 * `NewTabSpec` needs no input passes the empty string, which is why this is not nullable.
 */
data class DashboardOpenTabTypeEvent(
    val typeId: String,
    /**
     * The `pluginId` half of the [ai.rever.boss.plugin.api.TabTypeId], or blank if unknown.
     *
     * Carried separately because `TabTypeId` is a data class over **both** fields, so a handler
     * cannot rebuild the registry key from [typeId] alone - `TabTypeId("arcade")` defaults
     * `pluginId` to "" and does not equal the `TabTypeId("arcade", "…dynamic.arcade")` Arcade
     * registered, which silently missed the lookup and dropped the event. The handler matches on
     * these two strings instead of reconstructing a key, the same way the panel handlers compare
     * `panelId`/`pluginId` field by field.
     */
    val typePluginId: String,
    val input: String,
    val sourceWindowId: String,
)

/**
 * Open the settings window.
 *
 * Added with the home screen. Settings used to arrive as an `onShowSettings: (() -> Unit)?`
 * threaded down four layers from `BossAppScaffold`, and the browser mount passed it as null, so
 * the Settings card there rendered normally and did nothing at all when clicked.
 */
data class DashboardShowSettingsEvent(
    val sourceWindowId: String,
)

/** Open the global search dialog, the same surface the `search.open` shortcut opens. */
data class DashboardOpenSearchEvent(
    val sourceWindowId: String,
)

/**
 * Event bus for Dashboard actions triggered from Fluck tabs.
 * When a Fluck tab shows Dashboard (empty URL), actions are emitted here
 * and handled by BossApp to perform the actual operations.
 *
 * Issue #506: All events include sourceWindowId for multi-window filtering.
 *
 * **This is the home screen's only way to act.** `HomeScreen` takes no action callbacks and
 * emits here instead. That is not stylistic: the screen has two mount points (the empty split
 * panel and `DashboardContentProviderImpl` for a browser's about:blank), and while it took 12
 * callbacks the second one passed 11 empty lambdas, so most of the screen was inert there.
 * Emitting on a window-scoped bus means one code path serves both, and kernel mode is carried
 * by [ipcBridge] for free. `HomeActionRoutingTest` pins it.
 */
// One emit function per event is the whole design of a bus; the count is a measure of how many
// actions the home screen has, not of this object doing too much. `HomeActionRoutingTest` asserts
// the stronger property that matters here: every flow has exactly one matching emitter.
@Suppress("TooManyFunctions")
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

    // Workspace layouts
    private val _applyWorkspaceEvents = MutableSharedFlow<DashboardApplyWorkspaceEvent>(extraBufferCapacity = 10)
    val applyWorkspaceEvents: SharedFlow<DashboardApplyWorkspaceEvent> = _applyWorkspaceEvents.asSharedFlow()

    // Open a plugin-registered tab type by id
    private val _openTabTypeEvents = MutableSharedFlow<DashboardOpenTabTypeEvent>(extraBufferCapacity = 10)
    val openTabTypeEvents: SharedFlow<DashboardOpenTabTypeEvent> = _openTabTypeEvents.asSharedFlow()

    // Settings window
    private val _showSettingsEvents = MutableSharedFlow<DashboardShowSettingsEvent>(extraBufferCapacity = 10)
    val showSettingsEvents: SharedFlow<DashboardShowSettingsEvent> = _showSettingsEvents.asSharedFlow()

    // Global search dialog
    private val _openSearchEvents = MutableSharedFlow<DashboardOpenSearchEvent>(extraBufferCapacity = 10)
    val openSearchEvents: SharedFlow<DashboardOpenSearchEvent> = _openSearchEvents.asSharedFlow()

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

    suspend fun newTab(
        sourceWindowId: String,
        requestedType: TabTypeId? = null,
    ) {
        val event = DashboardNewTabEvent(sourceWindowId, requestedType)
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

    suspend fun applyWorkspace(
        workspaceId: String,
        sourceWindowId: String,
    ) {
        val event = DashboardApplyWorkspaceEvent(workspaceId, sourceWindowId)
        _applyWorkspaceEvents.emit(event)
        ipcBridge?.forward("DashboardApplyWorkspaceEvent", event, sourceWindowId)
    }

    suspend fun openTabType(
        typeId: String,
        typePluginId: String,
        input: String,
        sourceWindowId: String,
    ) {
        val event = DashboardOpenTabTypeEvent(typeId, typePluginId, input, sourceWindowId)
        _openTabTypeEvents.emit(event)
        ipcBridge?.forward("DashboardOpenTabTypeEvent", event, sourceWindowId)
    }

    suspend fun showSettings(sourceWindowId: String) {
        val event = DashboardShowSettingsEvent(sourceWindowId)
        _showSettingsEvents.emit(event)
        ipcBridge?.forward("DashboardShowSettingsEvent", event, sourceWindowId)
    }

    suspend fun openSearch(sourceWindowId: String) {
        val event = DashboardOpenSearchEvent(sourceWindowId)
        _openSearchEvents.emit(event)
        ipcBridge?.forward("DashboardOpenSearchEvent", event, sourceWindowId)
    }
}
