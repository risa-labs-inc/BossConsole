package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.plugin.api.NavigationTargetEvent
import ai.rever.boss.plugin.api.NavigationTargetProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
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
    private val logger = BossLogger.forComponent("NavigationTargetProviderImpl")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _targets =
        MutableSharedFlow<NavigationTargetEvent>(
            replay = 1,
            extraBufferCapacity = 5,
        )
    override val targets: SharedFlow<NavigationTargetEvent> = _targets.asSharedFlow()

    init {
        logger.debug(LogCategory.EDITOR, "Starting navigation-target collector")
        // Forward events from NavigationTargetBus to our flow
        scope.launch {
            logger.debug(LogCategory.EDITOR, "Navigation-target collector started")
            NavigationTargetBus.targets.collect { event ->
                logger.debug(
                    LogCategory.EDITOR,
                    "Forwarding navigation target to plugins",
                    mapOf("target" to "${event.filePath}:${event.line}:${event.column}"),
                )
                _targets.emit(
                    NavigationTargetEvent(
                        filePath = event.filePath,
                        line = event.line,
                        column = event.column,
                        sourceWindowId = event.sourceWindowId,
                    ),
                )
                logger.debug(LogCategory.EDITOR, "Navigation target emitted to plugin flow")
            }
        }
    }

    override fun clearCache() {
        NavigationTargetBus.clearCache()
    }
}
