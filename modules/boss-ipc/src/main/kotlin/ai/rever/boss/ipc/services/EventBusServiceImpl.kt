package ai.rever.boss.ipc.services

import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Authenticated broadcast bus. Event sharing is intentional: the analytics client consumes event
 * kinds across processes. Source identity is host-verified; subscriber labels convey no authority.
 * Sensitive private state belongs in an owner-scoped service, not a broadcast event payload.
 */
class EventBusServiceImpl : EventBusServiceGrpcKt.EventBusServiceCoroutineImplBase() {
    private val eventFlow = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = 256)
    private val subscriberCount = AtomicInteger()

    override fun subscribe(request: SubscribeRequest): Flow<EventEnvelope> =
        flow {
            IpcCall.current()
            val eventTypes = request.eventTypesList.toSet()
            val windowFilter = request.sourceWindowId.takeIf { it.isNotEmpty() }
            subscriberCount.incrementAndGet()
            try {
                eventFlow
                    .filter { envelope ->
                        (eventTypes.isEmpty() || envelope.eventType in eventTypes) &&
                            (windowFilter == null || envelope.sourceWindowId == windowFilter)
                    }.collect {
                        IpcCall.current()
                        emit(it)
                    }
            } finally {
                subscriberCount.decrementAndGet()
            }
        }

    override suspend fun publish(request: EventEnvelope): PublishResponse {
        val caller = IpcCall.current()
        eventFlow.emit(request.toBuilder().setSourceProcess(caller.processId).build())
        return published()
    }

    override suspend fun publishBatch(request: PublishBatchRequest): PublishResponse {
        request.eventsList.forEach {
            val caller = IpcCall.current()
            eventFlow.emit(it.toBuilder().setSourceProcess(caller.processId).build())
        }
        return published()
    }

    /** Trusted in-process host publishers preserve their established event source metadata. */
    suspend fun publishLocal(envelope: EventEnvelope) {
        eventFlow.emit(envelope)
    }

    private fun published(): PublishResponse =
        PublishResponse
            .newBuilder()
            .setSuccess(true)
            .setSubscriberCount(subscriberCount.get())
            .build()

    val activeSubscribers: Int get() = subscriberCount.get()
}
