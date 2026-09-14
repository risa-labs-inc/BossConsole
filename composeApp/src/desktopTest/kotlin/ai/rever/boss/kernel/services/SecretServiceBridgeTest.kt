package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.services.CreateSecretProtoRequest
import ai.rever.boss.ipc.proto.services.SearchSecretsRequest
import ai.rever.boss.ipc.proto.services.SecretIdRequest
import ai.rever.boss.ipc.proto.services.SecretPaginatedRequest
import ai.rever.boss.ipc.proto.services.SecretServiceGrpcKt
import ai.rever.boss.ipc.proto.services.ShareSecretProtoRequest
import ai.rever.boss.ipc.proto.services.UnshareSecretProtoRequest
import ai.rever.boss.ipc.proto.services.UpdateSecretProtoRequest
import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import io.grpc.HandlerRegistry
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerMethodDefinition
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The out-of-process secret vault surface, exercised over a real gRPC server with a real client -
 * the same style [PluginUIServiceBridgeTest] uses for the transport [ProcessIdentityInterceptor]
 * secures (BossConsole#53).
 *
 * Every test that expects a refusal also asserts [FakeSecretDataProvider] was never called: a
 * `PERMISSION_DENIED` that still reached the vault would defeat the point of this bridge existing.
 */
class SecretServiceBridgeTest {
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var provider: FakeSecretDataProvider
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var authenticated: SecretServiceGrpcKt.SecretServiceCoroutineStub
    private val extraChannels = mutableListOf<ManagedChannel>()

    @BeforeTest
    fun setUp() {
        tokenRegistry = ProcessTokenRegistry()
        provider = FakeSecretDataProvider()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(SecretServiceBridge(provider))
                .build()
                .start()
        channel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(DEFAULT_PROCESS)))
                .build()
        authenticated = SecretServiceGrpcKt.SecretServiceCoroutineStub(channel)
    }

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        channel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        extraChannels.forEach { it.shutdownNow() }
        extraChannels.forEach { it.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        server.shutdownNow()
        server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** An unauthenticated stub against this test's server - no credential attached at all. */
    private fun anonymousCaller(): SecretServiceGrpcKt.SecretServiceCoroutineStub {
        val unauthenticatedChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        extraChannels += unauthenticatedChannel
        return SecretServiceGrpcKt.SecretServiceCoroutineStub(unauthenticatedChannel)
    }

    @Test
    fun `an authenticated caller can list secrets`() =
        runBlocking {
            val response = authenticated.getUserSecrets(SecretPaginatedRequest.newBuilder().setLimit(10).build())
            assertTrue(response.success)
            assertEquals(1, provider.getUserSecretsCalls.get())
        }

    @Test
    fun `an anonymous caller is refused and never reaches the provider`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().getUserSecrets(SecretPaginatedRequest.newBuilder().build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.getUserSecretsCalls.get())
        }

    @Test
    fun `getUserSecretsWithSharingInfo refuses an anonymous caller`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().getUserSecretsWithSharingInfo(SecretPaginatedRequest.newBuilder().build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.getUserSecretsWithSharingCalls.get())
        }

    @Test
    fun `searchSecrets refuses an anonymous caller`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().searchSecrets(SearchSecretsRequest.newBuilder().setQuery("q").build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.searchSecretsCalls.get())
        }

    @Test
    fun `createSecret refuses an anonymous caller and never reaches the vault`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().createSecret(
                        CreateSecretProtoRequest.newBuilder().setWebsite("evil.example").build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.createSecretCalls.get())
        }

    @Test
    fun `createSecret succeeds for an authenticated caller`() =
        runBlocking {
            val result =
                authenticated.createSecret(
                    CreateSecretProtoRequest
                        .newBuilder()
                        .setWebsite("example.com")
                        .setUsername("me")
                        .build(),
                )
            assertTrue(result.success)
            assertEquals(1, provider.createSecretCalls.get())
        }

    @Test
    fun `deleteSecret refuses an anonymous caller and never reaches the vault`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().deleteSecret(SecretIdRequest.newBuilder().setId("s1").build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.deleteSecretCalls.get())
        }

    @Test
    fun `updateSecret refuses an anonymous caller and never reaches the vault`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().updateSecret(
                        UpdateSecretProtoRequest.newBuilder().setSecretId("s1").build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.updateSecretCalls.get())
        }

    @Test
    fun `a bridge registered after start is still gated by the interceptor`() =
        runBlocking {
            // Production (KernelBootstrap.registerPluginServices) adds these bridges to an
            // already-started BossIpcServer, i.e. into its fallback HandlerRegistry instead of
            // the build-time registry. Pin the identity gate on that path: if interceptors ever
            // stopped covering fallback-resolved methods, the vault would be reachable
            // anonymously instead of refused.
            val lateServices = SingleServiceRegistry(SecretServiceBridge(provider).bindService())
            val lateServer =
                ServerBuilder
                    .forPort(0)
                    .fallbackHandlerRegistry(lateServices)
                    .intercept(ProcessIdentityInterceptor(tokenRegistry))
                    .build()
                    .start()
            try {
                val lateChannel =
                    ManagedChannelBuilder.forAddress("localhost", lateServer.port).usePlaintext().build()
                extraChannels += lateChannel
                val lateAnonymous = SecretServiceGrpcKt.SecretServiceCoroutineStub(lateChannel)
                // A distinct process id: `tokenRegistry.issue` replaces and invalidates whatever
                // token DEFAULT_PROCESS already held, which would silently kill the credential
                // `authenticated` (from setUp) is carrying for the rest of this test class.
                val lateAuthChannel =
                    ManagedChannelBuilder
                        .forAddress("localhost", lateServer.port)
                        .usePlaintext()
                        .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue("$DEFAULT_PROCESS.late")))
                        .build()
                extraChannels += lateAuthChannel
                val lateAuthenticated = SecretServiceGrpcKt.SecretServiceCoroutineStub(lateAuthChannel)

                val failure =
                    assertFailsWith<StatusException> {
                        lateAnonymous.getUserSecrets(SecretPaginatedRequest.newBuilder().build())
                    }
                assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
                assertEquals(0, provider.getUserSecretsCalls.get())

                val response = lateAuthenticated.getUserSecrets(SecretPaginatedRequest.newBuilder().build())
                assertTrue(response.success)
                assertEquals(1, provider.getUserSecretsCalls.get())
            } finally {
                lateServer.shutdownNow()
                assertTrue(lateServer.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            }
        }

    @Test
    fun `a revoked token is refused the same as no credential at all`() =
        runBlocking {
            // The post-reapChildren / post-respawn case: identityFor returns null for a token
            // the registry no longer holds, same as for an absent one - but the caller here is a
            // live process that had a valid credential moments ago, not an anonymous one.
            val revocableToken = tokenRegistry.issue("$DEFAULT_PROCESS.revocable")
            val revocableChannel =
                ManagedChannelBuilder
                    .forAddress("localhost", server.port)
                    .usePlaintext()
                    .intercept(ProcessTokenClientInterceptor(revocableToken))
                    .build()
            extraChannels += revocableChannel
            val revocable = SecretServiceGrpcKt.SecretServiceCoroutineStub(revocableChannel)

            // Confirm the token actually worked before revoking it.
            assertTrue(revocable.getUserSecrets(SecretPaginatedRequest.newBuilder().build()).success)
            assertEquals(1, provider.getUserSecretsCalls.get())

            tokenRegistry.revoke("$DEFAULT_PROCESS.revocable")

            val failure =
                assertFailsWith<StatusException> {
                    revocable.getUserSecrets(SecretPaginatedRequest.newBuilder().build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(1, provider.getUserSecretsCalls.get(), "the revoked call must not have reached the vault")
        }

    @Test
    fun `shareSecret and unshareSecret refuse an anonymous caller`() =
        runBlocking {
            val shareFailure =
                assertFailsWith<StatusException> {
                    anonymousCaller().shareSecret(ShareSecretProtoRequest.newBuilder().setSecretId("s1").build())
                }
            val unshareFailure =
                assertFailsWith<StatusException> {
                    anonymousCaller().unshareSecret(UnshareSecretProtoRequest.newBuilder().setSecretId("s1").build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, shareFailure.status.code)
            assertEquals(Status.Code.PERMISSION_DENIED, unshareFailure.status.code)
            assertEquals(0, provider.shareSecretCalls.get())
            assertEquals(0, provider.unshareSecretCalls.get())
        }

    @Test
    fun `getSecretShares refuses an anonymous caller`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().getSecretShares(SecretIdRequest.newBuilder().setId("s1").build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.getSecretSharesCalls.get())
        }

    companion object {
        private const val DEFAULT_PROCESS = "ai.rever.boss.plugin.dynamic.test-plugin"
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}

/**
 * A minimal stand-in for [BossIpcServer]'s late-service registry (a grpc-util
 * MutableHandlerRegistry, which is not on the desktopTest classpath): routes every lookup to a
 * single service, keyed by full method name exactly like MutableHandlerRegistry does. In grpc
 * 1.84 the fallback registry is consulted as lookupMethod(fullMethodName, authority). Pins that
 * builder-level interceptors also cover methods resolved through the fallback registry.
 */
private class SingleServiceRegistry(
    private val service: ServerServiceDefinition,
) : HandlerRegistry() {
    override fun getServices(): List<ServerServiceDefinition> = listOf(service)

    override fun lookupMethod(
        fullMethodName: String,
        authority: String?,
    ): ServerMethodDefinition<*, *>? = service.getMethod(fullMethodName)
}

/** Records every call so a refusal test can assert the vault was never actually reached. */
private class FakeSecretDataProvider : SecretDataProvider {
    val getUserSecretsCalls = AtomicInteger(0)
    val getUserSecretsWithSharingCalls = AtomicInteger(0)
    val searchSecretsCalls = AtomicInteger(0)
    val createSecretCalls = AtomicInteger(0)
    val updateSecretCalls = AtomicInteger(0)
    val deleteSecretCalls = AtomicInteger(0)
    val getSecretSharesCalls = AtomicInteger(0)
    val shareSecretCalls = AtomicInteger(0)
    val unshareSecretCalls = AtomicInteger(0)

    override suspend fun getUserSecrets(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsData> {
        getUserSecretsCalls.incrementAndGet()
        return Result.success(PaginatedSecretsData(data = emptyList(), hasMore = false))
    }

    override suspend fun getUserSecretsWithSharingInfo(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsWithSharingData> {
        getUserSecretsWithSharingCalls.incrementAndGet()
        return Result.success(PaginatedSecretsWithSharingData(data = emptyList(), hasMore = false))
    }

    override suspend fun searchSecrets(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsData> {
        searchSecretsCalls.incrementAndGet()
        return Result.success(PaginatedSecretsData(data = emptyList(), hasMore = false))
    }

    override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> {
        createSecretCalls.incrementAndGet()
        return Result.success(Unit)
    }

    override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> {
        updateSecretCalls.incrementAndGet()
        return Result.success(Unit)
    }

    override suspend fun deleteSecret(id: String): Result<Unit> {
        deleteSecretCalls.incrementAndGet()
        return Result.success(Unit)
    }

    override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> {
        getSecretSharesCalls.incrementAndGet()
        return Result.success(emptyList())
    }

    override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> {
        shareSecretCalls.incrementAndGet()
        return Result.success(Unit)
    }

    override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> {
        unshareSecretCalls.incrementAndGet()
        return Result.success(Unit)
    }
}
