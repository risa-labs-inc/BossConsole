package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event emitted when a link is clicked in a terminal.
 * Contains the URL to open and the source terminal ID for panel detection.
 */
data class TerminalLinkClickEvent(
    val url: String,
    /** Terminal tab ID where the link was clicked (used to find source panel) */
    val sourceTerminalId: String? = null,
    /** Window ID where the link was clicked (used to filter events to correct window) */
    val sourceWindowId: String? = null
)

/**
 * Event bus for terminal link clicks.
 *
 * BossApp subscribes to show the link open dialog when the user's
 * preference is ALWAYS_ASK, or to auto-open with their saved preference.
 *
 * Issue #346: Terminal link click prompt with remember preference
 */
object TerminalLinkEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    /**
     * SharedFlow for terminal link click events.
     *
     * Buffer sizing rationale:
     * - replay = 0: New subscribers shouldn't see old events (user already dismissed dialog)
     * - extraBufferCapacity = 10: Provides headroom for rapid link clicks while dialog is shown.
     *   This is a conservative buffer; in practice, users rarely click more than a few links
     *   before the first dialog appears (~16ms compose frame time). If buffer overflows,
     *   emit() suspends until space is available (no events lost, just delayed).
     */
    private val _linkClickEvents = MutableSharedFlow<TerminalLinkClickEvent>(
        replay = 0,
        extraBufferCapacity = 10
    )
    val linkClickEvents: SharedFlow<TerminalLinkClickEvent> = _linkClickEvents.asSharedFlow()

    /**
     * Emit a terminal link click event.
     *
     * @param url The URL that was clicked in the terminal
     * @param sourceTerminalId Optional terminal tab ID (for detecting source panel in splits)
     * @param sourceWindowId Optional window ID (for filtering events to correct window)
     */
    suspend fun emitLinkClick(url: String, sourceTerminalId: String? = null, sourceWindowId: String? = null) {
        val event = TerminalLinkClickEvent(url, sourceTerminalId, sourceWindowId)
        _linkClickEvents.emit(event)
        sourceWindowId?.let { ipcBridge?.forward("TerminalLinkClickEvent", event, it) }
    }

    /**
     * Non-suspend version for callers that can't use coroutines (e.g., plugin reflection).
     * Uses tryEmit which succeeds immediately when buffer has space (extraBufferCapacity = 10).
     */
    fun tryEmitLinkClick(url: String, sourceTerminalId: String? = null, sourceWindowId: String? = null): Boolean {
        return _linkClickEvents.tryEmit(TerminalLinkClickEvent(url, sourceTerminalId, sourceWindowId))
    }
}
