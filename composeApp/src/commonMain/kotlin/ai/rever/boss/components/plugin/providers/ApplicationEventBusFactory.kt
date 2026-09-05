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
 * (e.g. the analytics plugin) observe it. Lets common-source host code emit system events
 * without depending on the platform-specific bus implementation.
 *
 * Best-effort in one specific way: the bus is `replay = 0`, so an event published while nothing
 * is subscribed reaches no one and is not recoverable afterwards. It is no longer a *no-op* when
 * the bus does not exist - the desktop implementation creates it, so the host is not silent until
 * some plugin happens to touch `PluginContext.applicationEventBus` - but that first event is
 * still, by definition, unsubscribed.
 */
expect fun publishSystemEvent(event: ApplicationEvent)
