package ai.rever.boss.services.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.plugin.api.AuthEvent
import ai.rever.boss.plugin.api.AuthEventState
import ai.rever.boss.components.plugin.providers.publishSystemEvent

/**
 * Manages authentication state and user session information
 */
internal object AuthStateManager {
    // Main authentication state
    private val _authState = MutableStateFlow<AuthService.AuthState>(AuthService.AuthState.Loading)
    val authState: StateFlow<AuthService.AuthState> = _authState.asStateFlow()
    
    // Current user information
    private val _currentUser = MutableStateFlow<UserInfo?>(null)
    val currentUser: StateFlow<UserInfo?> = _currentUser.asStateFlow()

    // Authentication flags
    var authenticatedViaBiometric = false
        private set
    
    var authenticatedViaMagicLink = false
        private set
    
    var pendingTwoFactorVerification = false
        private set
    
    /**
     * Update authentication state
     */
    fun setAuthState(state: AuthService.AuthState) {
        val previous = _authState.value
        _authState.value = state
        // Bridge real sign-in/out transitions onto the application event bus (for analytics
        // and other subscribers). Only on actual change, and only for the two terminal states.
        if (state != previous) {
            when (state) {
                is AuthService.AuthState.Authenticated ->
                    publishSystemEvent(AuthEvent(authState = AuthEventState.SIGNED_IN))
                is AuthService.AuthState.NotAuthenticated ->
                    publishSystemEvent(AuthEvent(authState = AuthEventState.SIGNED_OUT))
                else -> {}
            }
        }
    }
    
    /**
     * Update current user information
     */
    fun setCurrentUser(user: UserInfo?) {
        _currentUser.value = user
    }
    
    /**
     * Set biometric authentication flag
     */
    fun setAuthenticatedViaBiometric(value: Boolean) {
        authenticatedViaBiometric = value
    }

    /**
     * Set magic link authentication flag
     */
    fun setAuthenticatedViaMagicLink(value: Boolean) {
        authenticatedViaMagicLink = value
    }
    

    /**
     * Reset all authentication state
     */
    fun reset() {
        _currentUser.value = null
        // Route through setAuthState so a SIGNED_OUT event is emitted on the bus.
        setAuthState(AuthService.AuthState.NotAuthenticated)
        authenticatedViaBiometric = false
        authenticatedViaMagicLink = false
        pendingTwoFactorVerification = false
    }
}
