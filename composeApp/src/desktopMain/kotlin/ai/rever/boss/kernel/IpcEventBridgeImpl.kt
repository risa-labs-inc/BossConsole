package ai.rever.boss.kernel

import ai.rever.boss.ipc.IpcEventBridge
import ai.rever.boss.ipc.proto.EventEnvelope
import ai.rever.boss.ipc.services.EventBusServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import com.google.protobuf.ByteString

/**
 * Implements [IpcEventBridge] by forwarding events to [EventBusServiceImpl.publishLocal].
 *
 * Used in KERNEL mode to forward in-process event bus events to cross-process subscribers.
 * When [eventBusService] is null (e.g., during shutdown), forwards are silently dropped.
 */
class IpcEventBridgeImpl(
    private val eventBusService: EventBusServiceImpl?,
    private val scope: CoroutineScope,
) : IpcEventBridge {

    private val logger = LoggerFactory.getLogger(IpcEventBridgeImpl::class.java)

    override suspend fun forward(eventType: String, payload: Any, sourceWindowId: String) {
        val svc = eventBusService ?: return

        // Serialize payload to JSON bytes for transport
        val payloadBytes = try {
            Json.encodeToString(kotlinx.serialization.serializer(payload.javaClass), payload)
                .toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            // Fall back to toString() if serialization fails — for debugging
            logger.debug(
                "Payload serialization failed for event {} - forwarding toString(): {}",
                eventType,
                e.toString(),
            )
            payload.toString().toByteArray(Charsets.UTF_8)
        }

        val envelope = EventEnvelope.newBuilder()
            .setEventType(eventType)
            .setPayload(ByteString.copyFrom(payloadBytes))
            .setSourceWindowId(sourceWindowId)
            .setSourceProcess("kernel")
            .setTimestamp(System.currentTimeMillis())
            .build()

        scope.launch {
            try {
                svc.publishLocal(envelope)
            } catch (e: Exception) {
                logger.warn("Failed to forward event {} cross-process: {}", eventType, e.message)
            }
        }
    }
}
