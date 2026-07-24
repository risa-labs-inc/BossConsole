package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.plugin.api.NavigationTargetEvent
import ai.rever.boss.plugin.api.NavigationTargetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Implementation of NavigationTargetProvider that wraps the host's NavigationTargetBus.
 *
 * This allows dynamic plugins to listen for navigation target events
 * and position their editor cursors appropriately.
 */
object NavigationTargetProviderImpl : NavigationTargetProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _targets =
        MutableSharedFlow<NavigationTargetEvent>(
            replay = 1,
            extraBufferCapacity = 5,
        )
    override val targets: SharedFlow<NavigationTargetEvent> = _targets.asSharedFlow()

    init {
        println("[HOST-DEBUG] NavigationTargetProviderImpl: init - starting collector")
        // Forward events from NavigationTargetBus to our flow
        scope.launch {
            println("[HOST-DEBUG] NavigationTargetProviderImpl: collector started")
            NavigationTargetBus.targets.collect { event ->
                println(
                    "[HOST-DEBUG] NavigationTargetProviderImpl: received event from NavigationTargetBus: ${event.filePath}:${event.line}:${event.column}",
                )
                _targets.emit(
                    NavigationTargetEvent(
                        filePath = event.filePath,
                        line = event.line,
                        column = event.column,
                        sourceWindowId = event.sourceWindowId,
                    ),
                )
                println("[HOST-DEBUG] NavigationTargetProviderImpl: emitted to plugin targets")
            }
        }
    }

    override fun clearCache() {
        NavigationTargetBus.clearCache()
    }
}
