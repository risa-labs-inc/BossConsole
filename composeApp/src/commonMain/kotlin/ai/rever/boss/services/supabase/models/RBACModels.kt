package ai.rever.boss.services.supabase.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User role assignment from the user_roles table
 */
@Serializable
data class UserRole(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val role: String,
    @SerialName("assigned_by")
    val assignedBy: String? = null,
    @SerialName("assigned_at")
    val assignedAt: String,
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * Role permission mapping from the role_permissions table
 */
@Serializable
data class RolePermission(
    val id: String,
    val role: String,
    val permission: String,
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * JWT claims containing user role information
 * These claims are injected by the custom_access_token_hook
 */
data class RoleClaims(
    val userRole: String,
    val userRoles: List<String>,
    val isAdmin: Boolean,
    /**
     * Effective permissions (own + inherited via the role hierarchy), injected
     * into the JWT by the custom_access_token_hook as the `user_permissions` claim.
     */
    val permissions: List<String> = emptyList(),
) {
    companion object {
        /**
         * Parse role claims from JWT token claims map
         */
        fun fromJWTClaims(claims: Map<String, Any?>): RoleClaims? {
            val userRoleStr = claims["user_role"] as? String
            val userRolesArray = claims["user_roles"] as? List<*>
            val isAdmin = claims["is_admin"] as? Boolean ?: false
            val userPermissionsArray = claims["user_permissions"] as? List<*>

            val primaryRole = userRoleStr ?: "user"

            val roles = userRolesArray?.mapNotNull { it as? String } ?: listOf(primaryRole)
            val permissions = userPermissionsArray?.mapNotNull { it as? String } ?: emptyList()

            return RoleClaims(
                userRole = primaryRole,
                userRoles = roles,
                isAdmin = isAdmin,
                permissions = permissions,
            )
        }
    }

    /**
     * Check if user has a specific role
     */
    fun hasRole(role: String): Boolean = userRoles.contains(role)

    /**
     * Check if user has a specific effective permission
     */
    fun hasPermission(permission: String): Boolean = permissions.contains(permission)

    /**
     * Check if user has any of the specified roles
     */
    fun hasAnyRole(vararg roles: String): Boolean = roles.any { hasRole(it) }

    /**
     * Check if user has all of the specified roles
     */
    fun hasAllRoles(vararg roles: String): Boolean = roles.all { hasRole(it) }
}

/**
 * User information with roles (supports dynamic roles)
 */
data class UserWithRoles(
    val userId: String,
    val email: String,
    val roles: List<String>,
    val isAdmin: Boolean,
) {
    /**
     * Get primary role name
     */
    val primaryRole: String
        get() = roles.firstOrNull() ?: "user"
}

/**
 * Role information from database (includes all roles, even dynamically created ones)
 * Now uses table-based schema with full CRUD support
 */
data class RoleInfo(
    val id: String? = null, // UUID from roles table (null for backward compatibility)
    val name: String,
    val description: String? = null,
    val isSystem: Boolean = false, // System roles (user, admin) cannot be deleted
    val createdAt: String? = null, // Timestamp when role was created
    val updatedAt: String? = null, // Timestamp when role was last updated
    val ordinal: Int = 0, // For backward compatibility (deprecated)
) {
    /**
     * Check if this role can be deleted
     */
    fun canDelete(): Boolean = !isSystem

    /**
     * Get display name (for UI)
     */
    fun getDisplayName(): String = name.replaceFirstChar { it.uppercase() }
}

/**
 * Permission information from database (includes all permissions, even dynamically created ones)
 * Now uses table-based schema with full CRUD support
 */
data class PermissionInfo(
    val id: String? = null, // UUID from permissions table (null for backward compatibility)
    val name: String,
    val description: String? = null,
    val isSystem: Boolean = false, // System permissions cannot be deleted
    val createdAt: String? = null, // Timestamp when permission was created
    val updatedAt: String? = null, // Timestamp when permission was last updated
    val ordinal: Int = 0, // For backward compatibility (deprecated)
) {
    /**
     * Check if this permission can be deleted
     */
    fun canDelete(): Boolean = !isSystem

    /**
     * Get domain and action parts (e.g., "users.read" -> "users" and "read")
     */
    fun getDomain(): String = name.substringBefore(".")

    fun getAction(): String = name.substringAfter(".")
}

/**
 * Role with its assigned permissions
 */
data class RoleWithPermissions(
    val roleName: String,
    val permissions: List<String> = emptyList(),
) {
    fun hasPermission(permission: String): Boolean = permissions.contains(permission)
}
