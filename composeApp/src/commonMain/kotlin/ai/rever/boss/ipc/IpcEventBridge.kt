package ai.rever.boss.ipc

/**
 * Bridge interface for forwarding events across process boundaries.
 * When null/not-set, events stay in-process (current behavior).
 * When set, events are additionally forwarded to cross-process subscribers via gRPC.
 *
 * Phase 1 of the microkernel architecture: provides the abstraction layer
 * that event buses use to optionally forward events to other processes.
 */
interface IpcEventBridge {
    /**
     * Forward an event to cross-process subscribers.
     * Called after the event has been emitted locally.
     *
     * @param eventType The event class name (e.g., "DashboardOpenFileEvent")
     * @param payload The event data object
     * @param sourceWindowId The window that originated the event
     */
    suspend fun forward(eventType: String, payload: Any, sourceWindowId: String)
}
