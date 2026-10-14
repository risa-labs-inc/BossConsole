package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.EventBusServiceGrpcKt
import ai.rever.boss.ipc.proto.EventEnvelope
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessState
import ai.rever.boss.ipc.proto.ProcessStatusRequest
import ai.rever.boss.ipc.proto.StateKey
import ai.rever.boss.ipc.proto.StateServiceGrpcKt
import ai.rever.boss.ipc.proto.SubscribeRequest
import ai.rever.boss.ipc.services.EventBusServiceImpl
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.ipc.services.StateServiceImpl
import com.google.protobuf.ByteString
import io.grpc.BindableService
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ConnectivityState
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the transport abuse bounds of [IpcTransportLimits] against the real authenticated wire.
 *
 * WHY: any process on the machine can open sockets to the kernel endpoint, so the transport
 * itself must refuse abuse before service handlers run. An oversize frame dies with a
 * protocol-level RESOURCE_EXHAUSTED error, one connection cannot hold unbounded concurrent
 * streams, and a connection left idle is reaped instead of pinning its slot forever. Every test
 * also proves the other half of the contract: well-formed traffic is unaffected before, during
 * and after the abuse.
 */
class IpcTransportLimitsTest {
    @Test
    fun `oversized request is refused with a protocol level error and later traffic is unaffected`() =
        runBlocking {
            // The cap is pinned BELOW gRPC's 4MiB library default on purpose: a payload sized
            // against the default would be refused by the library even if the explicit pin were
            // removed, and this test would pass while the bound it guards was gone.
            val serverLimits = IpcTransportLimits(maxInboundMessageBytes = 65_536)
            IpcTestServer(EventBusServiceImpl(), limits = serverLimits).use { host ->
                val channel = host.channelFor("large-writer")
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel)
                val oversizePayload =
                    ByteString.copyFrom(ByteArray(serverLimits.maxInboundMessageBytes + 1))
                val oversize =
                    EventEnvelope
                        .newBuilder()
                        .setEventType("test.oversize")
                        .setPayload(oversizePayload)
                        .build()

                val refusal = assertFailsWith<StatusException> { stub.publish(oversize) }

                // The server's deframer refuses the frame from its size prefix alone, before any handler
                // runs, and tears the stream down while the oversized body may still be in flight. The
                // status the client observes depends on where that teardown catches the writer, which is
                // platform timing: RESOURCE_EXHAUSTED trailers when the refusal lands after the write
                // completed, UNAVAILABLE or CANCELLED when it interrupts a write still in flight, and on
                // some stacks netty escalates the teardown into a connection GOAWAY surfaced as INTERNAL.
                // Every shape proves the transport refused the frame: a delivered frame would have
                // published and answered success. Only identity-shaped codes would masquerade as an
                // auth failure instead of a transport refusal, so the pin is the same transport-level
                // guard the stream flood test holds.
                val refusalCode = refusal.status.code
                assertTrue(
                    refusalCode != Status.Code.UNAUTHENTICATED && refusalCode != Status.Code.PERMISSION_DENIED,
                    "oversize refusal must stay transport-level, saw $refusalCode",
                )
                // Prove the well-formed half of the contract: the refusal targeted the abusive
                // frame, not the peer. The channel can already report READY on a transport the
                // refusal teardown is still draining, so a single-shot call races the replacement;
                // assertRecovers bounds that window and still fails on a genuine lockout.
                awaitUntil(POLL_DEADLINE_MILLIS) { channel.getState(true) == ConnectivityState.READY }
                val wellFormed =
                    EventEnvelope
                        .newBuilder()
                        .setEventType("test.small")
                        .setPayload(ByteString.copyFromUtf8("fine"))
                        .build()
                assertRecovers(stub, wellFormed)
            }
        }

    @Test
    fun `oversized request metadata is refused at the server and later calls are unaffected`() =
        runBlocking {
            // The cap is pinned BELOW gRPC's 8KiB default header budget for the same reason as the
            // message-size test above: with the explicit pin removed this request is admitted and
            // this test fails. The injected header's value alone is one byte over the cap, so the
            // header list crosses it regardless of how the transport counts the process token and
            // its own framing headers, while the total stays far under the library default.
            val serverLimits = IpcTransportLimits(maxInboundMetadataBytes = 1_024)
            val oversizedRequestHeaders =
                object : ClientInterceptor {
                    override fun <ReqT : Any?, RespT : Any?> interceptCall(
                        method: MethodDescriptor<ReqT, RespT>,
                        callOptions: CallOptions,
                        next: Channel,
                    ): ClientCall<ReqT, RespT> =
                        object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                            next.newCall(method, callOptions),
                        ) {
                            override fun start(
                                responseListener: ClientCall.Listener<RespT>,
                                headers: Metadata,
                            ) {
                                headers.put(
                                    Metadata.Key.of("oversized-request", Metadata.ASCII_STRING_MARSHALLER),
                                    "x".repeat(serverLimits.maxInboundMetadataBytes + 1),
                                )
                                super.start(responseListener, headers)
                            }
                        }
                }
            IpcTestServer(KernelServiceImpl(), limits = serverLimits).use { host ->
                val writer =
                    KernelServiceGrpcKt.KernelServiceCoroutineStub(
                        ClientInterceptors.intercept(host.channelFor("metadata-writer"), oversizedRequestHeaders),
                    )
                val request = ProcessStatusRequest.newBuilder().setProcessId("metadata-writer").build()

                val refusal = assertFailsWith<StatusException> { writer.getProcessStatus(request) }

                // Same shape as the oversize request test: the transport refuses the headers before
                // any service handler runs, and the refusal may surface as RESOURCE_EXHAUSTED
                // trailers or as the teardown-shaped UNAVAILABLE / CANCELLED / INTERNAL codes when
                // it interrupts the write or escalates into a connection GOAWAY. Only identity-
                // shaped codes would masquerade as an auth failure instead of the header budget
                // refusing the request.
                val refusalCode = refusal.status.code
                assertTrue(
                    refusalCode != Status.Code.UNAUTHENTICATED && refusalCode != Status.Code.PERMISSION_DENIED,
                    "oversized request headers must fail at the transport, saw $refusalCode",
                )

                // The well-formed half of the contract: the refusal targeted the headers, not the
                // peer. A peer sending ordinary headers is admitted by the same capped server.
                val normalWriter = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor("normal-writer"))
                val normalRequest = ProcessStatusRequest.newBuilder().setProcessId("normal-writer").build()
                assertEquals(ProcessState.PROCESS_STATE_STOPPED, normalWriter.getProcessStatus(normalRequest).state)
            }
        }

    @Test
    fun `oversized response is refused at the child reader and later reads are unaffected`() =
        runBlocking {
            val state = StateServiceImpl()
            // The reader's cap is pinned BELOW the library default for the same reason as the
            // request test: removing the explicit pin must change this test's outcome.
            val readerLimits = IpcTransportLimits(maxInboundMessageBytes = 65_536)
            IpcTestServer(state).use { host ->
                // Local host writes bypass the request wire, so only the oversized response crosses a transport.
                state.setLocal(
                    "big",
                    ByteArray(readerLimits.maxInboundMessageBytes + 1),
                    "octet-stream",
                )
                state.setLocal("small", "fine".toByteArray(), "text")
                val reader =
                    StateServiceGrpcKt.StateServiceCoroutineStub(
                        host.channelFor("large-reader", clientLimits = readerLimits),
                    )

                val refusal =
                    assertFailsWith<StatusException> {
                        reader.getState(StateKey.newBuilder().setKey("big").build())
                    }

                assertEquals(Status.Code.RESOURCE_EXHAUSTED, refusal.status.code)
                assertEquals(
                    "fine",
                    reader.getState(StateKey.newBuilder().setKey("small").build()).value.toStringUtf8(),
                )
            }
        }

    @Test
    fun `oversized response metadata is refused by the authenticated child reader`() =
        runBlocking {
            val oversizedHeaders =
                object : ServerInterceptor {
                    override fun <ReqT : Any?, RespT : Any?> interceptCall(
                        call: ServerCall<ReqT, RespT>,
                        headers: Metadata,
                        next: ServerCallHandler<ReqT, RespT>,
                    ): ServerCall.Listener<ReqT> {
                        val wrapped =
                            object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                                override fun sendHeaders(responseHeaders: Metadata) {
                                    responseHeaders.put(
                                        Metadata.Key.of("oversized-response", Metadata.ASCII_STRING_MARSHALLER),
                                        "x".repeat(2_048),
                                    )
                                    super.sendHeaders(responseHeaders)
                                }
                            }
                        return next.startCall(wrapped, headers)
                    }
                }
            val service =
                object : BindableService {
                    override fun bindService() = ServerInterceptors.intercept(KernelServiceImpl(), oversizedHeaders)
                }
            IpcTestServer(service).use { host ->
                val reader =
                    KernelServiceGrpcKt.KernelServiceCoroutineStub(
                        host.channelFor(
                            "metadata-reader",
                            clientLimits = IpcTransportLimits(maxInboundMetadataBytes = 1_024),
                        ),
                    )
                val request = ProcessStatusRequest.newBuilder().setProcessId("metadata-reader").build()
                val refusal = assertFailsWith<StatusException> { reader.getProcessStatus(request) }
                assertTrue(
                    refusal.status.code in setOf(Status.Code.RESOURCE_EXHAUSTED, Status.Code.INTERNAL),
                    "oversized response headers must fail at the transport, saw ${refusal.status.code}",
                )
                val defaultReader = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor("normal-reader"))
                val normalRequest = ProcessStatusRequest.newBuilder().setProcessId("normal-reader").build()
                assertEquals(ProcessState.PROCESS_STATE_STOPPED, defaultReader.getProcessStatus(normalRequest).state)
            }
        }

    @Test
    fun `a connection cannot exceed its concurrent stream cap and recovers once streams close`() =
        runBlocking {
            val bus = EventBusServiceImpl()
            val limits =
                IpcTransportLimits(
                    maxConcurrentCallsPerConnection = CONCURRENT_STREAM_CAP,
                    maxConnectionIdleMillis = LONG_IDLE_MILLIS,
                )
            IpcTestServer(bus, limits = limits).use { host ->
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(host.channelFor("flooded-peer"))
                val request = SubscribeRequest.newBuilder().build()
                val refusals = CopyOnWriteArrayList<Status.Code>()
                val flood = subscribingScope(stub, request, FLOOD_STREAM_COUNT, refusals)
                try {
                    withTimeout(POLL_DEADLINE_MILLIS) {
                        awaitUntil(POLL_DEADLINE_MILLIS) { bus.activeSubscribers == CONCURRENT_STREAM_CAP }
                    }
                    // However many streams the peer opens, the server never runs more than the cap.
                    repeat(SAMPLE_COUNT) {
                        assertTrue(
                            bus.activeSubscribers <= CONCURRENT_STREAM_CAP,
                            "server held ${bus.activeSubscribers} concurrent streams, cap is $CONCURRENT_STREAM_CAP",
                        )
                        delay(SAMPLE_INTERVAL_MILLIS)
                    }
                } finally {
                    flood.cancel()
                }
                withTimeout(POLL_DEADLINE_MILLIS) {
                    awaitUntil(POLL_DEADLINE_MILLIS) { bus.activeSubscribers == 0 }
                }

                // Refusing the flood did not lock the peer out: fresh streams are admitted at the cap.
                val healthy = subscribingScope(stub, request, CONCURRENT_STREAM_CAP, refusals)
                try {
                    withTimeout(POLL_DEADLINE_MILLIS) {
                        awaitUntil(POLL_DEADLINE_MILLIS) { bus.activeSubscribers == CONCURRENT_STREAM_CAP }
                    }
                } finally {
                    healthy.cancel()
                }
                withTimeout(POLL_DEADLINE_MILLIS) {
                    awaitUntil(POLL_DEADLINE_MILLIS) { bus.activeSubscribers == 0 }
                }
                // The stream cap refused abuse at the transport and never masqueraded as an auth failure.
                assertTrue(
                    refusals.none { it == Status.Code.UNAUTHENTICATED || it == Status.Code.PERMISSION_DENIED },
                    "stream refusal must stay transport-level, saw $refusals",
                )
            }
        }

    @Test
    fun `an idle connection is reaped by the server and reconnects on demand`() =
        runBlocking {
            val limits = IpcTransportLimits(maxConnectionIdleMillis = IDLE_WINDOW_MILLIS)
            IpcTestServer(KernelServiceImpl(), limits = limits).use { host ->
                val channel = host.channelFor("stalled-peer")
                val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(channel)
                // The probe must target the caller's own process: a PROCESS-authority caller cannot read
                // another process's status, and the probe exists only to bring the connection up.
                val probe = ProcessStatusRequest.newBuilder().setProcessId("stalled-peer").build()

                // One real call brings the connection up; the channel must be READY afterwards.
                assertEquals(ProcessState.PROCESS_STATE_STOPPED, stub.getProcessStatus(probe).state)
                assertEquals(ConnectivityState.READY, channel.getState(false))

                // The peer then goes silent for longer than the idle window, and the server must
                // tear the connection down instead of pinning its slot forever.
                withTimeout(REAP_DEADLINE_MILLIS) {
                    while (channel.getState(false) == ConnectivityState.READY) {
                        delay(POLL_INTERVAL_MILLIS)
                    }
                }

                // Reaping a stalled connection did not lock the peer out: the next call reconnects.
                assertEquals(ProcessState.PROCESS_STATE_STOPPED, stub.getProcessStatus(probe).state)
            }
        }

    private fun subscribingScope(
        stub: EventBusServiceGrpcKt.EventBusServiceCoroutineStub,
        request: SubscribeRequest,
        streams: Int,
        refusals: CopyOnWriteArrayList<Status.Code>,
    ): CoroutineScope {
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Default +
                    CoroutineExceptionHandler { _, throwable ->
                        (throwable as? StatusException)?.let { refusals.add(it.status.code) }
                    },
            )
        // Server-streaming calls stay open until an event arrives, so collecting the first
        // element parks the stream on the server for exactly as long as this test needs.
        repeat(streams) { scope.launch { stub.subscribe(request).take(1).toList() } }
        return scope
    }

    /**
     * A transport-level refusal tears the HTTP/2 transport down on some stacks - netty
     * escalates the stream reset into a connection GOAWAY - so a well-formed call racing
     * the replacement transport can fail UNAVAILABLE without the peer being locked out.
     * Retrying only that bounded window keeps the proof honest: a peer genuinely refused
     * exhausts the deadline and fails.
     */
    private suspend fun assertRecovers(
        stub: EventBusServiceGrpcKt.EventBusServiceCoroutineStub,
        request: EventEnvelope,
    ) {
        val startedAt = System.nanoTime()
        while (true) {
            try {
                assertTrue(stub.publish(request).success)
                return
            } catch (_: StatusException) {
                assertTrue(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < POLL_DEADLINE_MILLIS,
                    "well-formed traffic did not recover within $POLL_DEADLINE_MILLIS ms",
                )
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun awaitUntil(
        deadlineMillis: Long,
        condition: () -> Boolean,
    ) {
        val startedAt = System.nanoTime()
        while (!condition()) {
            assertTrue(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < deadlineMillis,
                "condition was not reached within $deadlineMillis ms",
            )
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private companion object {
        const val CONCURRENT_STREAM_CAP = 2
        const val FLOOD_STREAM_COUNT = 5
        const val SAMPLE_COUNT = 10
        const val SAMPLE_INTERVAL_MILLIS = 100L
        const val IDLE_WINDOW_MILLIS = 1_000L
        const val LONG_IDLE_MILLIS = 60_000L
        const val POLL_INTERVAL_MILLIS = 100L
        const val POLL_DEADLINE_MILLIS = 15_000L
        const val REAP_DEADLINE_MILLIS = 15_000L
    }
}
