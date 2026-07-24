package ai.rever.boss.services.supabase

import ai.rever.boss.services.auth.*
import ai.rever.boss.services.passkey.PasskeyInfo
import ai.rever.boss.services.passkey.PasskeyService
import ai.rever.boss.services.supabase.models.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.ExperimentalTime

// Exception for cross-device authentication flow
class CrossDeviceAuthenticationRequired(
    val qrCodeUrl: String,
    val challenge: String,
    val sessionId: String,
    val browserAlreadyOpened: Boolean = false,
    override val message: String = "Cross-device authentication required",
) : Exception(message)

/**
 * Authentication service for managing user authentication with Supabase
 * Coordinates between extracted authentication service components
 */
@OptIn(ExperimentalTime::class)
object AuthService {
    // Expose StateFlows from AuthStateManager
    val authState: StateFlow<AuthState> = AuthStateManager.authState
    val currentUser: StateFlow<UserInfo?> = AuthStateManager.currentUser

    /**
     * Initialize the auth service and check for existing session
     */
    fun initialize() {
        CoreAuthService.initialize()
    }

    /**
     * Send magic link for passwordless authentication
     */
    suspend fun sendMagicLink(email: String): Result<Unit> = EmailAuthService.sendMagicLink(email)

    /**
     * Sign out the current user
     */
    suspend fun signOut(): Result<Unit> = CoreAuthService.signOut()

    /**
     * Mark email as verified - called when deep link indicates successful verification
     */
    suspend fun verifyEmail(
        token: String,
        type: String = "magiclink",
    ): Result<Unit> = EmailAuthService.verifyEmail(token, type)

    /**
     * Check if a user exists with the given email address
     */
    suspend fun checkUserExists(email: String): Result<UserExistence> = UserExistenceService.checkUserExists(email)

    /**
     * Set the platform-specific passkey service implementation
     */
    fun setPasskeyService(service: PasskeyService) {
        PasskeyAuthService.setPasskeyService(service)
        UserExistenceService.setPasskeyService(service)
    }

    /**
     * Get passkey state flow from the passkey service
     */
    fun getPasskeyState() = PasskeyAuthService.getPasskeyState()

    /**
     * Check if passkey authentication is available
     */
    suspend fun isPasskeySupported(): Boolean = PasskeyAuthService.isPasskeySupported()

    /**
     * Register a new passkey for the current user
     * Integrates with Supabase backend for credential storage and verification
     */
    suspend fun registerPasskey(): Result<String> = PasskeyAuthService.registerPasskey()

    /**
     * Authenticate using passkey
     * Supports both user-identified and usernameless authentication
     */
    suspend fun authenticateWithPasskey(
        email: String,
        credentialId: String? = null,
    ): Result<Unit> = PasskeyAuthService.authenticateWithPasskey(email, credentialId)

    /**
     * Check authentication status for cross-device flow
     */
    suspend fun checkAuthenticationStatus(
        challenge: String,
        sessionId: String? = null,
    ): Result<Boolean> = CrossDeviceAuthService.checkAuthenticationStatus(challenge, sessionId)

    /**
     * Get user's registered passkeys (from both local storage and Supabase backend)
     */
    suspend fun getUserPasskeys(): Result<List<PasskeyInfo>> = PasskeyAuthService.getUserPasskeys()

    /**
     * Delete a passkey
     */
    suspend fun deletePasskey(credentialId: String): Result<Unit> = PasskeyAuthService.deletePasskey(credentialId)

    // ============================================================================
    // RBAC - Role-Based Access Control
    // ============================================================================

    /**
     * Get current user's role claims from JWT
     * Returns null if no user is authenticated
     */
    fun getCurrentUserRoleClaims(): RoleClaims? = currentUser.value?.roleClaims

    /**
     * Check if current user is an admin
     */
    fun isCurrentUserAdmin(): Boolean = currentUser.value?.isAdmin ?: false

    /**
     * Check if current user has a specific role
     */
    fun currentUserHasRole(roleName: String): Boolean = currentUser.value?.hasRole(roleName) ?: false

    /**
     * Assign a role to a user (admin only)
     */
    suspend fun assignRoleByName(
        targetUserId: String,
        roleName: String,
    ): Result<Unit> = RoleService.assignRoleByName(targetUserId, roleName)

    /**
     * Remove a role from a user (admin only)
     */
    suspend fun removeRoleByName(
        targetUserId: String,
        roleName: String,
    ): Result<Unit> = RoleService.removeRoleByName(targetUserId, roleName)

    /**
     * Get all roles for a specific user
     */
    suspend fun getUserRoles(userId: String): Result<List<UserRole>> = RoleService.getUserRoles(userId)

    /**
     * Check if a user has a specific permission
     */
    suspend fun userHasPermission(
        userId: String,
        permissionName: String,
    ): Result<Boolean> = RoleService.canPerformAction(userId, permissionName)

    /**
     * Authentication state
     */
    sealed class AuthState {
        object Loading : AuthState()

        object NotAuthenticated : AuthState()

        object Authenticated : AuthState()

        data class Error(
            val message: String,
        ) : AuthState()

        /** Offline - no internet connection during startup */
        object Offline : AuthState()
    }
}
