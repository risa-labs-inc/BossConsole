package ai.rever.boss.plugin.browser

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Native page presses never reach AWT; scope them to the browser's current host window. */
internal object BrowserPagePressEvents {
    private val events =
        MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val presses = events.asSharedFlow()

    fun emit(windowId: String) {
        events.tryEmit(windowId)
    }
}
