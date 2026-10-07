package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.pathutils.BossDirectories
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
    suspend fun sendMagicLink(email: String): Result<Unit> {
        val sent = EmailAuthService.sendMagicLink(email)
        // A magic link completes through boss://auth/verify; record that this process waits for
        // it, and for which account, so the link is routed to and accepted by this process only.
        if (profilesEnabled()) sent.onSuccess { AuthFlowMarker.mark(AuthFlowMarker.Kind.MAGIC_LINK, email) }
        return sent
    }

    /**
     * A link this process has no flow left for while its own link may still arrive: most often the
     * same link delivered to a second window, which another caller is already exchanging.
     */
    class MagicLinkAlreadyTakenException(
        message: String,
    ) : Exception(message)

    private const val STALE_LINK = "This sign-in link was already used or is not the newest one. Request a new link."

    /** The hidden profiles feature; while off, magic links take the pre-profiles path unchanged. */
    internal var profilesEnabled: () -> Boolean = { BossDirectories.profilesEnabled }

    /** The pre-profiles exchange: spends the link and imports the session with no account check. */
    internal var legacyVerify: suspend (token: String, type: String) -> Result<Unit> = { token, type ->
        EmailAuthService.verifyEmail(token, type)
    }

    /** Where a Google or Apple sign-in stands. */
    val oauthState: StateFlow<OAuthSignInState> get() = OAuthSignInService.state

    /** The provider whose Google or Apple sign-in is being prepared, or null. */
    val oauthStarting: StateFlow<OAuthProviderKind?> get() = OAuthSignInService.starting

    /** End a waiting Google or Apple sign-in that has run past its time limit. */
    suspend fun expireStaleOAuth(): Boolean = OAuthSignInService.expireIfStale()

    /** Open [provider]'s sign-in page in the system browser; completes via `boss://auth/callback`. */
    suspend fun signInWithOAuth(provider: OAuthProviderKind): OAuthStart = OAuthSignInService.start(provider)

    /** Finish the waiting Google or Apple sign-in with its deep-link callback. */
    suspend fun completeOAuth(callback: ai.rever.boss.components.auth.AuthDeepLink.OAuthCallback): OAuthCompletion =
        OAuthSignInService.complete(callback)

    /** Reopen the waiting sign-in's page; false when none is waiting or no browser opened. */
    suspend fun reopenOAuthBrowser(): Boolean = OAuthSignInService.reopenBrowser()

    /** Abandon the waiting Google or Apple sign-in. */
    suspend fun cancelOAuth() = OAuthSignInService.cancel()

    /** Clear a Google or Apple sign-in error from the login screen. */
    fun dismissOAuthError() = OAuthSignInService.dismissError()

    /**
     * Sign out the current user
     */
    suspend fun signOut(): Result<Unit> = CoreAuthService.signOut()

    /**
     * Completes a magic link - called when the `boss://auth/verify` deep link arrives.
     *
     * When this process asked for a link ([AuthFlowMarker.takeForExchange] finds the flow, claimed
     * for exactly this token or its own pending one, and consumes it), the link is spent through
     * [MagicLinkExchange]: the minted session reaches the live client only if it is for the account
     * the link was sent to. A separate-account profile that asked for no link refuses it without
     * spending it, and so does a process whose own link may still be live but finds no flow for this
     * one (a second delivery, or a concurrent caller that already took it): the quarantine holds for
     * the process, not for one caller. Only a main profile asking for nothing - and every process
     * while profiles are off - keeps the old path, unchanged.
     */
    suspend fun verifyEmail(
        token: String,
        type: String = "magiclink",
    ): Result<Unit> {
        if (!profilesEnabled()) return legacyVerify(token, type)
        val flow = AuthFlowMarker.takeForExchange(token)
        return when {
            flow != null -> {
                MagicLinkExchange
                    .exchange(token, type, flow)
                    .onSuccess { AuthStateManager.setAuthenticatedViaMagicLink(true) }
                    // Not this flow's link: the one that is may still arrive.
                    .onFailure { AuthFlowMarker.restore(flow) }
            }

            BossDirectories.isProfile -> {
                Result.failure(Exception("This sign-in link was not requested in this BOSS window."))
            }

            AuthFlowMarker.hasIssuedLive() -> {
                Result.failure(MagicLinkAlreadyTakenException(STALE_LINK))
            }

            else -> {
                legacyVerify(token, type)
            }
        }
    }

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
