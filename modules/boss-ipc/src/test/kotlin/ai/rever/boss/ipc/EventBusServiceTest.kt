package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.*
import ai.rever.boss.ipc.services.EventBusServiceImpl
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for EventBusService — publish/subscribe round-trip.
 */
class EventBusServiceTest {

    private var server: io.grpc.Server? = null
    private var channel: io.grpc.ManagedChannel? = null
    private var port: Int = 0
    private lateinit var eventBusService: EventBusServiceImpl

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        eventBusService = EventBusServiceImpl()
        server = ServerBuilder.forPort(port)
            .addService(eventBusService)
            .build()
            .start()
        channel = ManagedChannelBuilder.forAddress("localhost", port)
            .usePlaintext()
            .build()
    }

    @After
    fun tearDown() {
        channel?.shutdownNow()
        server?.shutdownNow()
    }

    @Test
    fun `publish event is received by subscriber`() = runBlocking {
        withTimeout(10_000) {
            val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

            // Start subscriber
            val subscribeRequest = SubscribeRequest.newBuilder()
                .setSubscriberId("test-subscriber-1")
                .addEventTypes("TestEvent")
                .build()

            var receivedEnvelope: EventEnvelope? = null
            val subscriberJob = launch {
                receivedEnvelope = stub.subscribe(subscribeRequest).first()
            }

            // Give subscriber time to connect
            kotlinx.coroutines.delay(100)

            // Publish event
            val envelope = EventEnvelope.newBuilder()
                .setEventType("TestEvent")
                .setPayload(ByteString.copyFromUtf8("hello from test"))
                .setSourceProcess("test-publisher")
                .setTimestamp(System.currentTimeMillis())
                .build()

            val publishResponse = stub.publish(envelope)

            subscriberJob.join()

            assertTrue(publishResponse.success)
            assertEquals("TestEvent", receivedEnvelope?.eventType)
            assertEquals("hello from test", receivedEnvelope?.payload?.toStringUtf8())
        }
    }

    @Test
    fun `subscriber with type filter only receives matching events`() = runBlocking {
        withTimeout(10_000) {
            val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

            // Subscribe only to "FilteredEvent"
            val subscribeRequest = SubscribeRequest.newBuilder()
                .setSubscriberId("filter-subscriber")
                .addEventTypes("FilteredEvent")
                .build()

            var receivedEnvelope: EventEnvelope? = null
            val subscriberJob = launch {
                receivedEnvelope = stub.subscribe(subscribeRequest).first()
            }

            kotlinx.coroutines.delay(100)

            // Publish unmatched event first
            stub.publish(EventEnvelope.newBuilder()
                .setEventType("OtherEvent")
                .setPayload(ByteString.copyFromUtf8("should not receive"))
                .setTimestamp(System.currentTimeMillis())
                .build())

            // Publish matching event
            stub.publish(EventEnvelope.newBuilder()
                .setEventType("FilteredEvent")
                .setPayload(ByteString.copyFromUtf8("should receive this"))
                .setTimestamp(System.currentTimeMillis())
                .build())

            subscriberJob.join()

            assertEquals("FilteredEvent", receivedEnvelope?.eventType)
            assertEquals("should receive this", receivedEnvelope?.payload?.toStringUtf8())
        }
    }

    @Test
    fun `publishBatch delivers all events`() = runBlocking {
        withTimeout(10_000) {
            val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

            val received = mutableListOf<EventEnvelope>()
            val subscribeRequest = SubscribeRequest.newBuilder()
                .setSubscriberId("batch-subscriber")
                .addEventTypes("BatchEvent")
                .build()

            val subscriberJob = launch {
                stub.subscribe(subscribeRequest).collect { envelope ->
                    received.add(envelope)
                    if (received.size == 3) return@collect
                }
            }

            kotlinx.coroutines.delay(100)

            val batchRequest = PublishBatchRequest.newBuilder()
                .addAllEvents((1..3).map { i ->
                    EventEnvelope.newBuilder()
                        .setEventType("BatchEvent")
                        .setPayload(ByteString.copyFromUtf8("event-$i"))
                        .setTimestamp(System.currentTimeMillis())
                        .build()
                })
                .build()

            stub.publishBatch(batchRequest)

            kotlinx.coroutines.delay(500)
            subscriberJob.cancel()

            assertEquals(3, received.size, "Should receive all 3 batch events")
        }
    }

    @Test
    fun `local publish via publishLocal reaches subscribers`() = runBlocking {
        withTimeout(10_000) {
            val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

            val subscribeRequest = SubscribeRequest.newBuilder()
                .setSubscriberId("local-subscriber")
                .addEventTypes("LocalEvent")
                .build()

            var receivedEnvelope: EventEnvelope? = null
            val subscriberJob = launch {
                receivedEnvelope = stub.subscribe(subscribeRequest).first()
            }

            kotlinx.coroutines.delay(100)

            eventBusService.publishLocal(
                EventEnvelope.newBuilder()
                    .setEventType("LocalEvent")
                    .setPayload(ByteString.copyFromUtf8("local-payload"))
                    .setTimestamp(System.currentTimeMillis())
                    .build()
            )

            subscriberJob.join()
            assertEquals("LocalEvent", receivedEnvelope?.eventType)
            assertEquals("local-payload", receivedEnvelope?.payload?.toStringUtf8())
        }
    }
}
