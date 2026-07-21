package ai.rever.boss.viewmodels

import ai.rever.boss.services.supabase.models.*
import ai.rever.boss.viewmodels.auth.AuthOptions
import ai.rever.boss.viewmodels.auth.AuthOptionsManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Facade pattern coordinating multiple authentication component ViewModels
 * Responsible for: orchestrating login flows, exposing unified state
 */
class LoginViewModel {
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Component ViewModels
    private val coreLoginViewModel = CoreLoginViewModel()
    val passkeyAuthViewModel = PasskeyAuthViewModel()
    val authOptionsManager = AuthOptionsManager()

    // Exposed state flows that delegate to appropriate component ViewModels

    // Core loading state (for backward compatibility - magic link flow)
    val isLoading: StateFlow<Boolean> = coreLoginViewModel.isLoading
    val errorMessage: StateFlow<String?> = coreLoginViewModel.errorMessage

    // Combined loading state - true when ANY component is loading
    val isAnyLoading: StateFlow<Boolean> = combine(
        coreLoginViewModel.isLoading,
        passkeyAuthViewModel.isLoading,
        authOptionsManager.isLoading
    ) { coreLoading, passkeyLoading, optionsLoading ->
        coreLoading || passkeyLoading || optionsLoading
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    // Cross-device authentication state from PasskeyAuthViewModel
    val showCrossDeviceQR: StateFlow<Boolean> = passkeyAuthViewModel.showCrossDeviceQR
    val crossDeviceQRUrl: StateFlow<String?> = passkeyAuthViewModel.crossDeviceQRUrl
    val crossDeviceChallenge: StateFlow<String?> = passkeyAuthViewModel.crossDeviceChallenge
    val crossDeviceSessionId: StateFlow<String?> = passkeyAuthViewModel.crossDeviceSessionId

    fun sendMagicLink(email: String, onSuccess: () -> Unit) {
        coreLoginViewModel.sendMagicLink(email, onSuccess)
    }

    /**
     * Authenticate with email and Touch ID - streamlined flow
     */
    fun authenticateWithEmailAndPasskey(email: String, onSuccess: () -> Unit) {
        passkeyAuthViewModel.authenticateWithEmailAndPasskey(email, onSuccess)
    }

    /**
     * Dismiss the cross-device QR dialog
     */
    fun dismissCrossDeviceQR() {
        passkeyAuthViewModel.dismissCrossDeviceQR()
    }
    
    /**
     * Check if a user exists with the given email and return their authentication options
     */
    fun checkUserExists(email: String, onResult: (AuthOptions) -> Unit) {
        authOptionsManager.checkUserExists(email, onResult)
    }

    /**
     * Clear magic link error message
     */
    fun clearMagicLinkError() {
        coreLoginViewModel.clearError()
    }

    /**
     * Set magic link verification error
     * Called from AuthScreenContainer when deep link verification fails
     */
    fun setMagicLinkVerificationError(errorMessage: String) {
        coreLoginViewModel.setMagicLinkVerificationError(errorMessage)
    }
}

