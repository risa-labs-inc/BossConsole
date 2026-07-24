package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.input.key.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Represents a keyboard event with its source and context information.
 * Used for event bubbling through the component hierarchy.
 */
data class KeyboardEvent(
    val keyEvent: KeyEvent,
    val source: KeyEventSource,
    val context: ShortcutContext,
    val sourceWindowId: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Identifies where a keyboard event originated from.
 * Used to determine event priority and handling logic.
 */
enum class KeyEventSource {
    /** Event intercepted at AWT window level before Compose */
    AWT_INTERCEPTOR,

    /** Event from terminal component */
    COMPONENT_TERMINAL,

    /** Event from browser component (Fluck) */
    COMPONENT_BROWSER,

    /** Event from code editor component */
    COMPONENT_EDITOR,

    /** Event from dialog components */
    COMPONENT_DIALOG,

    /** Event from workspace/BossApp level */
    WORKSPACE,

    /** Event from automated testing */
    TEST,
}

/**
 * Priority levels for keyboard event handling.
 * Lower priority values are handled first (Component > Workspace > Global).
 */
enum class KeyboardEventPriority(
    val level: Int,
) {
    /** Highest priority - focused component handles first */
    COMPONENT(0),

    /** Medium priority - workspace/layout handles next */
    WORKSPACE(1),

    /** Lowest priority - global app-wide shortcuts handled last */
    GLOBAL(2),
    ;

    companion object {
        fun fromLevel(level: Int): KeyboardEventPriority = entries.find { it.level == level } ?: GLOBAL
    }
}

/**
 * Result of a keyboard event handler.
 */
data class KeyboardEventResult(
    val consumed: Boolean,
    val handlerName: String,
    val actionId: String? = null,
)

/**
 * Information about a registered keyboard event handler.
 * Tracks handler metadata for window-specific filtering.
 */
private data class HandlerInfo(
    val handlerName: String,
    val targetWindowId: String, // Every handler must be tied to a specific window
    val handler: suspend (KeyboardEvent) -> KeyboardEventResult,
)

/**
 * Central event bus for keyboard events.
 * Implements event bubbling with priority-based handling:
 * Component -> Workspace -> Global
 *
 * Each handler can consume the event or let it bubble to the next priority level.
 * Supports window-specific filtering via sourceWindowId/targetWindowId matching.
 */
object KeyboardEventBus {
    private val logger = BossLogger.forComponent("KeyboardEventBus")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _events =
        MutableSharedFlow<KeyboardEvent>(
            replay = 0,
            extraBufferCapacity = 100,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Flow of all keyboard events emitted to the bus.
     */
    val events: SharedFlow<KeyboardEvent> = _events.asSharedFlow()

    /**
     * Handlers registered for each priority level.
     * Map: Priority -> List of HandlerInfo (includes targetWindowId for filtering)
     *
     * Uses ConcurrentHashMap and CopyOnWriteArrayList for thread-safe access
     * from multiple coroutines without explicit synchronization.
     */
    private val handlers = ConcurrentHashMap<KeyboardEventPriority, CopyOnWriteArrayList<HandlerInfo>>()

    /**
     * Debug mode - logs all events and handling results.
     */
    var debugMode: Boolean = false

    /**
     * Emits a keyboard event to the bus.
     * Events are processed synchronously through the priority chain.
     *
     * @param event The keyboard event to emit
     * @return true if any handler consumed the event, false otherwise
     */
    suspend fun emit(event: KeyboardEvent): Boolean {
        // Emit to flow for observers
        _events.emit(event)
        ipcBridge?.forward("KeyboardEvent", event, event.sourceWindowId)

        // Process through priority chain
        val priorities = KeyboardEventPriority.entries.sortedBy { it.level }

        for (priority in priorities) {
            val priorityHandlers = handlers[priority] ?: continue

            for (handlerInfo in priorityHandlers) {
                // Only invoke handlers for the event's source window
                if (handlerInfo.targetWindowId != event.sourceWindowId) {
                    continue
                }

                try {
                    val result = handlerInfo.handler(event)

                    if (result.consumed) {
                        return true
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.UI, "Error in keyboard handler", mapOf("handler" to handlerInfo.handlerName), error = e)
                }
            }
        }

        return false
    }

    /**
     * Subscribes to keyboard events with a specific priority level.
     * Handlers are called in priority order: COMPONENT -> WORKSPACE -> GLOBAL.
     *
     * @param priority The priority level for this handler
     * @param handlerName Name for debugging purposes
     * @param targetWindowId Window ID to filter events - handler only receives events from this window
     * @param handler Function that processes the event and returns whether it was consumed
     * @return Job that can be cancelled to unsubscribe
     */
    fun subscribe(
        priority: KeyboardEventPriority,
        handlerName: String,
        targetWindowId: String,
        handler: suspend (KeyboardEvent) -> KeyboardEventResult,
    ): Job {
        // Add handler to the list for this priority (thread-safe with atomic computeIfAbsent)
        val handlerInfo = HandlerInfo(handlerName, targetWindowId, handler)
        handlers.computeIfAbsent(priority) { CopyOnWriteArrayList() }.add(handlerInfo)

        if (debugMode) {
            logger.debug(
                LogCategory.UI,
                "Registered handler",
                mapOf(
                    "handler" to handlerName,
                    "windowId" to targetWindowId,
                    "priority" to priority.name,
                ),
            )
        }

        // Return a job that removes the handler when cancelled
        val job =
            scope.launch {
                awaitCancellation()
            }

        // Use invokeOnCompletion to ensure handler removal happens synchronously
        // when the job is cancelled/completed. This runs on the thread that calls
        // cancel(), ensuring visibility to callers of cancelAndJoin().
        job.invokeOnCompletion {
            handlers[priority]?.removeAll { it.handlerName == handlerName }
            if (debugMode) {
                logger.debug(LogCategory.UI, "Unregistered handler", mapOf("handler" to handlerName))
            }
        }

        return job
    }

    /**
     * Clears all registered handlers.
     * Useful for testing or resetting the event bus.
     */
    fun clearHandlers() {
        handlers.clear()
        if (debugMode) {
            logger.debug(LogCategory.UI, "Cleared all handlers")
        }
    }

    /**
     * Gets the count of registered handlers for each priority level.
     * Useful for debugging.
     */
    fun getHandlerCounts(): Map<KeyboardEventPriority, Int> = handlers.mapValues { it.value.size }
}
