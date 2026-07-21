package ai.rever.boss.services.supabase

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.services.supabase.models.RoleInfo
import ai.rever.boss.services.supabase.models.PermissionInfo
import ai.rever.boss.services.supabase.models.RoleWithPermissions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Service for dynamic role and permission creation
 *
 * This service provides methods to:
 * - Create new roles at runtime (table-based, not ENUM)
 * - Create new permissions at runtime (table-based, not ENUM)
 * - Assign permissions to roles
 * - Remove permissions from roles
 * - Query all roles and permissions
 *
 * Security:
 * - All operations require admin role
 * - RLS policies enforce server-side authorization
 * - Validation enforced at database level
 *
 * Usage:
 * ```kotlin
 * // Create a new role
 * val result = RoleCreationService.createRole("developer", "Developer role")
 *
 * // Create a new permission
 * val result = RoleCreationService.createPermission("code.review", "Review code changes")
 *
 * // Assign permission to role
 * val result = RoleCreationService.assignPermission("developer", "code.review")
 * ```
 */
object RoleCreationService {

    private val logger = BossLogger.forComponent("RoleCreationService")

    /**
     * Create a new role dynamically
     *
     * @param roleName Role name (lowercase, alphanumeric + underscore, 3-50 chars)
     * @param description Optional description
     * @return Result with RoleInfo on success or exception on failure
     */
    suspend fun createRole(roleName: String, description: String? = null): Result<RoleInfo> {
        return try {
            val params = buildJsonObject {
                put("role_name", roleName)
                if (description != null) {
                    put("description", description)
                }
            }

            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "create_new_role",
                parameters = params
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(
                    RoleInfo(
                        name = roleName,
                        description = description,
                        ordinal = 0 // Will be assigned by database
                    )
                )
            } else {
                Result.failure(Exception(result.error ?: "Failed to create role"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "createRole failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Create a new permission dynamically
     *
     * @param permissionName Permission name (domain.action format, e.g., "code.review")
     * @param description Optional description
     * @return Result with PermissionInfo on success or exception on failure
     */
    suspend fun createPermission(
        permissionName: String,
        description: String? = null
    ): Result<PermissionInfo> {
        return try {
            val params = buildJsonObject {
                put("permission_name", permissionName)
                if (description != null) {
                    put("description", description)
                }
            }

            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "create_new_permission",
                parameters = params
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(
                    PermissionInfo(
                        name = permissionName,
                        description = description,
                        ordinal = 0 // Will be assigned by database
                    )
                )
            } else {
                Result.failure(Exception(result.error ?: "Failed to create permission"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "createPermission failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Get all roles from the database
     *
     * @return Result with list of RoleInfo on success or exception on failure
     */
    suspend fun getAllRoles(): Result<List<RoleInfo>> {
        return try {
            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "get_all_roles",
                parameters = buildJsonObject { }
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RolesResponseNew>(jsonElement)

            if (result.success) {
                val roles = result.data?.map { roleData ->
                    RoleInfo(
                        id = roleData.id,
                        name = roleData.name,
                        description = roleData.description,
                        isSystem = roleData.isSystem,
                        createdAt = roleData.createdAt,
                        updatedAt = roleData.updatedAt,
                        ordinal = 0 // Deprecated field
                    )
                } ?: emptyList()
                Result.success(roles)
            } else {
                Result.failure(Exception(result.error ?: "Failed to get roles"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "getAllRoles failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Get all permissions from the database
     *
     * @return Result with list of PermissionInfo on success or exception on failure
     */
    suspend fun getAllPermissions(): Result<List<PermissionInfo>> {
        return try {
            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "get_all_permissions",
                parameters = buildJsonObject { }
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<PermissionsResponseNew>(jsonElement)

            if (result.success) {
                val permissions = result.data?.map { permData ->
                    PermissionInfo(
                        id = permData.id,
                        name = permData.name,
                        description = permData.description,
                        isSystem = permData.isSystem,
                        createdAt = permData.createdAt,
                        updatedAt = permData.updatedAt,
                        ordinal = 0 // Deprecated field
                    )
                } ?: emptyList()
                Result.success(permissions)
            } else {
                Result.failure(Exception(result.error ?: "Failed to get permissions"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "getAllPermissions failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Assign a permission to a role
     *
     * @param roleName Role to assign permission to
     * @param permissionName Permission to assign
     * @return Result with Unit on success or exception on failure
     */
    suspend fun assignPermissionToRole(
        roleName: String,
        permissionName: String
    ): Result<Unit> {
        return try {
            val params = buildJsonObject {
                put("role_name", roleName)
                put("permission_name", permissionName)
            }

            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "assign_permission_to_role",
                parameters = params
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to assign permission"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "assignPermissionToRole failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Remove a permission from a role
     *
     * @param roleName Role to remove permission from
     * @param permissionName Permission to remove
     * @return Result with Unit on success or exception on failure
     */
    suspend fun removePermissionFromRole(
        roleName: String,
        permissionName: String
    ): Result<Unit> {
        return try {
            val params = buildJsonObject {
                put("role_name", roleName)
                put("permission_name", permissionName)
            }

            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "remove_permission_from_role",
                parameters = params
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to remove permission"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "removePermissionFromRole failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Get all permissions assigned to a specific role
     *
     * @param roleName Role to get permissions for
     * @return Result with RoleWithPermissions on success or exception on failure
     */
    suspend fun getRolePermissions(roleName: String): Result<RoleWithPermissions> {
        return try {
            val params = buildJsonObject {
                put("role_name", roleName)
            }

            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "get_role_permissions",
                parameters = params
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RolePermissionsResponse>(jsonElement)

            if (result.success) {
                Result.success(
                    RoleWithPermissions(
                        roleName = roleName,
                        permissions = result.permissions ?: emptyList()
                    )
                )
            } else {
                Result.failure(Exception(result.error ?: "Failed to get role permissions"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "getRolePermissions failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Delete a role from the database
     *
     * @param roleName Role to delete
     * @return Result with Unit on success or exception on failure
     */
    suspend fun deleteRole(roleName: String): Result<Unit> {
        return try {
            val params = buildJsonObject {
                put("role_name", roleName)
            }

            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "delete_role",
                parameters = params
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to delete role"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "deleteRole failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Delete a permission from the database
     *
     * @param permissionName Permission to delete
     * @return Result with Unit on success or exception on failure
     */
    suspend fun deletePermission(permissionName: String): Result<Unit> {
        return try {
            val params = buildJsonObject {
                put("permission_name", permissionName)
            }

            val postgrestResult = SupabaseConfig.client.postgrest.rpc(
                function = "delete_permission",
                parameters = params
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val result = Json.decodeFromJsonElement<RpcResponse>(jsonElement)

            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.error ?: "Failed to delete permission"))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "deletePermission failed", error = e)
            Result.failure(e)
        }
    }

    /**
     * Validate role name format (client-side validation before server call)
     *
     * @param roleName Role name to validate
     * @return Error message if invalid, null if valid
     */
    fun validateRoleName(roleName: String): String? {
        val regex = Regex("^[a-z][a-z0-9_]{2,50}$")
        return when {
            roleName.isBlank() -> "Role name cannot be empty"
            !regex.matches(roleName) -> "Role name must be lowercase, start with letter, 3-50 characters, alphanumeric + underscore only"
            roleName in listOf("user", "admin", "authenticated", "anon", "service_role", "postgres") ->
                "Role name is reserved and cannot be used"
            else -> null
        }
    }

    /**
     * Validate permission name format (client-side validation before server call)
     *
     * @param permissionName Permission name to validate
     * @return Error message if invalid, null if valid
     */
    fun validatePermissionName(permissionName: String): String? {
        val regex = Regex("^[a-z][a-z0-9_]{1,30}\\.[a-z][a-z0-9_]{1,30}$")
        return when {
            permissionName.isBlank() -> "Permission name cannot be empty"
            !regex.matches(permissionName) -> "Permission must be domain.action format (e.g., \"code.review\"), lowercase, alphanumeric + underscore"
            else -> null
        }
    }
}

// ============================================================================
// Response DTOs
// ============================================================================

@Serializable
private data class RpcResponse(
    val success: Boolean,
    val error: String? = null,
    val message: String? = null,
    val permission: String? = null,       // Returned by create_new_permission()
    val description: String? = null,      // Returned by create_new_permission()
    val permission_id: String? = null,    // Returned by create_new_permission() (table-based)
    val role: String? = null,             // Returned by create_new_role()
    val role_id: String? = null           // Returned by create_new_role() (table-based)
)

// NEW: Response DTOs for table-based schema
@Serializable
private data class RolesResponseNew(
    val success: Boolean,
    val error: String? = null,
    val data: List<RoleDataNew>? = null
)

@Serializable
private data class RoleDataNew(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("is_system")
    val isSystem: Boolean,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String
)

@Serializable
private data class PermissionsResponseNew(
    val success: Boolean,
    val error: String? = null,
    val data: List<PermissionDataNew>? = null
)

@Serializable
private data class PermissionDataNew(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("is_system")
    val isSystem: Boolean,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String
)

@Serializable
private data class RolePermissionsResponse(
    val success: Boolean,
    val error: String? = null,
    val role: String? = null,
    val permissions: List<String>? = null
)
