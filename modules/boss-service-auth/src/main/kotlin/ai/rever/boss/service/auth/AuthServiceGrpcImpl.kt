package ai.rever.boss.service.auth

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * gRPC implementation of the AuthService.
 *
 * Manages authentication state and delegates sign-in/sign-out operations to Supabase
 * via [SupabaseAuthClient]. Call [restoreSession] during startup to re-hydrate state
 * from a previously stored Supabase session.
 */
class AuthServiceGrpcImpl(
    private val supabaseClient: SupabaseAuthClient? = null,
) : AuthServiceGrpcKt.AuthServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(AuthServiceGrpcImpl::class.java)

    private val authState = MutableStateFlow(AuthState.AUTH_STATE_NOT_AUTHENTICATED)
    private val currentUser = MutableStateFlow<UserInfo?>(null)
    private val userPermissions = MutableStateFlow<Set<String>>(emptySet())

    override suspend fun getAuthState(request: Empty): AuthStateResponse {
        return AuthStateResponse.newBuilder()
            .setState(authState.value)
            .apply { currentUser.value?.let { setUser(it) } }
            .build()
    }

    override fun watchAuthState(request: Empty): Flow<AuthStateResponse> = flow {
        emit(
            AuthStateResponse.newBuilder()
                .setState(authState.value)
                .apply { currentUser.value?.let { setUser(it) } }
                .build()
        )
        authState.collect { state ->
            emit(
                AuthStateResponse.newBuilder()
                    .setState(state)
                    .apply { currentUser.value?.let { setUser(it) } }
                    .build()
            )
        }
    }

    override suspend fun signIn(request: SignInRequest): SignInResponse {
        logger.info("Sign-in attempt for method: {}", request.authMethod)

        val client = supabaseClient ?: return SignInResponse.newBuilder()
            .setSuccess(false)
            .setErrorMessage("Auth service not configured: SUPABASE_URL / SUPABASE_ANON_KEY missing")
            .build()

        return when (request.authMethod) {
            "magic_link" -> handleMagicLink(client, request.email)
            else -> handleEmailPassword(client, request.email, request.password)
        }
    }

    private suspend fun handleEmailPassword(
        client: SupabaseAuthClient,
        email: String,
        password: String,
    ): SignInResponse {
        return when (val result = client.signInWithEmailPassword(email, password)) {
            is AuthResult.Success -> {
                val userInfo = result.toUserInfo()
                updateAuthState(AuthState.AUTH_STATE_AUTHENTICATED, userInfo, emptySet())
                SignInResponse.newBuilder()
                    .setSuccess(true)
                    .setUser(userInfo)
                    .setSessionToken(result.sessionToken)
                    .build()
            }
            is AuthResult.Failure -> SignInResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage(result.message)
                .build()
            is AuthResult.MagicLinkSent -> SignInResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage("Unexpected magic link result for password auth")
                .build()
        }
    }

    private suspend fun handleMagicLink(client: SupabaseAuthClient, email: String): SignInResponse {
        return when (val result = client.sendMagicLink(email)) {
            is AuthResult.MagicLinkSent -> SignInResponse.newBuilder()
                .setSuccess(true)
                .setErrorMessage("Magic link sent to ${result.email}")
                .build()
            is AuthResult.Failure -> SignInResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage(result.message)
                .build()
            is AuthResult.Success -> SignInResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage("Unexpected success result for magic link")
                .build()
        }
    }

    override suspend fun signOut(request: Empty): SignOutResponse {
        logger.info("Sign-out requested")
        supabaseClient?.signOut()

        authState.value = AuthState.AUTH_STATE_NOT_AUTHENTICATED
        currentUser.value = null
        userPermissions.value = emptySet()

        return SignOutResponse.newBuilder()
            .setSuccess(true)
            .build()
    }

    override suspend fun getCurrentUser(request: Empty): UserInfoResponse {
        val user = currentUser.value
        return UserInfoResponse.newBuilder()
            .setAuthenticated(user != null)
            .apply { user?.let { setUser(it) } }
            .build()
    }

    override fun watchCurrentUser(request: Empty): Flow<UserInfoResponse> = flow {
        emit(
            UserInfoResponse.newBuilder()
                .setAuthenticated(currentUser.value != null)
                .apply { currentUser.value?.let { setUser(it) } }
                .build()
        )
        currentUser.collect { user ->
            emit(
                UserInfoResponse.newBuilder()
                    .setAuthenticated(user != null)
                    .apply { user?.let { setUser(it) } }
                    .build()
            )
        }
    }

    override suspend fun hasPermission(request: PermissionRequest): PermissionResponse {
        return PermissionResponse.newBuilder()
            .setGranted(request.permission in userPermissions.value)
            .build()
    }

    override suspend fun hasAnyPermission(request: HasAnyPermissionRequest): PermissionResponse {
        val granted = request.permissionsList.any { it in userPermissions.value }
        return PermissionResponse.newBuilder()
            .setGranted(granted)
            .build()
    }

    override suspend fun getUserPermissions(request: Empty): UserPermissionsResponse {
        return UserPermissionsResponse.newBuilder()
            .addAllPermissions(userPermissions.value)
            .build()
    }

    override suspend fun isAdmin(request: Empty): IsAdminResponse {
        return IsAdminResponse.newBuilder()
            .setIsAdmin(currentUser.value?.isAdmin ?: false)
            .build()
    }

    /**
     * Attempts to restore a previous Supabase session. Call once at service startup.
     * Updates auth state if a valid session is found.
     */
    suspend fun restoreSession() {
        val client = supabaseClient ?: return
        when (val result = client.restoreSession()) {
            is AuthResult.Success -> {
                val userInfo = result.toUserInfo()
                updateAuthState(AuthState.AUTH_STATE_AUTHENTICATED, userInfo, emptySet())
                logger.info("Auth session restored for user: {}", result.email.take(3) + "***")
            }
            else -> logger.debug("No previous auth session to restore")
        }
    }

    /** Update auth state programmatically (called during session restore, sign-in, etc.) */
    fun updateAuthState(state: AuthState, user: UserInfo?, permissions: Set<String>) {
        authState.value = state
        currentUser.value = user
        userPermissions.value = permissions
    }

    private fun AuthResult.Success.toUserInfo(): UserInfo =
        UserInfo.newBuilder()
            .setUserId(userId)
            .setEmail(email)
            .setDisplayName(displayName)
            .setIsAdmin(isAdmin)
            .setSessionCreatedAt(sessionCreatedAt)
            .build()
}
