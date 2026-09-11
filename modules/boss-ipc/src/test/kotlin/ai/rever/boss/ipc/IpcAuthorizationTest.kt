package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.EventBusServiceGrpcKt
import ai.rever.boss.ipc.proto.EventEnvelope
import ai.rever.boss.ipc.proto.HeartbeatPing
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessStatusRequest
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.proto.ShutdownRequest
import ai.rever.boss.ipc.proto.StateKey
import ai.rever.boss.ipc.proto.StateServiceGrpcKt
import ai.rever.boss.ipc.proto.StateUpdate
import ai.rever.boss.ipc.proto.StateValue
import ai.rever.boss.ipc.proto.SubscribeRequest
import ai.rever.boss.ipc.services.EventBusServiceImpl
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.ipc.services.StateServiceImpl
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IpcAuthorizationTest {
    @Test
    fun `missing malformed unknown revoked and replaced credentials never reach registration`() =
        runBlocking {
            val calls = AtomicInteger()
            val kernel = KernelServiceImpl(onProcessRegistered = { _, _, _ -> calls.incrementAndGet() })
            IpcTestServer(kernel).use { host ->
                val revoked = host.registry.issue("revoked", expectedAddress = ADDRESS)
                host.registry.revoke("revoked")
                val replaced = host.registry.issue("current", expectedAddress = ADDRESS)
                val current = host.registry.issue("current", expectedAddress = ADDRESS)
                for (token in listOf(null, "", "not-a-credential", "0".repeat(64), revoked, replaced)) {
                    val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelWithToken(token))
                    refused(Status.Code.UNAUTHENTICATED) { stub.registerProcess(registration("current")) }
                }
                assertEquals(0, calls.get())
                val authorized = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelWithToken(current))
                assertTrue(authorized.registerProcess(registration("current")).success)
                assertEquals(1, calls.get())
            }
        }

    @Test
    fun `valid caller cannot register another process redirect its endpoint or forge its heartbeat`() =
        runBlocking {
            val calls = AtomicInteger()
            val kernel = KernelServiceImpl(onProcessRegistered = { _, _, _ -> calls.incrementAndGet() })
            IpcTestServer(kernel).use { host ->
                val stub = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor("alpha", ADDRESS))
                refused(Status.Code.PERMISSION_DENIED) { stub.registerProcess(registration("beta")) }
                refused(Status.Code.PERMISSION_DENIED) {
                    stub.registerProcess(registration("alpha").toBuilder().setIpcAddress("tcp://127.0.0.1:1").build())
                }
                refused(Status.Code.PERMISSION_DENIED) {
                    stub.heartbeat(flowOf(HeartbeatPing.newBuilder().setProcessId("beta").build())).toList()
                }
                assertEquals(0, calls.get())
                assertEquals(null, kernel.getLastHeartbeat("beta"))
                assertTrue(stub.registerProcess(registration("alpha")).success)
                val ownPing = HeartbeatPing.newBuilder().setProcessId("alpha").build()
                assertTrue(
                    stub
                        .heartbeat(flowOf(ownPing))
                        .toList()
                        .single()
                        .acknowledged,
                )
                assertEquals(1, calls.get())
            }
        }

    @Test
    fun `process control requires ownership or an explicitly issued supervisor privilege`() =
        runBlocking {
            val calls = AtomicInteger()
            val kernel =
                KernelServiceImpl(onShutdownRequested = { _, _ ->
                    calls.incrementAndGet()
                    true
                })
            IpcTestServer(kernel).use { host ->
                val ordinary = KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor("alpha"))
                val target = ShutdownRequest.newBuilder().setProcessId("beta").build()
                refused(Status.Code.PERMISSION_DENIED) { ordinary.requestShutdown(target) }
                refused(Status.Code.PERMISSION_DENIED) {
                    ordinary.getProcessStatus(ProcessStatusRequest.newBuilder().setProcessId("beta").build())
                }
                assertEquals(0, calls.get())
                assertTrue(ordinary.requestShutdown(target.toBuilder().setProcessId("alpha").build()).success)
                val supervisorChannel = host.channelFor("supervisor", authority = ProcessAuthority.SUPERVISOR)
                val supervisor = KernelServiceGrpcKt.KernelServiceCoroutineStub(supervisorChannel)
                assertTrue(supervisor.requestShutdown(target).success)
                assertEquals(2, calls.get())
            }
        }

    @Test
    fun `state ownership cannot be selected with sourceProcess and shared host state is read only`() =
        runBlocking {
            val state = StateServiceImpl()
            IpcTestServer(state).use { host ->
                val alpha = StateServiceGrpcKt.StateServiceCoroutineStub(host.channelFor("alpha"))
                val beta = StateServiceGrpcKt.StateServiceCoroutineStub(host.channelFor("beta"))
                val controller =
                    StateServiceGrpcKt.StateServiceCoroutineStub(
                        host.channelFor("host", authority = ProcessAuthority.HOST),
                    )
                val key = StateKey.newBuilder().setKey("private").build()
                val update =
                    StateUpdate
                        .newBuilder()
                        .setKey(key.key)
                        .setValue(ByteString.copyFromUtf8("secret"))
                        .setSourceProcess("host")
                        .build()
                alpha.setState(update)
                refused(Status.Code.PERMISSION_DENIED) { beta.getState(key) }
                refused(Status.Code.PERMISSION_DENIED) { beta.setState(update) }
                refused(Status.Code.PERMISSION_DENIED) { beta.watchState(key).toList() }
                assertEquals(0, beta.listStateKeys(Empty.getDefaultInstance()).keysCount)
                assertEquals(
                    "alpha",
                    controller
                        .listStateKeys(Empty.getDefaultInstance())
                        .keysList
                        .single()
                        .ownerProcess,
                )
                assertEquals("secret", controller.getState(key).value.toStringUtf8())
                state.setLocal("shared", "visible".toByteArray(), "text")
                val shared = StateKey.newBuilder().setKey("shared").build()
                assertEquals("visible", beta.getState(shared).value.toStringUtf8())
                refused(Status.Code.PERMISSION_DENIED) { beta.setState(update.toBuilder().setKey("shared").build()) }
            }
        }

    @Test
    fun `revocation cancels an idle stream and replacement cannot read the previous instances private state`() =
        runBlocking {
            withTimeout(15_000) {
                IpcTestServer(StateServiceImpl()).use { host ->
                    val alpha = StateServiceGrpcKt.StateServiceCoroutineStub(host.channelFor("alpha"))
                    val key = StateKey.newBuilder().setKey("private").build()
                    alpha.setState(
                        StateUpdate
                            .newBuilder()
                            .setKey(key.key)
                            .setValue(ByteString.copyFromUtf8("initial"))
                            .build(),
                    )
                    val received = Channel<StateValue>(1)
                    val subscription =
                        async {
                            refused(Status.Code.UNAUTHENTICATED) { alpha.watchState(key).collect { received.send(it) } }
                        }
                    assertEquals("initial", received.receive().value.toStringUtf8())
                    val replacement = StateServiceGrpcKt.StateServiceCoroutineStub(host.channelFor("alpha"))
                    subscription.await()
                    refused(Status.Code.PERMISSION_DENIED) { replacement.getState(key) }
                    refused(Status.Code.UNAUTHENTICATED) { alpha.getState(key) }
                }
            }
        }

    @Test
    fun `broadcast preserves analytics subscriber labels while deriving the event source from its caller`() =
        runBlocking {
            withTimeout(15_000) {
                val events = EventBusServiceImpl()
                IpcTestServer(events).use { host ->
                    val alpha = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(host.channelFor("alpha"))
                    val analyticsChannel = host.channelFor("analytics-process")
                    val analytics = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(analyticsChannel)
                    val received = Channel<EventEnvelope>(Channel.UNLIMITED)
                    val subscription =
                        async {
                            val request = SubscribeRequest.newBuilder().setSubscriberId("analytics").build()
                            val eventsToRead = analytics.subscribe(request)
                            refused(Status.Code.UNAUTHENTICATED) { eventsToRead.collect { received.send(it) } }
                        }
                    val envelope =
                        EventEnvelope
                            .newBuilder()
                            .setEventType("test")
                            .setSourceProcess("host")
                            .build()
                    while (received.isEmpty) {
                        alpha.publish(envelope)
                        delay(20)
                    }
                    assertEquals("alpha", received.receive().sourceProcess)
                    host.registry.revoke("analytics-process")
                    subscription.await()
                    while (events.activeSubscribers != 0) delay(10)
                    assertEquals(0, events.activeSubscribers)
                }
            }
        }

    private fun registration(processId: String): RegisterProcessRequest =
        RegisterProcessRequest
            .newBuilder()
            .setManifest(ProcessManifest.newBuilder().setProcessId(processId))
            .setIpcAddress(ADDRESS)
            .build()

    private suspend fun refused(
        code: Status.Code,
        action: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<StatusException> { action() }
        assertEquals(code, failure.status.code)
    }

    private companion object {
        const val ADDRESS = "tcp://127.0.0.1:59000"
    }
}
