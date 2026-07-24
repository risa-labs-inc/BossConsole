package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.PermissionInfoData
import ai.rever.boss.plugin.api.RoleInfoData
import ai.rever.boss.plugin.api.RoleManagementProvider

class RoleManagementServiceBridge(
    private val provider: RoleManagementProvider,
) : RoleManagementServiceGrpcKt.RoleManagementServiceCoroutineImplBase() {
    override suspend fun getAllRoles(request: Empty): RoleListResponse {
        val result = provider.getAllRoles()
        return result.fold(
            onSuccess = { roles ->
                RoleListResponse
                    .newBuilder()
                    .addAllRoles(roles.map { it.toProto() })
                    .build()
            },
            onFailure = { RoleListResponse.getDefaultInstance() },
        )
    }

    override suspend fun getAllPermissions(request: Empty): PermissionListResponse {
        val result = provider.getAllPermissions()
        return result.fold(
            onSuccess = { perms ->
                PermissionListResponse
                    .newBuilder()
                    .addAllPermissions(perms.map { it.toProto() })
                    .build()
            },
            onFailure = { PermissionListResponse.getDefaultInstance() },
        )
    }

    override suspend fun createRole(request: CreateRoleRequest): RoleInfoResponse {
        val result = provider.createRole(request.name, request.description.ifEmpty { null })
        return result.fold(
            onSuccess = { role ->
                RoleInfoResponse.newBuilder().setRole(role.toProto()).build()
            },
            onFailure = { RoleInfoResponse.getDefaultInstance() },
        )
    }

    override suspend fun createPermission(request: CreatePermissionRequest): PermissionInfoResponse {
        val result = provider.createPermission(request.name, request.description.ifEmpty { null })
        return result.fold(
            onSuccess = { perm ->
                PermissionInfoResponse.newBuilder().setPermission(perm.toProto()).build()
            },
            onFailure = { PermissionInfoResponse.getDefaultInstance() },
        )
    }

    override suspend fun deleteRole(request: RoleNameRequest): RoleOperationResult = provider.deleteRole(request.name).toOperationResult()

    override suspend fun deletePermission(request: PermissionNameRequest): RoleOperationResult =
        provider.deletePermission(request.name).toOperationResult()

    override suspend fun assignPermissionToRole(request: RolePermissionRequest): RoleOperationResult =
        provider.assignPermissionToRole(request.roleName, request.permissionName).toOperationResult()

    override suspend fun removePermissionFromRole(request: RolePermissionRequest): RoleOperationResult =
        provider.removePermissionFromRole(request.roleName, request.permissionName).toOperationResult()

    override suspend fun getRolePermissions(request: RoleNameRequest): RoleWithPermissionsResponse {
        val result = provider.getRolePermissions(request.name)
        return result.fold(
            onSuccess = { data ->
                RoleWithPermissionsResponse
                    .newBuilder()
                    .setRole(
                        RoleInfoProto
                            .newBuilder()
                            .setName(data.roleName)
                            .addAllPermissions(data.permissions)
                            .build(),
                    ).build()
            },
            onFailure = { RoleWithPermissionsResponse.getDefaultInstance() },
        )
    }

    override suspend fun validateRoleName(request: RoleNameRequest): ValidationResponse {
        val error = provider.validateRoleName(request.name)
        return ValidationResponse
            .newBuilder()
            .setValid(error == null)
            .setErrorMessage(error ?: "")
            .build()
    }

    override suspend fun validatePermissionName(request: PermissionNameRequest): ValidationResponse {
        val error = provider.validatePermissionName(request.name)
        return ValidationResponse
            .newBuilder()
            .setValid(error == null)
            .setErrorMessage(error ?: "")
            .build()
    }

    private fun RoleInfoData.toProto(): RoleInfoProto =
        RoleInfoProto
            .newBuilder()
            .setId(id)
            .setName(name)
            .setDescription(description ?: "")
            .addAllPermissions(permissions)
            .setCreatedAt(createdAt)
            .setIsSystem(isSystem)
            .build()

    private fun PermissionInfoData.toProto(): PermissionInfoProto =
        PermissionInfoProto
            .newBuilder()
            .setId(id)
            .setName(name)
            .setDescription(description ?: "")
            .setCreatedAt(createdAt)
            .setIsSystem(isSystem)
            .build()

    private fun Result<Unit>.toOperationResult(): RoleOperationResult =
        fold(
            onSuccess = {
                RoleOperationResult.newBuilder().setSuccess(true).build()
            },
            onFailure = { error ->
                RoleOperationResult
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            },
        )
}
