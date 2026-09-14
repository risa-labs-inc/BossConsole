package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Event emitted when a tab should be selected in a specific panel.
 *
 * @param targetWindowId The window that should handle this event
 * @param panelId The panel containing the tab
 * @param tabId The tab to select
 */
@Serializable
data class TabSelectEvent(
    val targetWindowId: String,
    val panelId: String,
    val tabId: String,
)

/**
 * Event bus for tab-related events.
 *
 * [selectTab] is the inverse of its twelve sibling buses: those are source-addressed
 * (handled by the originating window via `sourceWindowId`), while a tab selection is
 * destination-addressed - [TabSelectEvent.targetWindowId] names the window that should act,
 * and the originating window travels as the envelope's source window.
 *
 * Cross-process consequence: a kernel-mode subscriber must not set
 * `SubscribeRequest.sourceWindowId` to its own window id - the envelope's source is the
 * originating (searching) window, so the `EventBusServiceImpl` window filter would drop
 * exactly the selections addressed to the subscriber. Subscribe unfiltered and route on
 * the payload's `targetWindowId` instead.
 */
object TabEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _tabSelectEvents =
        MutableSharedFlow<TabSelectEvent>(
            replay = 0,
            extraBufferCapacity = 10,
        )
    val tabSelectEvents: SharedFlow<TabSelectEvent> = _tabSelectEvents.asSharedFlow()

    /**
     * Emit a tab select event.
     *
     * @param targetWindowId The window that should select the tab (the event's destination)
     * @param panelId The panel containing the tab
     * @param tabId The tab to select
     * @param sourceWindowId The window that initiated this selection (the dialog's own
     *   window). Forwarded as the envelope's source window so cross-process receivers can
     *   filter on the origin, like every other bus event.
     */
    suspend fun selectTab(
        targetWindowId: String,
        panelId: String,
        tabId: String,
        sourceWindowId: String,
    ) {
        val event = TabSelectEvent(targetWindowId, panelId, tabId)
        _tabSelectEvents.emit(event)
        ipcBridge?.forward("TabSelectEvent", event, sourceWindowId)
    }
}
