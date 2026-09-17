package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ExecuteConfigRequest
import ai.rever.boss.ipc.proto.services.RunConfigType
import ai.rever.boss.ipc.proto.services.RunConfigurationProto
import ai.rever.boss.ipc.proto.services.RunConfigurationServiceGrpcKt
import ai.rever.boss.ipc.proto.services.ScanProjectRequest
import ai.rever.boss.plugin.api.LanguageData
import ai.rever.boss.plugin.api.RunConfigurationData
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.RunConfigurationTypeData
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The run-configuration bridge, exercised over a real gRPC server (BossConsole#53).
 *
 * `Execute` is the sharpest RPC on this server: [ExecuteConfigRequest] carries a full command,
 * argument string, working directory and environment map that reach
 * [ai.rever.boss.run.RunExecutionService] - "opens a terminal and runs the command," per that
 * class's own KDoc. Requiring a verified caller is necessary but not sufficient here, because the
 * dangerous fields travel in the request itself rather than as a path or id into something the
 * host already trusts. So the test that matters most below is not "an anonymous caller is
 * refused" (every bridge has that) but "an authenticated caller cannot smuggle an arbitrary
 * command past a real configuration's id" - proving the bridge executes its own stored copy of a
 * known configuration, never the caller's.
 */
class RunConfigServiceBridgeTest {
    private val tls = IpcTlsIdentity.create()
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var provider: FakeRunConfigurationDataProvider
    private lateinit var server: Server
    private lateinit var authenticatedChannel: ManagedChannel
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var authenticated: RunConfigurationServiceGrpcKt.RunConfigurationServiceCoroutineStub
    private lateinit var anonymous: RunConfigurationServiceGrpcKt.RunConfigurationServiceCoroutineStub

    @BeforeTest
    fun setUp() {
        tokenRegistry = ProcessTokenRegistry()
        provider = FakeRunConfigurationDataProvider()
        server =
            NettyServerBuilder
                .forPort(0)
                .sslContext(tls.serverContext())
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(RunConfigServiceBridge(provider))
                .build()
                .start()
        authenticatedChannel =
            NettyChannelBuilder
                .forAddress("localhost", server.port)
                .sslContext(IpcTlsIdentity.clientContext(tls.certificateBase64))
                .overrideAuthority(IpcTlsIdentity.AUTHORITY)
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        anonymousChannel =
            NettyChannelBuilder
                .forAddress(
                    "localhost",
                    server.port,
                ).sslContext(IpcTlsIdentity.clientContext(tls.certificateBase64))
                .overrideAuthority(IpcTlsIdentity.AUTHORITY)
                .build()
        authenticated = RunConfigurationServiceGrpcKt.RunConfigurationServiceCoroutineStub(authenticatedChannel)
        anonymous = RunConfigurationServiceGrpcKt.RunConfigurationServiceCoroutineStub(anonymousChannel)
    }

    @AfterTest
    fun tearDown() {
        authenticatedChannel.shutdownNow()
        authenticatedChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        anonymousChannel.shutdownNow()
        anonymousChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        server.shutdownNow()
        server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `every RPC is refused with no credential, and never reaches the provider`() =
        runBlocking {
            assertUnauthenticated {
                anonymous.watchDetectedConfigurations(Empty.getDefaultInstance()).take(1).toList()
            }
            assertUnauthenticated { anonymous.watchIsScanning(Empty.getDefaultInstance()).take(1).toList() }
            assertUnauthenticated { anonymous.watchLastError(Empty.getDefaultInstance()).take(1).toList() }
            assertUnauthenticated {
                anonymous.scanProject(ScanProjectRequest.newBuilder().setProjectPath("/tmp").build())
            }
            assertUnauthenticated {
                anonymous.execute(ExecuteConfigRequest.newBuilder().setConfiguration(protoConfig("known-1")).build())
            }
            assertUnauthenticated { anonymous.clearError(Empty.getDefaultInstance()) }

            assertTrue(provider.calls.isEmpty(), "a refused call must never reach the provider")
        }

    @Test
    fun `an authenticated caller can watch, scan and clear errors`() =
        runBlocking {
            provider.setConfigurations(listOf(knownConfig("known-1")))

            val configs =
                authenticated
                    .watchDetectedConfigurations(Empty.getDefaultInstance())
                    .take(1)
                    .toList()
                    .single()
            assertEquals(1, configs.configurationsCount)
            assertEquals("known-1", configs.configurationsList.single().id)

            authenticated.scanProject(
                ScanProjectRequest
                    .newBuilder()
                    .setProjectPath("/repo")
                    .setWindowId("w1")
                    .build(),
            )
            authenticated.clearError(Empty.getDefaultInstance())

            assertEquals(listOf("scanProject", "clearError"), provider.calls)
            assertEquals("/repo" to "w1", provider.scanned.single())
        }

    @Test
    fun `execute runs the provider's own copy of a known configuration, not the caller's`() =
        runBlocking {
            provider.setConfigurations(listOf(knownConfig("known-1", command = "echo", arguments = "safe")))

            // The request names a real id but carries a completely different, attacker-chosen
            // command - if the bridge trusted this payload, this is the exploit.
            val tampered = protoConfig("known-1", command = "rm", arguments = "-rf /")
            authenticated.execute(
                ExecuteConfigRequest
                    .newBuilder()
                    .setConfiguration(tampered)
                    .setWindowId("w1")
                    .build(),
            )

            assertEquals("w1", provider.executionWindows.single())
            val executed = provider.executed.single()
            assertEquals("echo", executed.command, "must execute the provider's stored command")
            assertEquals("safe", executed.arguments, "must execute the provider's stored arguments")
        }

    @Test
    fun `execute with an id the provider does not know is refused and never runs anything`() =
        runBlocking {
            provider.setConfigurations(listOf(knownConfig("known-1")))

            val failure =
                assertFailsWith<StatusException> {
                    authenticated.execute(
                        ExecuteConfigRequest
                            .newBuilder()
                            .setConfiguration(protoConfig("not-a-real-id", command = "rm", arguments = "-rf /"))
                            .setWindowId("w1")
                            .build(),
                    )
                }

            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertTrue(provider.executed.isEmpty(), "an unknown id must never reach RunExecutionService")
        }

    @Test
    fun `revoked credentials cannot invoke a unary operation`() =
        runBlocking {
            tokenRegistry.revoke(CALLER)
            val failure = assertFailsWith<StatusException> { authenticated.clearError(Empty.getDefaultInstance()) }
            assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `revoked watcher cannot receive a later snapshot`() =
        runBlocking {
            withTimeout(10_000) {
                supervisorScope {
                    val received = Channel<Unit>(Channel.UNLIMITED)
                    val watching =
                        async {
                            authenticated.watchIsScanning(Empty.getDefaultInstance()).collect { received.send(Unit) }
                        }
                    try {
                        received.receive()
                        tokenRegistry.revoke(CALLER)
                        provider.setScanning(true)
                        val failure = assertFailsWith<StatusException> { watching.await() }
                        assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
                        assertTrue(received.tryReceive().isFailure, "revocation must prevent the next snapshot")
                    } finally {
                        watching.cancel()
                        received.close()
                    }
                }
            }
        }

    @Test
    fun `blank ids never authorize a provider configuration`() =
        runBlocking {
            provider.setConfigurations(listOf(knownConfig(""), knownConfig(" ")))
            assertFailsWithPermissionDenied { authenticated.execute(ExecuteConfigRequest.getDefaultInstance()) }
            assertFailsWithPermissionDenied {
                authenticated.execute(ExecuteConfigRequest.newBuilder().setConfiguration(protoConfig(" ")).build())
            }
            assertTrue(provider.executed.isEmpty())
        }

    @Test
    fun `an observed id is refused during and after a rescan`() =
        runBlocking {
            provider.setConfigurations(listOf(knownConfig("before-scan")))
            val observed =
                authenticated
                    .watchDetectedConfigurations(Empty.getDefaultInstance())
                    .take(1)
                    .toList()
                    .single()
            val request =
                ExecuteConfigRequest.newBuilder().setConfiguration(observed.configurationsList.single()).build()
            provider.setScanning(true)
            provider.setConfigurations(emptyList())
            assertFailsWithPermissionDenied { authenticated.execute(request) }
            provider.setConfigurations(listOf(knownConfig("after-scan")))
            provider.setScanning(false)
            assertFailsWithPermissionDenied { authenticated.execute(request) }
            assertTrue(provider.executed.isEmpty(), "a rescan must never select a replacement execution silently")
        }

    private suspend fun assertUnauthenticated(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
    }

    private suspend fun assertFailsWithPermissionDenied(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
    }

    private fun knownConfig(
        id: String,
        command: String = "kotlin",
        arguments: String = "",
    ): RunConfigurationData =
        RunConfigurationData(
            id = id,
            name = id,
            type = RunConfigurationTypeData.MAIN_FUNCTION,
            filePath = "/repo/Main.kt",
            lineNumber = 1,
            language = LanguageData.KOTLIN,
            command = command,
            workingDirectory = "/repo",
            environmentVariables = emptyMap(),
            arguments = arguments,
            isAutoDetected = true,
            timestamp = 0L,
        )

    private fun protoConfig(
        id: String,
        command: String = "kotlin",
        arguments: String = "",
    ): RunConfigurationProto =
        RunConfigurationProto
            .newBuilder()
            .setId(id)
            .setName(id)
            .setType(RunConfigType.RUN_CONFIG_TYPE_MAIN_FUNCTION)
            .setCommand(command)
            .setArguments(arguments)
            .build()

    /** Records every method it was actually asked to perform, so a refusal can be proven silent. */
    private class FakeRunConfigurationDataProvider : RunConfigurationDataProvider {
        val calls = mutableListOf<String>()
        val executed = mutableListOf<RunConfigurationData>()
        val scanned = mutableListOf<Pair<String, String>>()
        val executionWindows = mutableListOf<String>()
        private val _detectedConfigurations = MutableStateFlow<List<RunConfigurationData>>(emptyList())
        override val detectedConfigurations: StateFlow<List<RunConfigurationData>> = _detectedConfigurations
        private val _isScanning = MutableStateFlow(false)
        override val isScanning: StateFlow<Boolean> = _isScanning
        private val _lastError = MutableStateFlow<String?>(null)
        override val lastError: StateFlow<String?> = _lastError

        fun setScanning(scanning: Boolean) {
            _isScanning.value = scanning
        }

        fun setConfigurations(configs: List<RunConfigurationData>) {
            _detectedConfigurations.value = configs
        }

        override suspend fun scanProject(
            projectPath: String,
            windowId: String,
        ) {
            calls += "scanProject"
            scanned += projectPath to windowId
        }

        override suspend fun execute(
            config: RunConfigurationData,
            windowId: String,
        ) {
            calls += "execute"
            executed += config
            executionWindows += windowId
        }

        override suspend fun clearError() {
            calls += "clearError"
        }
    }

    private companion object {
        const val CALLER = "run-panel-plugin"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
