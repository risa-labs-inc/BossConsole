package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.PermissionInfoData
import ai.rever.boss.plugin.api.RoleInfoData
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.api.RoleWithPermissionsData
import io.grpc.ManagedChannel

/**
 * IPC proxy implementation of RoleManagementProvider.
 */
class RoleManagementProviderProxy(
    channel: ManagedChannel,
) : RoleManagementProvider {

    private val stub = RoleManagementServiceGrpcKt.RoleManagementServiceCoroutineStub(channel)

    override suspend fun getAllRoles(): Result<List<RoleInfoData>> = try {
        val resp = stub.getAllRoles(Empty.getDefaultInstance())
        Result.success(resp.rolesList.map { it.toData() })
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun getAllPermissions(): Result<List<PermissionInfoData>> = try {
        val resp = stub.getAllPermissions(Empty.getDefaultInstance())
        Result.success(resp.permissionsList.map { it.toData() })
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun createRole(name: String, description: String?): Result<RoleInfoData> = try {
        val resp = stub.createRole(
            CreateRoleRequest.newBuilder().setName(name).setDescription(description ?: "").build()
        )
        Result.success(resp.role.toData())
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun createPermission(name: String, description: String?): Result<PermissionInfoData> = try {
        val resp = stub.createPermission(
            CreatePermissionRequest.newBuilder().setName(name).setDescription(description ?: "").build()
        )
        Result.success(resp.permission.toData())
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun deleteRole(roleName: String): Result<Unit> = try {
        val resp = stub.deleteRole(RoleNameRequest.newBuilder().setName(roleName).build())
        if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun deletePermission(permissionName: String): Result<Unit> = try {
        val resp = stub.deletePermission(PermissionNameRequest.newBuilder().setName(permissionName).build())
        if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun assignPermissionToRole(roleName: String, permissionName: String): Result<Unit> = try {
        val resp = stub.assignPermissionToRole(
            RolePermissionRequest.newBuilder().setRoleName(roleName).setPermissionName(permissionName).build()
        )
        if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun removePermissionFromRole(roleName: String, permissionName: String): Result<Unit> = try {
        val resp = stub.removePermissionFromRole(
            RolePermissionRequest.newBuilder().setRoleName(roleName).setPermissionName(permissionName).build()
        )
        if (resp.success) Result.success(Unit) else Result.failure(Exception(resp.errorMessage))
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun getRolePermissions(roleName: String): Result<RoleWithPermissionsData> = try {
        val resp = stub.getRolePermissions(RoleNameRequest.newBuilder().setName(roleName).build())
        Result.success(RoleWithPermissionsData(
            roleName = resp.role.name,
            permissions = resp.permissionsList.map { it.name },
        ))
    } catch (e: Exception) { Result.failure(e) }

    override fun validateRoleName(roleName: String): String? {
        // Client-side validation — no IPC needed
        if (roleName.isBlank()) return "Role name cannot be empty"
        if (roleName.length < 2) return "Role name must be at least 2 characters"
        if (!roleName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_-]*$"))) {
            return "Role name must start with a letter and contain only letters, numbers, hyphens, and underscores"
        }
        return null
    }

    override fun validatePermissionName(permissionName: String): String? {
        if (permissionName.isBlank()) return "Permission name cannot be empty"
        if (permissionName.length < 2) return "Permission name must be at least 2 characters"
        if (!permissionName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.:]*$"))) {
            return "Permission name must start with a letter and contain only letters, numbers, underscores, dots, and colons"
        }
        return null
    }

    private fun RoleInfoProto.toData() = RoleInfoData(
        id = id, name = name, description = description.takeIf { it.isNotEmpty() },
        permissions = permissionsList, createdAt = createdAt, isSystem = isSystem,
    )

    private fun PermissionInfoProto.toData() = PermissionInfoData(
        id = id, name = name, description = description.takeIf { it.isNotEmpty() },
        createdAt = createdAt, isSystem = isSystem,
    )
}
