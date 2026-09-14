package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.CreatePermissionRequest
import ai.rever.boss.ipc.proto.services.CreateRoleRequest
import ai.rever.boss.ipc.proto.services.PermissionNameRequest
import ai.rever.boss.ipc.proto.services.RoleManagementServiceGrpcKt
import ai.rever.boss.ipc.proto.services.RoleNameRequest
import ai.rever.boss.ipc.proto.services.RolePermissionRequest
import ai.rever.boss.plugin.api.PermissionInfoData
import ai.rever.boss.plugin.api.RoleInfoData
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.api.RoleWithPermissionsData
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
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
 * The out-of-process RBAC surface, exercised over a real gRPC server with a real client - same style
 * as [SecretServiceBridgeTest] (BossConsole#53).
 *
 * Every refusal test also asserts [FakeRoleManagementProvider] was never called: an unattributed
 * caller enumerating or mutating roles/permissions is exactly the exposure this closes.
 */
class RoleManagementServiceBridgeTest {
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var provider: FakeRoleManagementProvider
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var authenticated: RoleManagementServiceGrpcKt.RoleManagementServiceCoroutineStub
    private val extraChannels = mutableListOf<ManagedChannel>()

    @BeforeTest
    fun setUp() {
        tokenRegistry = ProcessTokenRegistry()
        provider = FakeRoleManagementProvider()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(RoleManagementServiceBridge(provider))
                .build()
                .start()
        channel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(DEFAULT_PROCESS)))
                .build()
        authenticated = RoleManagementServiceGrpcKt.RoleManagementServiceCoroutineStub(channel)
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
    private fun anonymousCaller(): RoleManagementServiceGrpcKt.RoleManagementServiceCoroutineStub {
        val unauthenticatedChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        extraChannels += unauthenticatedChannel
        return RoleManagementServiceGrpcKt.RoleManagementServiceCoroutineStub(unauthenticatedChannel)
    }

    @Test
    fun `an authenticated caller can list roles`() =
        runBlocking {
            authenticated.getAllRoles(Empty.getDefaultInstance())
            assertEquals(1, provider.getAllRolesCalls.get())
        }

    @Test
    fun `an anonymous caller is refused and never reaches the provider`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().getAllRoles(Empty.getDefaultInstance())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.getAllRolesCalls.get())
        }

    @Test
    fun `getAllPermissions refuses an anonymous caller`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> { anonymousCaller().getAllPermissions(Empty.getDefaultInstance()) }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.getAllPermissionsCalls.get())
        }

    @Test
    fun `createRole succeeds for an authenticated caller`() =
        runBlocking {
            val response = authenticated.createRole(CreateRoleRequest.newBuilder().setName("editor").build())
            assertEquals("editor", response.role.name)
            assertEquals(1, provider.createRoleCalls.get())
        }

    @Test
    fun `createRole refuses an anonymous caller and never mints a role`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().createRole(CreateRoleRequest.newBuilder().setName("rogue-admin").build())
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.createRoleCalls.get())
        }

    @Test
    fun `createPermission refuses an anonymous caller`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().createPermission(
                        CreatePermissionRequest.newBuilder().setName("secrets.read").build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.createPermissionCalls.get())
        }

    @Test
    fun `deleteRole and deletePermission refuse an anonymous caller`() =
        runBlocking {
            val roleFailure =
                assertFailsWith<StatusException> {
                    anonymousCaller().deleteRole(RoleNameRequest.newBuilder().setName("admin").build())
                }
            val permissionFailure =
                assertFailsWith<StatusException> {
                    anonymousCaller().deletePermission(
                        PermissionNameRequest.newBuilder().setName("secrets.read").build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, roleFailure.status.code)
            assertEquals(Status.Code.PERMISSION_DENIED, permissionFailure.status.code)
            assertEquals(0, provider.deleteRoleCalls.get())
            assertEquals(0, provider.deletePermissionCalls.get())
        }

    @Test
    fun `assignPermissionToRole refuses an anonymous caller - no silent privilege grant`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().assignPermissionToRole(
                        RolePermissionRequest
                            .newBuilder()
                            .setRoleName("admin")
                            .setPermissionName("secrets.read")
                            .build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.assignPermissionToRoleCalls.get())
        }

    @Test
    fun `removePermissionFromRole refuses an anonymous caller`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymousCaller().removePermissionFromRole(
                        RolePermissionRequest
                            .newBuilder()
                            .setRoleName("admin")
                            .setPermissionName("secrets.read")
                            .build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(0, provider.removePermissionFromRoleCalls.get())
        }

    @Test
    fun `getRolePermissions and name validation refuse an anonymous caller and never reach the provider`() =
        runBlocking {
            val rolePermissionsFailure =
                assertFailsWith<StatusException> {
                    anonymousCaller().getRolePermissions(RoleNameRequest.newBuilder().setName("admin").build())
                }
            val validateRoleFailure =
                assertFailsWith<StatusException> {
                    anonymousCaller().validateRoleName(RoleNameRequest.newBuilder().setName("admin").build())
                }
            val validatePermissionFailure =
                assertFailsWith<StatusException> {
                    anonymousCaller().validatePermissionName(
                        PermissionNameRequest.newBuilder().setName("secrets.read").build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, rolePermissionsFailure.status.code)
            assertEquals(Status.Code.PERMISSION_DENIED, validateRoleFailure.status.code)
            assertEquals(Status.Code.PERMISSION_DENIED, validatePermissionFailure.status.code)
            assertEquals(0, provider.getRolePermissionsCalls.get())
            assertEquals(0, provider.validateRoleNameCalls.get())
            assertEquals(0, provider.validatePermissionNameCalls.get())
        }

    @Test
    fun `an authenticated caller can validate names`() =
        runBlocking {
            val response = authenticated.validateRoleName(RoleNameRequest.newBuilder().setName("editor").build())
            assertTrue(response.valid)
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
            val revocable = RoleManagementServiceGrpcKt.RoleManagementServiceCoroutineStub(revocableChannel)

            // Confirm the token actually worked before revoking it.
            revocable.getAllRoles(Empty.getDefaultInstance())
            assertEquals(1, provider.getAllRolesCalls.get())

            tokenRegistry.revoke("$DEFAULT_PROCESS.revocable")

            val failure =
                assertFailsWith<StatusException> { revocable.getAllRoles(Empty.getDefaultInstance()) }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertEquals(1, provider.getAllRolesCalls.get(), "the revoked call must not have reached the provider")
        }

    companion object {
        private const val DEFAULT_PROCESS = "ai.rever.boss.plugin.dynamic.test-plugin"
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}

/** Records every call so a refusal test can assert the provider was never actually reached. */
private class FakeRoleManagementProvider : RoleManagementProvider {
    val getAllRolesCalls = AtomicInteger(0)
    val getAllPermissionsCalls = AtomicInteger(0)
    val createRoleCalls = AtomicInteger(0)
    val createPermissionCalls = AtomicInteger(0)
    val deleteRoleCalls = AtomicInteger(0)
    val deletePermissionCalls = AtomicInteger(0)
    val assignPermissionToRoleCalls = AtomicInteger(0)
    val removePermissionFromRoleCalls = AtomicInteger(0)
    val getRolePermissionsCalls = AtomicInteger(0)
    val validateRoleNameCalls = AtomicInteger(0)
    val validatePermissionNameCalls = AtomicInteger(0)

    override suspend fun getAllRoles(): Result<List<RoleInfoData>> {
        getAllRolesCalls.incrementAndGet()
        return Result.success(emptyList())
    }

    override suspend fun getAllPermissions(): Result<List<PermissionInfoData>> {
        getAllPermissionsCalls.incrementAndGet()
        return Result.success(emptyList())
    }

    override suspend fun createRole(
        name: String,
        description: String?,
    ): Result<RoleInfoData> {
        createRoleCalls.incrementAndGet()
        return Result.success(
            RoleInfoData(
                id = "",
                name = name,
                description = description,
                permissions = emptyList(),
                createdAt = 0L,
                isSystem = false,
            ),
        )
    }

    override suspend fun createPermission(
        name: String,
        description: String?,
    ): Result<PermissionInfoData> {
        createPermissionCalls.incrementAndGet()
        return Result.success(
            PermissionInfoData(id = "", name = name, description = description, createdAt = 0L, isSystem = false),
        )
    }

    override suspend fun deleteRole(roleName: String): Result<Unit> {
        deleteRoleCalls.incrementAndGet()
        return Result.success(Unit)
    }

    override suspend fun deletePermission(permissionName: String): Result<Unit> {
        deletePermissionCalls.incrementAndGet()
        return Result.success(Unit)
    }

    override suspend fun assignPermissionToRole(
        roleName: String,
        permissionName: String,
    ): Result<Unit> {
        assignPermissionToRoleCalls.incrementAndGet()
        return Result.success(Unit)
    }

    override suspend fun removePermissionFromRole(
        roleName: String,
        permissionName: String,
    ): Result<Unit> {
        removePermissionFromRoleCalls.incrementAndGet()
        return Result.success(Unit)
    }

    override suspend fun getRolePermissions(roleName: String): Result<RoleWithPermissionsData> {
        getRolePermissionsCalls.incrementAndGet()
        return Result.success(RoleWithPermissionsData(roleName = roleName, permissions = emptyList()))
    }

    override fun validateRoleName(roleName: String): String? {
        validateRoleNameCalls.incrementAndGet()
        return null
    }

    override fun validatePermissionName(permissionName: String): String? {
        validatePermissionNameCalls.incrementAndGet()
        return null
    }
}
