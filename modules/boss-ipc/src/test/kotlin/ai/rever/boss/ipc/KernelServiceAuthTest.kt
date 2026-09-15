package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.HealthContract
import ai.rever.boss.ipc.proto.HeartbeatPing
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessStatusRequest
import ai.rever.boss.ipc.proto.ProcessType
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.proto.ShutdownRequest
import ai.rever.boss.ipc.services.KernelServiceImpl
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias KernelStub = KernelServiceGrpcKt.KernelServiceCoroutineStub

/**
 * Real-server authorization tests for the kernel control plane.
 *
 * The server is configured exactly as KERNEL mode is: a ProcessTokenRegistry-backed
 * ProcessIdentityInterceptor sits in front of KernelServiceImpl. Anonymous and stale clients use
 * separate channels so these tests prove the transport boundary, not just direct method behavior.
 */
class KernelServiceAuthTest {
    private lateinit var registry: ProcessTokenRegistry
    private lateinit var server: Server
    private lateinit var kernelService: KernelServiceImpl
    private var port: Int = 0
    private val channels = mutableListOf<ManagedChannel>()
    private val registrations = mutableListOf<String>()
    private val shutdowns = mutableListOf<Pair<String, Boolean>>()
    private var failRegistrationCallback = false
    private var blockedRegistrationId: String? = null
    private lateinit var registrationCallbackEntered: CompletableDeferred<Unit>
    private lateinit var releaseRegistrationCallback: CompletableDeferred<Unit>

    @Before
    fun setUp() {
        registry = ProcessTokenRegistry()
        kernelService =
            KernelServiceImpl(
                onProcessRegistered = { id, _, _ ->
                    if (failRegistrationCallback) error("callback failure")
                    if (id == blockedRegistrationId) {
                        registrationCallbackEntered.complete(Unit)
                        releaseRegistrationCallback.await()
                    }
                    registrations += id
                },
                onShutdownRequested = { id, force ->
                    shutdowns += id to force
                    true
                },
                shutdownAuthorizer = { caller, target ->
                    caller == target || caller == ORCHESTRATOR_PROCESS_ID
                },
            )
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(registry))
                .addService(kernelService)
                .build()
                .start()
        port = server.port
    }

    @After
    fun tearDown() {
        channels.forEach(ManagedChannel::shutdownNow)
        server.shutdownNow()
    }

    @Test
    fun `stale registration cannot commit after credential replacement while waiting`() =
        runBlocking {
            registrationCallbackEntered = CompletableDeferred()
            releaseRegistrationCallback = CompletableDeferred()
            blockedRegistrationId = "lock-holder"

            val lockHolder = authenticatedStub("lock-holder")
            val lockHolderRegistration =
                async(start = CoroutineStart.UNDISPATCHED) {
                    lockHolder.registerProcess(registerRequest("lock-holder", "tcp://lock-holder"))
                }
            withTimeout(5_000) { registrationCallbackEntered.await() }

            val staleToken = registry.issue("respawn-during-registration")
            val stale = authenticatedStub("respawn-during-registration", staleToken)
            val staleRegistration =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        stale.registerProcess(
                            registerRequest("respawn-during-registration", "tcp://stale"),
                        )
                    }
                }

            val replacement = authenticatedStub("respawn-during-registration")
            val replacementRegistration =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        replacement.registerProcess(
                            registerRequest("respawn-during-registration", "tcp://replacement"),
                        )
                    }
                }

            releaseRegistrationCallback.complete(Unit)
            assertTrue(lockHolderRegistration.await().success)
            assertTrue(replacementRegistration.await().getOrThrow().success)

            val staleFailure = staleRegistration.await().exceptionOrNull()
            assertNotNull(staleFailure)
            assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(staleFailure).code)
            assertEquals(listOf("lock-holder", "respawn-during-registration"), registrations)
            assertEquals(2, kernelService.registeredCount)
            assertEquals(
                "respawn-during-registration",
                replacement
                    .heartbeat(flow { emit(ping("respawn-during-registration")) })
                    .toList()
                    .single()
                    .processId,
            )
        }

    @Test
    fun `anonymous callers cannot reach any kernel RPC or callback`() =
        runBlocking {
            val anonymous = anonymousStub()

            assertPermissionDenied {
                anonymous.registerProcess(registerRequest("spoofed-process", "tcp://attacker"))
            }
            assertPermissionDenied {
                anonymous.heartbeat(flow { emit(ping("spoofed-process")) }).toList()
            }
            assertPermissionDenied {
                anonymous.getProcessStatus(
                    ProcessStatusRequest.newBuilder().setProcessId("spoofed-process").build(),
                )
            }
            assertPermissionDenied { anonymous.listProcesses(Empty.getDefaultInstance()) }
            assertPermissionDenied {
                anonymous.requestShutdown(
                    ShutdownRequest.newBuilder().setProcessId("spoofed-process").build(),
                )
            }

            assertEquals(0, kernelService.registeredCount)
            assertTrue(registrations.isEmpty(), "anonymous registration must not reach the callback")
            assertTrue(shutdowns.isEmpty(), "anonymous shutdown must not reach the callback")
        }

    @Test
    fun `valid token can register heartbeat and query process state`() =
        runBlocking {
            val stub = authenticatedStub("service-a")
            assertTrue(stub.registerProcess(registerRequest("service-a", "tcp://service-a")).success)

            val pongs =
                stub
                    .heartbeat(flow { emit(ping("service-a")) })
                    .toList()
            assertEquals(1, pongs.size)
            assertEquals("service-a", pongs.single().processId)
            assertTrue(pongs.single().acknowledged)

            assertEquals(
                ai.rever.boss.ipc.proto.ProcessState.PROCESS_STATE_RUNNING,
                stub
                    .getProcessStatus(
                        ProcessStatusRequest.newBuilder().setProcessId("service-a").build(),
                    ).state,
            )
            assertEquals(1, stub.listProcesses(Empty.getDefaultInstance()).processesCount)
        }

    @Test
    fun `a caller cannot register or heartbeat as another process`() =
        runBlocking {
            val caller = authenticatedStub("process-a")

            assertPermissionDenied {
                caller.registerProcess(registerRequest("process-b", "tcp://process-b"))
            }
            assertEquals(0, kernelService.registeredCount)
            assertTrue(registrations.isEmpty())

            assertPermissionDenied {
                caller.heartbeat(flow { emit(ping("process-b")) }).toList()
            }
            assertNull(kernelService.getLastHeartbeat("process-a"))
        }

    @Test
    fun `revoked unary calls and an open heartbeat stream are rejected`() =
        runBlocking {
            val token = registry.issue("revocable")
            val stub = authenticatedStub("revocable", token)
            assertTrue(stub.registerProcess(registerRequest("revocable", "tcp://revocable")).success)

            registry.revoke("revocable")
            assertPermissionDenied {
                stub.getProcessStatus(
                    ProcessStatusRequest.newBuilder().setProcessId("revocable").build(),
                )
            }

            val replacementToken = registry.issue("stream-revocable")
            val streamStub = authenticatedStub("stream-revocable", replacementToken)
            assertTrue(
                streamStub
                    .registerProcess(registerRequest("stream-revocable", "tcp://stream"))
                    .success,
            )

            coroutineScope {
                val pings = Channel<HeartbeatPing>(Channel.UNLIMITED)
                val stream =
                    async {
                        runCatching {
                            streamStub.heartbeat(pings.consumeAsFlow()).toList()
                        }.exceptionOrNull()
                    }
                pings.send(ping("stream-revocable"))
                withTimeout(5_000) {
                    while (kernelService.getLastHeartbeat("stream-revocable") == null) delay(5)
                }
                registry.revoke("stream-revocable")
                pings.send(ping("stream-revocable"))

                val failure = withTimeout(5_000) { stream.await() }
                assertNotNull(failure)
                assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(failure).code)
                pings.close()
                Unit
            }
        }

    @Test
    fun `same credential cannot overwrite registration but a fresh respawn credential can`() =
        runBlocking {
            val firstToken = registry.issue("respawnable")
            val first = authenticatedStub("respawnable", firstToken)
            assertTrue(first.registerProcess(registerRequest("respawnable", "tcp://old")).success)

            assertStatus(Status.Code.ALREADY_EXISTS) {
                first.registerProcess(registerRequest("respawnable", "tcp://spoofed-replacement"))
            }
            assertEquals(listOf("respawnable"), registrations)

            val replacementToken = registry.issue("respawnable")
            val replacement = authenticatedStub("respawnable", replacementToken)
            assertTrue(replacement.registerProcess(registerRequest("respawnable", "tcp://new")).success)
            assertEquals(listOf("respawnable", "respawnable"), registrations)

            assertPermissionDenied {
                first.getProcessStatus(
                    ProcessStatusRequest.newBuilder().setProcessId("respawnable").build(),
                )
            }
            val pong = replacement.heartbeat(flow { emit(ping("respawnable")) }).toList().single()
            assertEquals("respawnable", pong.processId)
        }

    @Test
    fun `service cannot shut down a sibling but orchestrator can`() =
        runBlocking {
            val target = authenticatedStub("target")
            assertTrue(target.registerProcess(registerRequest("target", "tcp://target")).success)
            val service = authenticatedStub("service")
            assertTrue(service.registerProcess(registerRequest("service", "tcp://service")).success)

            assertPermissionDenied {
                service.requestShutdown(
                    ShutdownRequest
                        .newBuilder()
                        .setProcessId("target")
                        .setForce(true)
                        .build(),
                )
            }
            assertTrue(shutdowns.isEmpty())
            assertEquals(2, kernelService.registeredCount)

            val orchestrator = authenticatedStub(ORCHESTRATOR_PROCESS_ID)
            assertTrue(
                orchestrator
                    .requestShutdown(
                        ShutdownRequest
                            .newBuilder()
                            .setProcessId("target")
                            .setForce(false)
                            .setReason("approved repair")
                            .build(),
                    ).success,
            )
            assertEquals(listOf("target" to false), shutdowns)
            assertEquals(1, kernelService.registeredCount)
        }

    @Test
    fun `registration callback failure does not leak process state`() =
        runBlocking {
            failRegistrationCallback = true
            val stub = authenticatedStub("callback-failure")
            val failed = stub.registerProcess(registerRequest("callback-failure", "tcp://failed"))
            assertFalse(failed.success)
            assertEquals(0, kernelService.registeredCount)
            assertNull(kernelService.getLastHeartbeat("callback-failure"))
            assertTrue(registrations.isEmpty())

            failRegistrationCallback = false
            assertTrue(stub.registerProcess(registerRequest("callback-failure", "tcp://recovered")).success)
            assertEquals(1, kernelService.registeredCount)
        }

    private fun anonymousStub(): KernelStub = channelStub()

    private fun authenticatedStub(
        processId: String,
        token: String = registry.issue(processId),
    ): KernelStub = channelStub(ProcessTokenClientInterceptor(token))

    private fun channelStub(interceptor: io.grpc.ClientInterceptor? = null): KernelStub {
        val builder = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext()
        interceptor?.let(builder::intercept)
        val channel = builder.build()
        channels += channel
        return KernelServiceGrpcKt.KernelServiceCoroutineStub(channel)
    }

    private fun registerRequest(
        processId: String,
        address: String,
    ): RegisterProcessRequest =
        RegisterProcessRequest
            .newBuilder()
            .setManifest(
                ProcessManifest
                    .newBuilder()
                    .setProcessId(processId)
                    .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                    .setDisplayName(processId)
                    .setHealthContract(HealthContract.newBuilder().setHeartbeatIntervalMs(5000).build())
                    .build(),
            ).setIpcAddress(address)
            .build()

    private fun ping(processId: String): HeartbeatPing =
        HeartbeatPing
            .newBuilder()
            .setProcessId(processId)
            .setTimestamp(System.currentTimeMillis())
            .build()

    private suspend fun assertPermissionDenied(block: suspend () -> Unit) {
        assertStatus(Status.Code.PERMISSION_DENIED, block)
    }

    private suspend fun assertStatus(
        code: Status.Code,
        block: suspend () -> Unit,
    ) {
        val failure =
            try {
                block()
                null
            } catch (t: Throwable) {
                t
            }
        assertNotNull(failure, "expected gRPC failure $code")
        assertEquals(code, Status.fromThrowable(failure).code)
    }

    private companion object {
        const val ORCHESTRATOR_PROCESS_ID = "boss-orchestrator"
    }
}
