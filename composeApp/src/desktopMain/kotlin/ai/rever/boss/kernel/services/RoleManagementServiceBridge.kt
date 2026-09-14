package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.PermissionInfoData
import ai.rever.boss.plugin.api.RoleInfoData
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException

/**
 * Kernel-side bridge for `RoleManagementService` — the out-of-process RBAC surface: list, create and
 * delete roles/permissions, and grant/revoke a permission on a role.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and the
 * same helper shape as [SecretServiceBridge] — see that class's KDoc for the full rationale. This is
 * the other bridge whose worst case is not "stale UI state": an unattributed, unauthenticated caller
 * here could enumerate every role/permission and mint or revoke access grants. A caller with no
 * credential is refused with `PERMISSION_DENIED` before [provider] is ever reached, and every mutation
 * logs the verified caller identity.
 */
class RoleManagementServiceBridge(
    private val provider: RoleManagementProvider,
) : RoleManagementServiceGrpcKt.RoleManagementServiceCoroutineImplBase() {
    override suspend fun getAllRoles(request: Empty): RoleListResponse {
        authenticatedCallerOrRefuse("getAllRoles")
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
        authenticatedCallerOrRefuse("getAllPermissions")
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
        val caller = authenticatedCallerOrRefuse("createRole")
        logMutation("createRole", caller, mapOf("name" to request.name))
        val result = provider.createRole(request.name, request.description.ifEmpty { null })
        return result.fold(
            onSuccess = { role ->
                RoleInfoResponse.newBuilder().setRole(role.toProto()).build()
            },
            onFailure = { RoleInfoResponse.getDefaultInstance() },
        )
    }

    override suspend fun createPermission(request: CreatePermissionRequest): PermissionInfoResponse {
        val caller = authenticatedCallerOrRefuse("createPermission")
        logMutation("createPermission", caller, mapOf("name" to request.name))
        val result = provider.createPermission(request.name, request.description.ifEmpty { null })
        return result.fold(
            onSuccess = { perm ->
                PermissionInfoResponse.newBuilder().setPermission(perm.toProto()).build()
            },
            onFailure = { PermissionInfoResponse.getDefaultInstance() },
        )
    }

    override suspend fun deleteRole(request: RoleNameRequest): RoleOperationResult {
        val caller = authenticatedCallerOrRefuse("deleteRole")
        logMutation("deleteRole", caller, mapOf("name" to request.name))
        return provider.deleteRole(request.name).toOperationResult()
    }

    override suspend fun deletePermission(request: PermissionNameRequest): RoleOperationResult {
        val caller = authenticatedCallerOrRefuse("deletePermission")
        logMutation("deletePermission", caller, mapOf("name" to request.name))
        return provider.deletePermission(request.name).toOperationResult()
    }

    override suspend fun assignPermissionToRole(request: RolePermissionRequest): RoleOperationResult {
        val caller = authenticatedCallerOrRefuse("assignPermissionToRole")
        logMutation(
            "assignPermissionToRole",
            caller,
            mapOf("roleName" to request.roleName, "permissionName" to request.permissionName),
        )
        return provider.assignPermissionToRole(request.roleName, request.permissionName).toOperationResult()
    }

    override suspend fun removePermissionFromRole(request: RolePermissionRequest): RoleOperationResult {
        val caller = authenticatedCallerOrRefuse("removePermissionFromRole")
        logMutation(
            "removePermissionFromRole",
            caller,
            mapOf("roleName" to request.roleName, "permissionName" to request.permissionName),
        )
        return provider.removePermissionFromRole(request.roleName, request.permissionName).toOperationResult()
    }

    override suspend fun getRolePermissions(request: RoleNameRequest): RoleWithPermissionsResponse {
        authenticatedCallerOrRefuse("getRolePermissions")
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
        authenticatedCallerOrRefuse("validateRoleName")
        val error = provider.validateRoleName(request.name)
        return ValidationResponse
            .newBuilder()
            .setValid(error == null)
            .setErrorMessage(error ?: "")
            .build()
    }

    override suspend fun validatePermissionName(request: PermissionNameRequest): ValidationResponse {
        authenticatedCallerOrRefuse("validatePermissionName")
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

    /**
     * The verified identity behind this call, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors [SecretServiceBridge]'s own helper (BossConsole#53) — fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     *
     * Unary RPCs only: [ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID] is a per-call snapshot,
     * correct for unary methods. A streaming RPC on this bridge must read
     * [ProcessIdentityInterceptor.CURRENT_IDENTITY] instead (see [PluginUIServiceBridge.streamUI]),
     * so a mid-stream revocation is honoured.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: run {
            logger.warn(
                LogCategory.AUTH,
                "Refused $rpc: no verified process identity on this call",
                mapOf("rpc" to rpc),
            )
            throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
        }

    /** Attributed audit line for an RBAC mutation — the caller identity a refusal would otherwise hide. */
    private fun logMutation(
        rpc: String,
        caller: String,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        logger.info(LogCategory.AUTH, "Role/permission mutation", mapOf("rpc" to rpc, "caller" to caller) + extra)
    }

    private companion object {
        val logger = BossLogger.forComponent("RoleManagementServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
