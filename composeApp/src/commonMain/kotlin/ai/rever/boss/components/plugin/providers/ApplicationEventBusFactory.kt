package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBus
import kotlinx.coroutines.CoroutineScope

/**
 * Factory function to create platform-specific ApplicationEventBus.
 * Desktop implementation provides a singleton event bus.
 *
 * @param scope CoroutineScope for event processing
 * @return ApplicationEventBus implementation
 */
expect fun createApplicationEventBus(scope: CoroutineScope): ApplicationEventBus

/**
 * Publish a host/system [ApplicationEvent] onto the shared application event bus so plugins
 * (e.g. the analytics plugin) observe it. Best-effort: a no-op if the bus has not been
 * created yet. Lets common-source host code emit system events without depending on the
 * platform-specific bus implementation.
 */
expect fun publishSystemEvent(event: ApplicationEvent)
