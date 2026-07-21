package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.api.PermissionInfoData
import ai.rever.boss.plugin.api.RoleInfoData
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.api.RoleWithPermissionsData

/**
 * Implementation of RoleManagementProvider that wraps RoleCreationService.
 *
 * This adapter allows role-management panels to be extracted to separate
 * plugin modules while keeping the actual Supabase services in composeApp.
 */
class RoleManagementProviderImpl : RoleManagementProvider {

    override suspend fun getAllRoles(): Result<List<RoleInfoData>> {
        return RoleCreationService.getAllRoles().map { roles ->
            roles.map { role ->
                RoleInfoData(
                    id = role.id ?: "",
                    name = role.name,
                    description = role.description,
                    permissions = emptyList(), // Permissions are fetched separately
                    createdAt = 0L, // Role has string date
                    isSystem = role.isSystem
                )
            }
        }
    }

    override suspend fun getAllPermissions(): Result<List<PermissionInfoData>> {
        return RoleCreationService.getAllPermissions().map { permissions ->
            permissions.map { perm ->
                PermissionInfoData(
                    id = perm.id ?: "",
                    name = perm.name,
                    description = perm.description,
                    createdAt = 0L, // Permission has string date
                    isSystem = perm.isSystem
                )
            }
        }
    }

    override suspend fun createRole(name: String, description: String?): Result<RoleInfoData> {
        return RoleCreationService.createRole(name, description).map { role ->
            RoleInfoData(
                id = "",
                name = role.name,
                description = role.description,
                permissions = emptyList(),
                createdAt = 0L,
                isSystem = role.isSystem
            )
        }
    }

    override suspend fun createPermission(name: String, description: String?): Result<PermissionInfoData> {
        return RoleCreationService.createPermission(name, description).map { perm ->
            PermissionInfoData(
                id = "",
                name = perm.name,
                description = perm.description,
                createdAt = 0L,
                isSystem = perm.isSystem
            )
        }
    }

    override suspend fun deleteRole(roleName: String): Result<Unit> {
        return RoleCreationService.deleteRole(roleName)
    }

    override suspend fun deletePermission(permissionName: String): Result<Unit> {
        return RoleCreationService.deletePermission(permissionName)
    }

    override suspend fun assignPermissionToRole(roleName: String, permissionName: String): Result<Unit> {
        return RoleCreationService.assignPermissionToRole(roleName, permissionName)
    }

    override suspend fun removePermissionFromRole(roleName: String, permissionName: String): Result<Unit> {
        return RoleCreationService.removePermissionFromRole(roleName, permissionName)
    }

    override suspend fun getRolePermissions(roleName: String): Result<RoleWithPermissionsData> {
        return RoleCreationService.getRolePermissions(roleName).map { roleWithPerms ->
            RoleWithPermissionsData(
                roleName = roleWithPerms.roleName,
                permissions = roleWithPerms.permissions
            )
        }
    }

    override fun validateRoleName(roleName: String): String? {
        return RoleCreationService.validateRoleName(roleName)
    }

    override fun validatePermissionName(permissionName: String): String? {
        return RoleCreationService.validatePermissionName(permissionName)
    }
}
