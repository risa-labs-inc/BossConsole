package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.*
import ai.rever.boss.ipc.services.EventBusServiceImpl
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private companion object {
        const val POLL_MS = 25L
        const val BATCH_SIZE = 3
    }

    private var server: io.grpc.Server? = null
    private var channel: io.grpc.ManagedChannel? = null
    private var port: Int = 0
    private lateinit var eventBusService: EventBusServiceImpl

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        eventBusService = EventBusServiceImpl()
        server =
            ServerBuilder
                .forPort(port)
                .addService(eventBusService)
                .build()
                .start()
        channel =
            ManagedChannelBuilder
                .forAddress("localhost", port)
                .usePlaintext()
                .build()
    }

    @After
    fun tearDown() {
        channel?.shutdownNow()
        server?.shutdownNow()
    }

    /**
     * Publish until the subscriber has actually received something.
     *
     * These tests used to `delay(100)` and publish once. The service's `MutableSharedFlow` has **no
     * replay**, so a publish that lands before the subscription is registered is lost for good — and then
     * `subscriberJob.join()` waits out the enclosing 10s timeout. Latent on a fast machine, and it fails on
     * the Windows CI runner, which is the slowest leg. Republishing is safe because every caller asserts on
     * the *first* event it receives.
     */
    private suspend fun publishUntilDelivered(
        subscriber: Job,
        publish: suspend () -> Unit,
    ) {
        while (subscriber.isActive) {
            publish()
            delay(POLL_MS)
        }
    }

    @Test
    fun `publish event is received by subscriber`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

                // Start subscriber
                val subscribeRequest =
                    SubscribeRequest
                        .newBuilder()
                        .setSubscriberId("test-subscriber-1")
                        .addEventTypes("TestEvent")
                        .build()

                var receivedEnvelope: EventEnvelope? = null
                val subscriberJob =
                    launch {
                        receivedEnvelope = stub.subscribe(subscribeRequest).first()
                    }

                // Give subscriber time to connect
                // Publish event
                val envelope =
                    EventEnvelope
                        .newBuilder()
                        .setEventType("TestEvent")
                        .setPayload(ByteString.copyFromUtf8("hello from test"))
                        .setSourceProcess("test-publisher")
                        .setTimestamp(System.currentTimeMillis())
                        .build()

                var publishResponse = stub.publish(envelope)
                publishUntilDelivered(subscriberJob) { publishResponse = stub.publish(envelope) }

                subscriberJob.join()

                assertTrue(publishResponse.success)
                assertEquals("TestEvent", receivedEnvelope?.eventType)
                assertEquals("hello from test", receivedEnvelope?.payload?.toStringUtf8())
            }
        }

    @Test
    fun `subscriber with type filter only receives matching events`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

                // Subscribe only to "FilteredEvent"
                val subscribeRequest =
                    SubscribeRequest
                        .newBuilder()
                        .setSubscriberId("filter-subscriber")
                        .addEventTypes("FilteredEvent")
                        .build()

                var receivedEnvelope: EventEnvelope? = null
                val subscriberJob =
                    launch {
                        receivedEnvelope = stub.subscribe(subscribeRequest).first()
                    }

                // Publish unmatched event first
                stub.publish(
                    EventEnvelope
                        .newBuilder()
                        .setEventType("OtherEvent")
                        .setPayload(ByteString.copyFromUtf8("should not receive"))
                        .setTimestamp(System.currentTimeMillis())
                        .build(),
                )

                // Publish matching event
                val matching =
                    EventEnvelope
                        .newBuilder()
                        .setEventType("FilteredEvent")
                        .setPayload(ByteString.copyFromUtf8("should receive this"))
                        .setTimestamp(System.currentTimeMillis())
                        .build()
                publishUntilDelivered(subscriberJob) { stub.publish(matching) }

                subscriberJob.join()

                assertEquals("FilteredEvent", receivedEnvelope?.eventType)
                assertEquals("should receive this", receivedEnvelope?.payload?.toStringUtf8())
            }
        }

    @Test
    fun `publishBatch delivers all events`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

                val received = mutableListOf<EventEnvelope>()
                val ready = CompletableDeferred<Unit>()
                val subscribeRequest =
                    SubscribeRequest
                        .newBuilder()
                        .setSubscriberId("batch-subscriber")
                        .addEventTypes("BatchEvent")
                        .addEventTypes("ReadyProbe")
                        .build()

                val subscriberJob =
                    launch {
                        stub.subscribe(subscribeRequest).collect { envelope ->
                            if (envelope.eventType == "ReadyProbe") {
                                ready.complete(Unit)
                            } else {
                                received.add(envelope)
                            }
                        }
                    }

                // Observing a probe proves this stream is collecting. Retry only probes, so delayed
                // delivery cannot duplicate the batch or hide a batch with missing entries.
                val probe = EventEnvelope.newBuilder().setEventType("ReadyProbe").build()
                while (!ready.isCompleted) {
                    assertTrue(subscriberJob.isActive, "Subscriber ended before receiving the readiness probe")
                    stub.publish(probe)
                    delay(POLL_MS)
                }

                val batchRequest =
                    PublishBatchRequest
                        .newBuilder()
                        .addAllEvents(
                            (1..BATCH_SIZE).map { i ->
                                EventEnvelope
                                    .newBuilder()
                                    .setEventType("BatchEvent")
                                    .setPayload(ByteString.copyFromUtf8("event-$i"))
                                    .setTimestamp(System.currentTimeMillis())
                                    .build()
                            },
                        ).build()

                assertTrue(stub.publishBatch(batchRequest).success)

                // Wait for the complete batch. Fail immediately if the stream ends; the enclosing
                // timeout bounds a live stream that never delivers all expected events.
                while (received.size < BATCH_SIZE) {
                    assertTrue(subscriberJob.isActive, "Subscriber ended before receiving the complete batch")
                    delay(POLL_MS)
                }
                subscriberJob.cancel()

                assertEquals(
                    (1..BATCH_SIZE).map { "event-$it" },
                    received.map { it.payload.toStringUtf8() },
                    "Should receive each batch event once, in order",
                )
            }
        }

    @Test
    fun `local publish via publishLocal reaches subscribers`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

                val subscribeRequest =
                    SubscribeRequest
                        .newBuilder()
                        .setSubscriberId("local-subscriber")
                        .addEventTypes("LocalEvent")
                        .build()

                var receivedEnvelope: EventEnvelope? = null
                val subscriberJob =
                    launch {
                        receivedEnvelope = stub.subscribe(subscribeRequest).first()
                    }

                val event =
                    EventEnvelope
                        .newBuilder()
                        .setEventType("LocalEvent")
                        .setPayload(ByteString.copyFromUtf8("local-payload"))
                        .setTimestamp(System.currentTimeMillis())
                        .build()
                publishUntilDelivered(subscriberJob) { eventBusService.publishLocal(event) }

                subscriberJob.join()
                assertEquals("LocalEvent", receivedEnvelope?.eventType)
                assertEquals("local-payload", receivedEnvelope?.payload?.toStringUtf8())
            }
        }
}
