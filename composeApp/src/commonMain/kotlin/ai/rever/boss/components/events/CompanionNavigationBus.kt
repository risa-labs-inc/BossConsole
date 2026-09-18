package ai.rever.boss.components.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class CompanionNavigationEvent(
    val windowId: String,
    val tabId: String,
)

object CompanionNavigationBus {
    private val _events =
        MutableSharedFlow<CompanionNavigationEvent>(
            replay = 0,
            extraBufferCapacity = 16,
        )

    val events: SharedFlow<CompanionNavigationEvent> = _events.asSharedFlow()

    suspend fun navigate(
        windowId: String,
        tabId: String,
    ) {
        _events.emit(
            CompanionNavigationEvent(
                windowId = windowId,
                tabId = tabId,
            ),
        )
    }

    fun tryNavigate(
        windowId: String,
        tabId: String,
    ): Boolean =
        _events.tryEmit(
            CompanionNavigationEvent(
                windowId = windowId,
                tabId = tabId,
            ),
        )
}
