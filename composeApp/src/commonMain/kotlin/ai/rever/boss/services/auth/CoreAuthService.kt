package ai.rever.boss.services.auth

import ai.rever.boss.services.auth.AuthStateManager
import ai.rever.boss.services.network.NetworkMonitorService
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.services.supabase.RoleService
import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.utils.VersionVerifier
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Core authentication orchestration service
 * Coordinates between different authentication services
 */
@OptIn(ExperimentalTime::class)
internal object CoreAuthService {
    // Coroutine scope for auth service
    private val authScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val logger = BossLogger.forComponent("CoreAuthService")

    // Track when session status first resolves (authenticated or not)
    // Used by UI to know when auth system is ready
    private val _isSessionResolved = MutableStateFlow(false)
    val isSessionResolved: StateFlow<Boolean> = _isSessionResolved.asStateFlow()

    // Prevent duplicate initialization attempts (race condition fix)
    private var isInitializing = false

    // Single-flight guard for the session recovery loop (see startSessionRecovery).
    // Written only from the Main-confined status collector; @Volatile so the
    // read in signOut() (callable from any dispatcher) sees the latest job.
    @Volatile
    private var recoveryJob: Job? = null

    /**
     * Initialize the auth service and check for existing session
     */
    fun initialize() {
        try {
            // Verify version consistency at startup (Issue #111 fix)
            VersionVerifier.verifyVersionConsistency()

            // Check network connectivity before initializing Supabase
            authScope.launch {
                val isConnected = NetworkMonitorService.checkConnectivity()
                if (!isConnected) {
                    logger.warn(LogCategory.NETWORK, "No network connectivity, entering offline state")
                    handleOfflineStart()
                    return@launch
                }

                // Network is available, proceed with normal initialization
                initializeWithNetwork()
            }
        } catch (e: Exception) {
            AuthStateManager.setAuthState(AuthService.AuthState.Error(e.message ?: "Failed to initialize authentication"))
        }
    }

    /**
     * Initialize authentication with network available
     * Uses isInitializing flag to prevent duplicate initialization attempts
     */
    private fun initializeWithNetwork() {
        // Prevent duplicate initialization (race condition fix)
        if (isInitializing) {
            logger.debug(LogCategory.AUTH, "Already initializing, skipping")
            return
        }
        isInitializing = true

        // Stop any running auto-retry since we're now initializing
        NetworkMonitorService.stopAutoRetry()

        try {
            // Initialize Supabase with build-time configuration
            if (!SupabaseConfig.isInitialized.value) {
                SupabaseConfig.initializeFromEnvironment()
            }

            // Wait for session to load from storage and then set proper state
            authScope.launch {
                SupabaseConfig.client.auth.sessionStatus.collect { sessionStatus ->
                    logger.debug(LogCategory.AUTH, "SessionStatus changed", mapOf("status" to sessionStatus::class.simpleName))

                    when (sessionStatus) {
                        is SessionStatus.Authenticated -> {
                            // Mark session as resolved (user is authenticated)
                            _isSessionResolved.value = true

                            val user = sessionStatus.session.user
                            val userId = user?.id ?: ""
                            logger.debug(LogCategory.AUTH, "Session authenticated", mapOf("hasUserId" to userId.isNotEmpty()))

                            // Only update user info if not already set (e.g., by PasskeyAuthService)
                            // or if session.user has valid data
                            val currentUser = AuthStateManager.currentUser.value
                            if (currentUser == null || (userId.isNotEmpty() && currentUser.id != userId)) {
                                if (userId.isNotEmpty()) {
                                    // Update user info from session.user (standard Supabase auth)
                                    // Parse role claims from JWT
                                    val roleClaims = RoleService.parseRoleClaimsFromSession(sessionStatus.session)

                                    AuthStateManager.setCurrentUser(
                                        UserInfo(
                                            id = userId,
                                            email = user?.email ?: "",
                                            createdAt = user?.createdAt?.toString() ?: "",
                                            roleClaims = roleClaims,
                                        ),
                                    )
                                    logger.debug(
                                        LogCategory.AUTH,
                                        "Updated user info from session.user",
                                        mapOf(
                                            "hasRoleClaims" to (roleClaims != null),
                                            "isAdmin" to (roleClaims?.isAdmin ?: false),
                                        ),
                                    )
                                } else {
                                    // Session user is null (custom JWT) - load from SessionManager
                                    SessionManager.loadSession().fold(
                                        onSuccess = { storedUser ->
                                            if (storedUser != null) {
                                                AuthStateManager.setCurrentUser(storedUser)
                                                logger.debug(
                                                    LogCategory.AUTH,
                                                    "Loaded user info via SessionManager",
                                                    mapOf(
                                                        "email" to LogSanitizer.maskEmail(storedUser.email),
                                                    ),
                                                )
                                            } else {
                                                logger.debug(
                                                    LogCategory.AUTH,
                                                    "No user data available (session.user is null and no stored data)",
                                                )
                                            }
                                        },
                                        onFailure = { error ->
                                            logger.warn(LogCategory.AUTH, "Failed to load session", error = error)
                                        },
                                    )
                                }
                            } else if (userId.isEmpty()) {
                                // Session user is null but we have user data (custom auth like passkey)
                                logger.debug(LogCategory.AUTH, "Keeping existing user info from custom auth")
                            }

                            // All authentication methods (magic link, passkey, biometric) provide inherent 2FA
                            // No additional verification needed - set to Authenticated
                            AuthStateManager.setAuthState(AuthService.AuthState.Authenticated)
                            logger.info(LogCategory.AUTH, "Auth state set to Authenticated")
                        }

                        is SessionStatus.NotAuthenticated -> {
                            // Mark session as resolved (user is not authenticated)
                            _isSessionResolved.value = true

                            AuthStateManager.setCurrentUser(null)
                            AuthStateManager.setAuthState(AuthService.AuthState.NotAuthenticated)
                            // Reset magic link flag when session ends
                            AuthStateManager.setAuthenticatedViaMagicLink(false)
                            logger.info(LogCategory.AUTH, "Auth state set to NotAuthenticated")
                        }

                        is SessionStatus.RefreshFailure -> {
                            // supabase-kt deprecated RefreshFailure.cause in favor of a
                            // separate AuthEvent.RefreshFailure flow; migrating means
                            // restructuring the recovery path (#855), so read it here
                            // until that refactor - suppressed, not ignored.
                            @Suppress("DEPRECATION")
                            val detail =
                                when (val cause = sessionStatus.cause) {
                                    is RefreshFailureCause.NetworkError -> {
                                        "network: ${cause.exception.message ?: cause.exception::class.simpleName}"
                                    }

                                    is RefreshFailureCause.InternalServerError -> {
                                        "server: HTTP ${cause.exception.statusCode} ${cause.exception.error}"
                                    }
                                }
                            logger.warn(LogCategory.AUTH, "Session refresh failed", mapOf("cause" to detail))

                            // Startup path: a stored session that expired while the
                            // app was closed and can't be refreshed yet must not
                            // leave the UI on the Loading spinner until recovery
                            // succeeds — resolve as Offline. The recovery loop
                            // flips the state once refresh succeeds (Authenticated)
                            // or the token is rejected (login screen). Mid-session,
                            // keep the current state and recover silently.
                            if (!_isSessionResolved.value) {
                                _isSessionResolved.value = true
                                AuthStateManager.setAuthState(AuthService.AuthState.Offline)
                                logger.info(LogCategory.AUTH, "Auth state set to Offline (startup session refresh failed)")
                            }
                            startSessionRecovery()
                        }

                        else -> {
                            // Keep loading state for any other status while we wait
                            if (AuthStateManager.authState.value is AuthService.AuthState.Loading) {
                                logger.debug(
                                    LogCategory.AUTH,
                                    "Still waiting for session status",
                                    mapOf("status" to sessionStatus.toString()),
                                )
                            } else {
                                logger.debug(LogCategory.AUTH, "Other session status", mapOf("status" to sessionStatus.toString()))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AuthStateManager.setAuthState(AuthService.AuthState.Error(e.message ?: "Failed to initialize authentication"))
        }
    }

    /**
     * Single-flight recovery loop for a session stuck in [SessionStatus.RefreshFailure].
     *
     * supabase-kt only clears the session when its *scheduled* refresh is
     * rejected with a hard 4xx; the force-refresh path used by authenticated
     * requests (Realtime reconnects, Postgrest calls) throws
     * TokenExpiredException into library-internal coroutines instead. Without
     * this loop the app can stay "authenticated" with an expired token
     * indefinitely while Realtime reconnect-crashes in the background.
     *
     * Exits when the session is valid again (refreshed here or by the
     * library's own retry), when the user signs out, or after clearing an
     * unrecoverable session (rejected refresh token → login screen).
     */
    private fun startSessionRecovery() {
        if (recoveryJob?.isActive == true) return
        recoveryJob =
            authScope.launch {
                var backoff = SessionRecoveryPolicy.initialBackoff
                while (isActive) {
                    // Also debounces the first attempt: RefreshFailure fires right
                    // after the library's own failed refresh, so retrying instantly
                    // would almost certainly fail the same way.
                    delay(backoff)
                    try {
                        val auth = SupabaseConfig.client.auth
                        val session = auth.currentSessionOrNull()
                        if (session == null) {
                            logger.info(LogCategory.AUTH, "Session gone; stopping session recovery")
                            return@launch
                        }
                        // supabase-kt only sets SessionStatus.RefreshFailure for an
                        // already-expired session (updateStatusIfExpired guards on
                        // expiresAt <= now), so a future expiry here proves a fresh
                        // session was imported since — by the library's own retry
                        // or a re-login — and recovery is done.
                        if (session.expiresAt > Clock.System.now()) {
                            logger.info(LogCategory.AUTH, "Session was refreshed elsewhere; stopping session recovery")
                            return@launch
                        }
                        auth.refreshCurrentSession()
                        logger.info(LogCategory.AUTH, "Session recovered by manual refresh")
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        when (SessionRecoveryPolicy.actionFor(e)) {
                            is SessionRecoveryPolicy.Action.ClearSession -> {
                                logger.error(
                                    LogCategory.AUTH,
                                    "Refresh token rejected by auth server; clearing session for re-login",
                                    error = e,
                                )
                                clearUnrecoverableSession()
                                return@launch
                            }

                            is SessionRecoveryPolicy.Action.Retry -> {
                                backoff = SessionRecoveryPolicy.nextBackoff(backoff)
                                logger.warn(
                                    LogCategory.AUTH,
                                    "Session refresh attempt failed; retrying",
                                    mapOf(
                                        "nextAttemptIn" to backoff.toString(),
                                        "error" to (e.message ?: e::class.simpleName),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
    }

    /**
     * Clear a session whose refresh token was rejected by the auth server.
     *
     * Order matters: [io.github.jan.supabase.auth.Auth.clearSession] first —
     * it is local-only and cannot fail on the dead token. Going through
     * [SessionManager.clearSession] alone would not work here: its Supabase
     * signOut step is an authenticated network call that fails on the very
     * token we are discarding, leaving the Supabase session alive. Once the
     * session is gone, SessionManager's signOut step no-ops and it handles the
     * rest (persisted user data, auth state reset) for parity with [signOut].
     */
    private suspend fun clearUnrecoverableSession() {
        try {
            SupabaseConfig.client.auth.clearSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to clear unrecoverable session", error = e)
        }
        SessionManager.clearSession().onFailure { error ->
            logger.warn(LogCategory.AUTH, "SessionManager.clearSession failed during recovery", error = error)
        }
    }

    /**
     * Handle offline state at startup
     * Sets auth state to Offline and starts auto-retry
     */
    private fun handleOfflineStart() {
        _isSessionResolved.value = true
        AuthStateManager.setAuthState(AuthService.AuthState.Offline)

        // Start auto-retry in background
        NetworkMonitorService.startAutoRetry {
            logger.info(LogCategory.NETWORK, "Network restored, retrying initialization")
            initializeWithNetwork()
        }
    }

    /**
     * Retry initialization after network is restored
     * Called from OfflineScreen retry button
     */
    suspend fun retryInitialization(): Boolean {
        val isConnected = NetworkMonitorService.manualRetry()
        if (isConnected) {
            logger.info(LogCategory.NETWORK, "Network restored, initializing")
            initializeWithNetwork()
        }
        return isConnected
    }

    /**
     * Sign out the current user
     */
    suspend fun signOut(): Result<Unit> =
        try {
            // A running session recovery loop would race the sign-out with a
            // stray refreshCurrentSession() that could re-import the session
            // being cleared; stop it and wait until it is actually gone.
            recoveryJob?.cancelAndJoin()

            // Use SessionManager for centralized session clearing
            // This handles: Supabase signOut, UserDataStorage clearing, and AuthStateManager reset
            SessionManager.clearSession().fold(
                onSuccess = {
                    logger.info(LogCategory.AUTH, "Session cleared successfully via SessionManager")
                },
                onFailure = { error ->
                    logger.warn(LogCategory.AUTH, "SessionManager.clearSession failed", error = error)
                    // Continue even if SessionManager fails
                },
            )

            // Reset passkey state on logout
            PasskeyAuthService.resetPasskeyState()

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Logout failed", error = e)
            Result.failure(e)
        }
}
