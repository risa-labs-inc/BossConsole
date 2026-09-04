package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event emitted when an HTML file open request needs user clarification (ALWAYS_ASK mode).
 */
data class HtmlFileOpenPromptEvent(
    val filePath: String,
    val fileName: String,
    /** Window ID where the open was requested (used to filter events to correct window) */
    val sourceWindowId: String? = null,
)

/**
 * Event bus for HTML file open prompts.
 * BossApp listens to show the HTML open dialog when preference is ALWAYS_ASK.
 */
object HtmlFileEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _openPromptEvents =
        MutableSharedFlow<HtmlFileOpenPromptEvent>(
            replay = 0,
            extraBufferCapacity = 10,
        )
    val openPromptEvents: SharedFlow<HtmlFileOpenPromptEvent> = _openPromptEvents.asSharedFlow()

    suspend fun emitOpenPrompt(
        filePath: String,
        fileName: String,
        sourceWindowId: String? = null,
    ) {
        val event = HtmlFileOpenPromptEvent(filePath, fileName, sourceWindowId)
        _openPromptEvents.emit(event)
        sourceWindowId?.let { ipcBridge?.forward("HtmlFileOpenPromptEvent", event, it) }
    }

    fun tryEmitOpenPrompt(
        filePath: String,
        fileName: String,
        sourceWindowId: String? = null,
    ): Boolean = _openPromptEvents.tryEmit(HtmlFileOpenPromptEvent(filePath, fileName, sourceWindowId))
}
