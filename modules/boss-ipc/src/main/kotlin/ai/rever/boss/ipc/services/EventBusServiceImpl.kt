package ai.rever.boss.ipc.services

import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * gRPC implementation of the EventBusService.
 *
 * Routes events between processes. When a process publishes an event,
 * it is delivered to all subscribers (in other processes) that match
 * the event type and window ID filters.
 *
 * This replaces the in-process SharedFlow event buses for cross-process communication.
 */
class EventBusServiceImpl : EventBusServiceGrpcKt.EventBusServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(EventBusServiceImpl::class.java)

    // Central event flow that all subscribers read from
    private val eventFlow = MutableSharedFlow<EventEnvelope>(
        extraBufferCapacity = 256,
    )

    // Track active subscribers for metrics
    private val subscriberCount = AtomicInteger(0)
    private val anonIdCounter = AtomicInteger(0)
    private val subscriberInfo = ConcurrentHashMap<String, SubscriberInfo>()

    override fun subscribe(request: SubscribeRequest): Flow<EventEnvelope> {
        val subscriberId = request.subscriberId.ifEmpty { "anon-${anonIdCounter.incrementAndGet()}" }
        subscriberCount.incrementAndGet()
        val eventTypes = request.eventTypesList.toSet()
        val windowFilter = request.sourceWindowId.takeIf { it.isNotEmpty() }

        subscriberInfo[subscriberId] = SubscriberInfo(
            subscriberId = subscriberId,
            eventTypes = eventTypes,
            windowFilter = windowFilter,
            subscribedAt = System.currentTimeMillis(),
        )

        logger.debug("New subscriber: id={}, types={}, window={}", subscriberId, eventTypes, windowFilter)

        return flow {
            try {
                eventFlow
                    .filter { envelope ->
                        // Type filter: empty = subscribe to all
                        (eventTypes.isEmpty() || envelope.eventType in eventTypes) &&
                        // Window filter: empty = all windows
                        (windowFilter == null || envelope.sourceWindowId == windowFilter)
                    }
                    .collect { emit(it) }
            } finally {
                subscriberInfo.remove(subscriberId)
                subscriberCount.decrementAndGet()
                logger.debug("Subscriber disconnected: id={}", subscriberId)
            }
        }
    }

    override suspend fun publish(request: EventEnvelope): PublishResponse {
        eventFlow.emit(request)

        return PublishResponse.newBuilder()
            .setSuccess(true)
            .setSubscriberCount(subscriberCount.get())
            .build()
    }

    override suspend fun publishBatch(request: PublishBatchRequest): PublishResponse {
        request.eventsList.forEach { eventFlow.emit(it) }

        return PublishResponse.newBuilder()
            .setSuccess(true)
            .setSubscriberCount(subscriberCount.get())
            .build()
    }

    /**
     * Publish an event locally (from kernel code, not via gRPC).
     */
    suspend fun publishLocal(envelope: EventEnvelope) {
        eventFlow.emit(envelope)
    }

    val activeSubscribers: Int get() = subscriberCount.get()
}

private data class SubscriberInfo(
    val subscriberId: String,
    val eventTypes: Set<String>,
    val windowFilter: String?,
    val subscribedAt: Long,
)
