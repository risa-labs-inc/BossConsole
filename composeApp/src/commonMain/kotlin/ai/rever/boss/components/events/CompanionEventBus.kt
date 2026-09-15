package ai.rever.boss.components.events

import ai.rever.boss.companion.CompanionEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object CompanionEventBus {
    private val _events =
        MutableSharedFlow<CompanionEvent>(
            replay = 0,
            extraBufferCapacity = 32,
        )

    val events: SharedFlow<CompanionEvent> = _events.asSharedFlow()

    suspend fun emit(event: CompanionEvent) {
        _events.emit(event)
    }

    fun tryEmit(event: CompanionEvent): Boolean = _events.tryEmit(event)
}
