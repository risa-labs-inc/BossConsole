package ai.rever.boss.services.supabase

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.services.supabase.models.*
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.*

/**
 * Service for managing user roles and permissions
 *
 * ⚠️ CRITICAL SECURITY MODEL ⚠️
 * ================================
 * JWT claims parsed on the client are for UI/UX convenience ONLY.
 *
 * - JWT signature is NOT verified on the client
 * - Client-parsed claims are UNTRUSTED for authorization decisions
 * - All security enforcement happens server-side via:
 *   1. Row Level Security (RLS) policies in PostgreSQL
 *   2. Database functions that verify roles before actions
 *   3. Supabase Auth verifies JWT signatures server-side
 *
 * Client-side role checks (isAdmin, hasRole) are for:
 * - Showing/hiding UI elements
 * - Optimistic UI updates
 * - Reducing unnecessary server calls
 *
 * They are NOT for:
 * - Granting actual access to resources
 * - Bypassing server-side checks
 * - Making security decisions
 *
 * Features:
 * - Assign/remove roles (admin only, server-enforced)
 * - Query user roles (RLS-protected)
 * - Check permissions (informational only)
 * - Parse JWT role claims (for UI convenience)
 *
 * Usage:
 * ```kotlin
 * // Get current user's roles from JWT (UI only!)
 * val claims = RoleService.parseRoleClaimsFromSession(session)
 * if (claims.isAdmin) {
 *     // Show admin UI elements
 *     // Actual admin actions still protected by RLS
 * }
 *
 * // Assign admin role (server verifies you're admin via RLS)
 * val result = RoleService.assignRoleByName(userId, "admin")
 * ```
 */
object RoleService {
    private val logger = BossLogger.forComponent("RoleService")

    /**
     * Get the Supabase client
     */
    private val client
        get() = SupabaseConfig.client

    /**
     * Parse role claims from the current session's JWT
     */
    fun parseRoleClaimsFromSession(session: io.github.jan.supabase.auth.user.UserSession?): RoleClaims? {
        if (session == null) return null

        return try {
            // Decode JWT claims from access token
            val accessToken = session.accessToken
            val claims = decodeJWTClaims(accessToken)

            // Debug: log role claims (without sensitive data)
            logger.debug(LogCategory.AUTH, "JWT Claims parsed", mapOf(
                "hasUserRole" to (claims["user_role"] != null),
                "hasUserRoles" to (claims["user_roles"] != null),
                "isAdmin" to (claims["is_admin"] ?: false)
            ))

            val roleClaims = RoleClaims.fromJWTClaims(claims)
            roleClaims?.let { rc ->
                logger.debug(LogCategory.AUTH, "RoleClaims created", mapOf("isAdmin" to rc.isAdmin, "roleCount" to rc.userRoles.size))
            }

            roleClaims
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Failed to parse role claims", error = e)
            null
        }
    }

    /**
     * Decode JWT token to extract claims using proper JSON parsing
     *
     * ⚠️ SECURITY WARNING: This does NOT verify the JWT signature.
     *
     * These claims are for UI/UX convenience only. The JWT signature has already
     * been verified by Supabase when the token was issued, but we don't re-verify
     * it here on the client.
     *
     * All authorization decisions MUST be made server-side via:
     * - RLS policies that check auth.jwt() claims
     * - Database functions that verify roles before mutations
     * - Supabase API endpoints that validate tokens
     *
     * Never trust client-parsed JWT claims for security decisions.
     * Client can be modified, debugged, or have malicious code injected.
     *
     * This parsing is safe for:
     * - Showing/hiding UI elements
     * - Displaying user role badges
     * - Optimistic UI updates
     * - Reducing unnecessary API calls
     *
     * Uses kotlinx.serialization.json for reliable JSON parsing.
     */
    private fun decodeJWTClaims(jwt: String): Map<String, Any?> {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) {
                throw IllegalArgumentException("Invalid JWT format")
            }

            // Decode the payload (second part)
            val payload = parts[1]
            val decodedBytes = java.util.Base64.getUrlDecoder().decode(payload)
            val jsonString = decodedBytes.decodeToString()

            // Note: JWT payload not logged to avoid exposing sensitive claims
            logger.debug(LogCategory.AUTH, "Decoding JWT payload")

            // Parse JSON using kotlinx.serialization (secure and reliable)
            val jsonObject = Json.parseToJsonElement(jsonString).jsonObject

            // Extract RBAC claims
            mapOf(
                "user_role" to jsonObject["user_role"]?.jsonPrimitive?.content,
                "user_roles" to jsonObject["user_roles"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                },
                "is_admin" to jsonObject["is_admin"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
                "user_permissions" to jsonObject["user_permissions"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                }
            )
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Failed to decode JWT", error = e)
            emptyMap()
        }
    }

    /**
     * Get all roles for a specific user
     * Uses helper RPC function for backward compatibility with table-based schema
     */
    suspend fun getUserRoles(userId: String): Result<List<UserRole>> {
        return try {
            // Call helper RPC function that JOINs with roles table
            val postgrestResult = client.postgrest.rpc(
                function = "get_user_roles_with_names",
                parameters = buildJsonObject {
                    put("target_user_id", userId)
                }
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val roles = Json.decodeFromJsonElement<List<UserRole>>(jsonElement)

            Result.success(roles)
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Failed to get user roles", error = e)
            Result.failure(e)
        }
    }

    /**
     * Check if a user has a specific role
     */
    suspend fun userHasRole(userId: String, roleName: String): Result<Boolean> {
        return try {
            // Call helper RPC function that checks role by name
            val postgrestResult = client.postgrest.rpc(
                function = "check_user_has_role",
                parameters = buildJsonObject {
                    put("target_user_id", userId)
                    put("role_name", roleName)
                }
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val hasRole = jsonElement.jsonPrimitive.boolean

            Result.success(hasRole)
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Failed to check user role", error = e)
            Result.failure(e)
        }
    }

    /**
     * Check if a user is an admin
     */
    suspend fun isUserAdmin(userId: String): Result<Boolean> {
        return userHasRole(userId, "admin")
    }

    /**
     * Assign a role to a user by role name (admin only)
     * Supports dynamic roles created at runtime
     */
    suspend fun assignRoleByName(targetUserId: String, roleName: String): Result<Unit> {
        return try {
            client.postgrest.rpc(
                function = "assign_role_to_user",
                parameters = buildJsonObject {
                    put("target_user_id", targetUserId)
                    put("target_role", roleName)
                }
            )

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to assign role", error = e)
            Result.failure(Exception("Failed to assign role: ${e.message}"))
        }
    }

    /**
     * Remove a role from a user by role name (admin only)
     * Supports dynamic roles created at runtime
     */
    suspend fun removeRoleByName(targetUserId: String, roleName: String): Result<Unit> {
        return try {
            client.postgrest.rpc(
                function = "remove_role_from_user",
                parameters = buildJsonObject {
                    put("target_user_id", targetUserId)
                    put("target_role", roleName)
                }
            )

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to remove role", error = e)
            Result.failure(Exception("Failed to remove role: ${e.message}"))
        }
    }

    /**
     * Get all role permissions by role name
     */
    suspend fun getRolePermissions(roleName: String): Result<List<RolePermission>> {
        return try {
            // Call helper RPC function that JOINs with permissions table
            val postgrestResult = client.postgrest.rpc(
                function = "get_role_permissions_with_names",
                parameters = buildJsonObject {
                    put("role_name", roleName)
                }
            )

            val jsonElement = Json.parseToJsonElement(postgrestResult.data)
            val permissions = Json.decodeFromJsonElement<List<RolePermission>>(jsonElement)

            Result.success(permissions)
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Failed to get role permissions", error = e)
            Result.failure(e)
        }
    }

    /**
     * Check if current user can perform an action
     * This checks against the role_permissions table
     */
    suspend fun canPerformAction(userId: String, permissionName: String): Result<Boolean> {
        return try {
            // Get user's roles
            val userRolesResult = getUserRoles(userId)
            if (userRolesResult.isFailure) {
                return Result.failure(userRolesResult.exceptionOrNull()!!)
            }

            val userRoles = userRolesResult.getOrNull() ?: emptyList()
            val roleNames = userRoles.map { it.role }

            // Check if any of the user's roles have the required permission
            for (roleName in roleNames) {
                val permissionsResult = getRolePermissions(roleName)
                if (permissionsResult.isSuccess) {
                    val permissions = permissionsResult.getOrNull() ?: emptyList()
                    if (permissions.any { it.permission == permissionName }) {
                        return Result.success(true)
                    }
                }
            }

            Result.success(false)
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Failed to check permission", error = e)
            Result.failure(e)
        }
    }
}
