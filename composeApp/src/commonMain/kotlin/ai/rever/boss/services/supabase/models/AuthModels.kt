package ai.rever.boss.services.supabase.models

/**
 * User existence information for progressive login flow
 */
data class UserExistence(
    val exists: Boolean,
    val hasPasskeys: Boolean,
    val email: String,
    val availableCredentials: List<AvailableWebAuthnCredential> = emptyList()
)

data class AvailableWebAuthnCredential(
    val credentialId: String,
    val displayName: String,
    val transports: List<String>,
    val credentialType: WebAuthnCredentialType
)

enum class WebAuthnCredentialType(val displayName: String, val icon: String) {
    PLATFORM("Touch ID / Face ID", "fingerprint"),
    CROSS_DEVICE("Authenticator App", "smartphone"),
    USB_KEY("USB Security Key", "usb"),
    NFC_KEY("NFC Security Key", "nfc"),
    UNKNOWN("Security Credential", "security")
}

/**
 * User information with role data
 */
data class UserInfo(
    val id: String,
    val email: String,
    val createdAt: String,
    val roleClaims: RoleClaims? = null
) {
    /**
     * Get user's primary role (defaults to "user" if no claims)
     */
    val primaryRole: String
        get() = roleClaims?.userRole ?: "user"

    /**
     * Get all user roles
     */
    val roles: List<String>
        get() = roleClaims?.userRoles ?: listOf("user")

    /**
     * Check if user is an admin
     */
    val isAdmin: Boolean
        get() = roleClaims?.isAdmin ?: false

    /**
     * Effective permissions (own + inherited via the role hierarchy)
     */
    val permissions: List<String>
        get() = roleClaims?.permissions ?: emptyList()

    /**
     * Check if user has a specific role
     */
    fun hasRole(role: String): Boolean =
        roleClaims?.hasRole(role) ?: (role == "user")

    /**
     * Check if user has a specific effective permission.
     * Admins implicitly hold every permission.
     */
    fun hasPermission(permission: String): Boolean =
        isAdmin || (roleClaims?.hasPermission(permission) ?: false)
}
