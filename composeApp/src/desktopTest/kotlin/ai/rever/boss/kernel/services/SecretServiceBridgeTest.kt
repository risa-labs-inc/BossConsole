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
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The password vault's kernel bridge, exercised over a real gRPC server (BossConsole#53).
 *
 * Before this bridge checked identity, any process that could open a connection to the kernel IPC
 * server - not only the plugins the host itself loaded - could read every stored password and TOTP
 * recovery code, or reshare a secret with a user or role of its choosing. The assertion that matters
 * throughout is not just "the RPC is refused" but "the provider is never reached" - a refusal that
 * still queried the vault would leak timing and existence information even without returning data.
 */
class SecretServiceBridgeTest {
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var provider: FakeSecretDataProvider
    private lateinit var server: Server
    private lateinit var authenticatedChannel: ManagedChannel
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var authenticated: SecretServiceGrpcKt.SecretServiceCoroutineStub
    private lateinit var anonymous: SecretServiceGrpcKt.SecretServiceCoroutineStub

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
        authenticatedChannel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        anonymousChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        authenticated = SecretServiceGrpcKt.SecretServiceCoroutineStub(authenticatedChannel)
        anonymous = SecretServiceGrpcKt.SecretServiceCoroutineStub(anonymousChannel)
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
    fun `every RPC is refused with no credential, and never reaches the vault`() =
        runBlocking {
            assertRefused { anonymous.getUserSecrets(SecretPaginatedRequest.getDefaultInstance()) }
            assertRefused { anonymous.getUserSecretsWithSharingInfo(SecretPaginatedRequest.getDefaultInstance()) }
            assertRefused { anonymous.searchSecrets(SearchSecretsRequest.getDefaultInstance()) }
            assertRefused { anonymous.createSecret(CreateSecretProtoRequest.getDefaultInstance()) }
            assertRefused { anonymous.updateSecret(UpdateSecretProtoRequest.getDefaultInstance()) }
            assertRefused { anonymous.deleteSecret(SecretIdRequest.getDefaultInstance()) }
            assertRefused { anonymous.getSecretShares(SecretIdRequest.getDefaultInstance()) }
            assertRefused { anonymous.shareSecret(ShareSecretProtoRequest.getDefaultInstance()) }
            assertRefused { anonymous.unshareSecret(UnshareSecretProtoRequest.getDefaultInstance()) }

            assertTrue(provider.calls.isEmpty(), "a refused call must never reach the vault provider")
        }

    @Test
    fun `an authenticated caller can read the vault`() =
        runBlocking {
            provider.secrets += fakeSecret()

            val response = authenticated.getUserSecrets(SecretPaginatedRequest.newBuilder().setLimit(10).build())

            assertEquals(1, response.secretsCount)
            assertEquals("acme.com", response.secretsList.single().website)
            assertEquals(listOf("getUserSecrets"), provider.calls)
        }

    @Test
    fun `an authenticated caller can create, share and delete a secret`() =
        runBlocking {
            val created =
                authenticated.createSecret(
                    CreateSecretProtoRequest
                        .newBuilder()
                        .setWebsite("new.example")
                        .setUsername("me")
                        .build(),
                )
            val shared =
                authenticated.shareSecret(
                    ShareSecretProtoRequest
                        .newBuilder()
                        .setSecretId("secret-1")
                        .setTargetUserId("user-2")
                        .build(),
                )
            val unshared =
                authenticated.unshareSecret(
                    UnshareSecretProtoRequest
                        .newBuilder()
                        .setSecretId("secret-1")
                        .setTargetUserId("user-2")
                        .build(),
                )
            val deleted = authenticated.deleteSecret(SecretIdRequest.newBuilder().setId("secret-1").build())

            assertTrue(created.success)
            assertTrue(shared.success)
            assertTrue(unshared.success)
            assertTrue(deleted.success)
            assertEquals(listOf("createSecret", "shareSecret", "unshareSecret", "deleteSecret"), provider.calls)
        }

    private suspend fun assertRefused(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
    }

    private fun fakeSecret(): SecretEntryData =
        SecretEntryData(
            id = "secret-1",
            website = "acme.com",
            username = "user",
            password = "hunter2",
            notes = null,
            expirationDate = null,
            tags = emptyList(),
            metadata = null,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )

    /** Records every method it was actually asked to perform, so a refusal can be proven silent. */
    private class FakeSecretDataProvider : SecretDataProvider {
        val calls = mutableListOf<String>()
        val secrets = mutableListOf<SecretEntryData>()

        override suspend fun getUserSecrets(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsData> {
            calls += "getUserSecrets"
            return Result.success(PaginatedSecretsData(data = secrets.toList(), hasMore = false))
        }

        override suspend fun getUserSecretsWithSharingInfo(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithSharingData> {
            calls += "getUserSecretsWithSharingInfo"
            return Result.success(PaginatedSecretsWithSharingData(data = emptyList(), hasMore = false))
        }

        override suspend fun searchSecrets(
            query: String,
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsData> {
            calls += "searchSecrets"
            return Result.success(PaginatedSecretsData(data = emptyList(), hasMore = false))
        }

        override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> {
            calls += "createSecret"
            return Result.success(Unit)
        }

        override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> {
            calls += "updateSecret"
            return Result.success(Unit)
        }

        override suspend fun deleteSecret(id: String): Result<Unit> {
            calls += "deleteSecret"
            return Result.success(Unit)
        }

        override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> {
            calls += "getSecretShares"
            return Result.success(emptyList())
        }

        override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> {
            calls += "shareSecret"
            return Result.success(Unit)
        }

        override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> {
            calls += "unshareSecret"
            return Result.success(Unit)
        }
    }

    private companion object {
        const val CALLER = "secret-manager-plugin"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
