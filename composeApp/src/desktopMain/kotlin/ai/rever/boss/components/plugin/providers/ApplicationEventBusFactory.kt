package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.ApplicationEventBusRegistry
import ai.rever.boss.plugin.api.AuthEvent
import ai.rever.boss.plugin.api.BrowserEvent
import ai.rever.boss.plugin.api.BrowserInteractionEvent
import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.plugin.api.FileChangeEvent
import ai.rever.boss.plugin.api.PluginLifecycleEvent
import ai.rever.boss.plugin.api.ProjectChangeEvent
import ai.rever.boss.plugin.api.TabEvent
import ai.rever.boss.plugin.api.TerminalSessionEvent
import ai.rever.boss.plugin.api.WindowFocusEvent
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop implementation of ApplicationEventBus factory.
 *
 * Prefers the process-global [ApplicationEventBusRegistry] instance if one already exists,
 * so the host and every in-process plugin share a single bus regardless of which classloader
 * first creates it.
 */
actual fun createApplicationEventBus(scope: CoroutineScope): ApplicationEventBus {
    ApplicationEventBusRegistry.bus?.let { return it }
    return ApplicationEventBusImpl.getInstance(scope)
}

/**
 * Scope handed to a bus created by [publishSystemEvent] rather than by a plugin touching
 * `PluginContext.applicationEventBus`. Nothing in [ApplicationEventBusImpl] launches on it today;
 * it exists because the constructor takes one, and it is a supervisor so that a future child
 * failing cannot take the process-wide bus with it. Lazy so it is built only if that fallback is
 * ever taken, which on most runs it is not.
 */
private val systemEventBusScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

actual fun publishSystemEvent(event: ApplicationEvent) {
    // Route through the shared registry rather than this classloader's own singleton, so host
    // system events reach the same bus instance that (possibly different-classloader) plugins
    // subscribe to.
    ApplicationEventBusRegistry.systemPublisher?.let {
        it(event)
        return
    }
    // Both registry fields are public vars in the api, so `bus` set without `systemPublisher` is
    // one stray assignment away (the runtime module, a plugin, a test that restores only half).
    // Falling through in that state would emit into THIS classloader's instance, which is not
    // necessarily the registry's bus - a permanent silent drop on every publish, including the
    // high-volume browser-interaction path. Refuse it and say so once, rather than working
    // around half an invariant.
    if (ApplicationEventBusRegistry.bus != null) {
        if (partialRegistryWarned.compareAndSet(false, true)) {
            systemEventLogger.warn(
                LogCategory.SYSTEM,
                "ApplicationEventBusRegistry has a bus but no systemPublisher; host events are being dropped",
            )
        }
        return
    }
    // No bus and no publisher: getInstance has never run, so nobody holds the bus and it has no
    // subscribers - THIS event still reaches no one, and creating the bus does not rescue it.
    // What it buys is that it is the last one. The bus was previously built only when the first
    // plugin touched PluginContext.applicationEventBus, so on a build where no installed plugin
    // had asked yet the host was permanently silent - not ProjectChangeEvent, not AuthEvent, not
    // TabEvent, and with replay = 0 nothing recoverable afterwards. Whether a plugin has been
    // curious is not a sensible thing for the host's events to depend on. getInstance registers
    // the publisher, so this branch is taken at most once.
    ApplicationEventBusImpl.getInstance(systemEventBusScope).publishInternal(event)
}

private val systemEventLogger = BossLogger.forComponent("ApplicationEventBus")

/** One warning, not one per event: the drop is permanent, so repeating it only buries the log. */
private val partialRegistryWarned = AtomicBoolean(false)

/**
 * Desktop implementation of ApplicationEventBus.
 *
 * This is a singleton that manages application-wide event distribution.
 * All events are broadcast to all subscribers via SharedFlow.
 */
class ApplicationEventBusImpl private constructor(
    private val scope: CoroutineScope,
) : ApplicationEventBus {
    companion object {
        // Not @Volatile: every read and write is inside the synchronized block below. The
        // lock-free fast path is gone deliberately - both callers (createApplicationEventBus,
        // publishSystemEvent) check the registry first, so this is not on a hot path.
        private var instance: ApplicationEventBusImpl? = null

        fun getInstance(scope: CoroutineScope): ApplicationEventBusImpl =
            synchronized(this) {
                val bus = instance ?: ApplicationEventBusImpl(scope).also { instance = it }
                // Publish to the process-global registry so the host's publishSystemEvent and
                // in-process plugins share this exact instance regardless of classloader.
                //
                // Checked on every call rather than only at creation: the registry is what
                // publishSystemEvent reads, so an instance that exists while the registry is
                // empty means host events go nowhere, and nothing would ever put it right.
                // Inside the lock, because it is a read-modify-write of two process-global
                // statics; the lock is uncontended after the first call.
                //
                // Order matters, though not in the way it looks: `bus` is set first, so a
                // concurrent publishSystemEvent can see a non-null bus while systemPublisher is
                // still null. That caller falls through to this method, blocks here, and emits
                // on the same instance - nothing is lost. Reversing the two would not be
                // obviously wrong to a later reader, so it is written down.
                // Both fields tested, because both are written. `systemPublisher != null` with a
                // null `bus` is the mirror of the half-invariant publishSystemEvent refuses - and
                // it is not hypothetical, it is exactly what a test installs when it swaps the
                // publisher to capture events (ProjectChangeAnnouncementTest,
                // BrowserAnalyticsEmissionTest, BossTabsComponentMoveTest). Testing only `bus`
                // would let this method silently take that publisher away from them.
                if (ApplicationEventBusRegistry.bus == null && ApplicationEventBusRegistry.systemPublisher == null) {
                    ApplicationEventBusRegistry.bus = bus
                    ApplicationEventBusRegistry.systemPublisher = { event -> bus.publishInternal(event) }
                }
                bus
            }
    }

    // Replay = 0 means events are only delivered to active subscribers
    // Buffer = 64 should handle most burst scenarios
    private val _events =
        MutableSharedFlow<ApplicationEvent>(
            replay = 0,
            extraBufferCapacity = 64,
        )

    override fun events(): Flow<ApplicationEvent> = _events.asSharedFlow()

    /**
     * Every event type the host publishes is listed rather than left to the reflective
     * `else`: that branch pays a `KClass` check per element, and the browser interaction
     * events are the highest-volume thing on this bus.
     */
    override fun <T : ApplicationEvent> eventsOfType(eventType: Class<T>): Flow<T> {
        @Suppress("UNCHECKED_CAST")
        return when (eventType) {
            FileChangeEvent::class.java -> _events.filterIsInstance<FileChangeEvent>() as Flow<T>
            ProjectChangeEvent::class.java -> _events.filterIsInstance<ProjectChangeEvent>() as Flow<T>
            WindowFocusEvent::class.java -> _events.filterIsInstance<WindowFocusEvent>() as Flow<T>
            PluginLifecycleEvent::class.java -> _events.filterIsInstance<PluginLifecycleEvent>() as Flow<T>
            TabEvent::class.java -> _events.filterIsInstance<TabEvent>() as Flow<T>
            AuthEvent::class.java -> _events.filterIsInstance<AuthEvent>() as Flow<T>
            TerminalSessionEvent::class.java -> _events.filterIsInstance<TerminalSessionEvent>() as Flow<T>
            CustomPluginEvent::class.java -> _events.filterIsInstance<CustomPluginEvent>() as Flow<T>
            BrowserEvent::class.java -> _events.filterIsInstance<BrowserEvent>() as Flow<T>
            BrowserInteractionEvent::class.java -> _events.filterIsInstance<BrowserInteractionEvent>() as Flow<T>
            else -> _events.filterIsInstance(eventType.kotlin)
        }
    }

    override fun publish(event: ApplicationEvent) {
        // Allow plugins to publish custom events and terminal session events
        // Other system events should only be published internally
        if (event is CustomPluginEvent || event is TerminalSessionEvent) {
            _events.tryEmit(event)
        }
    }

    /**
     * Internal method for the host application to publish system events.
     * This is not exposed through the public interface.
     */
    internal fun publishInternal(event: ApplicationEvent) {
        _events.tryEmit(event)
    }
}
