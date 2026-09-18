package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class RunProcessStatus {
    STARTED,
    WAITING_FOR_INPUT,
    COMPLETED,
    FAILED,
    STOPPED,
}

data class RunProcessEvent(
    val processId: String,
    val configId: String,
    val configName: String,
    val windowId: String,
    val terminalId: String?,
    val status: RunProcessStatus,
    val exitCode: Int? = null,
)

object RunProcessEventBus {
    @Volatile
    var ipcBridge: IpcEventBridge? = null

    private val _events =
        MutableSharedFlow<RunProcessEvent>(
            replay = 0,
            extraBufferCapacity = 32,
        )

    val events: SharedFlow<RunProcessEvent> = _events.asSharedFlow()

    suspend fun emit(event: RunProcessEvent) {
        _events.emit(event)
        ipcBridge?.forward("RunProcessEvent", event, event.windowId)
    }
}
