package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.EventBusServiceGrpcKt
import ai.rever.boss.ipc.proto.EventEnvelope
import ai.rever.boss.ipc.proto.SubscribeRequest
import ai.rever.boss.ipc.services.EventBusServiceImpl
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.ipc.services.StateServiceImpl
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adding a service to an already-running [BossIpcServer] must not disturb the server.
 *
 * It used to stop the running server and rebuild it on the same address. That is destructive in a way only
 * a long-lived call notices: `KernelBootstrap.registerPluginServices()` adds up to fifteen bridges one at a
 * time, and since a streaming RPC does not complete on demand, each rebuild burned the full 2s shutdown
 * grace period and then killed every in-flight call — with the address unbound in between. Unary bridges
 * survived on timing; `PluginUIService.StreamUI`, whose whole job is to stay open, could not.
 */
class LateServiceRegistrationTest {
    private lateinit var testServer: IpcTestServer
    private var ipcServer: BossIpcServer? = null
    private var channel: ManagedChannel? = null
    private var port: Int = 0

    @Before
    fun setUp() {
        testServer = IpcTestServer(KernelServiceImpl())
        ipcServer = testServer.server
        port = testServer.server.port
        channel = testServer.channelFor("late-registration-test")
    }

    @After
    fun tearDown() {
        testServer.close()
    }

    @Test
    fun `a service added after the server started is reachable`() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                ipcServer!!.addService(EventBusServiceImpl())

                val response = stub().publish(envelope("Reachable"))

                assertTrue(response.success, "a late-registered service must answer")
            }
        }

    @Test
    fun `an open streaming call survives fifteen services being added underneath it`() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                ipcServer!!.addService(EventBusServiceImpl())
                val received = Channel<EventEnvelope>(Channel.UNLIMITED)
                val subscriber =
                    launch {
                        stub()
                            .subscribe(
                                SubscribeRequest
                                    .newBuilder()
                                    .setSubscriberId("late-registration")
                                    .addEventTypes("Ping")
                                    .build(),
                            ).collect { received.trySend(it) }
                    }
                // Wait until the subscription is genuinely live, not merely launched.
                while (received.isEmpty) {
                    stub().publish(envelope("Ping"))
                    delay(POLL_MS)
                }
                received.tryReceive()

                // What registerPluginServices() does, while the stream is open.
                repeat(LATE_SERVICES) { ipcServer!!.addService(StateServiceImpl()) }

                // The same stream still delivers, on the same address.
                stub().publish(envelope("Ping"))
                val next = received.receive()

                assertEquals("Ping", next.eventType)
                assertTrue(ipcServer!!.isRunning)
                assertEquals(port, ipcServer!!.port, "a rebuild would have rebound the address")
                subscriber.cancel()
            }
        }

    private fun stub() = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

    private fun envelope(type: String): EventEnvelope =
        EventEnvelope
            .newBuilder()
            .setEventType(type)
            .setSourceProcess("late-registration-test")
            .setTimestamp(System.currentTimeMillis())
            .build()

    private companion object {
        const val LATE_SERVICES = 15
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 25L
    }
}
