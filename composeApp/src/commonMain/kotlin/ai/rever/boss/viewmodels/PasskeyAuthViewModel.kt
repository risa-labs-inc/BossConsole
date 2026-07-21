package ai.rever.boss.viewmodels

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.services.supabase.CrossDeviceAuthenticationRequired
import ai.rever.boss.services.supabase.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Passkey authentication view model handling WebAuthn flows
 * Responsible for: passkey authentication, registration, cross-device authentication
 */
class PasskeyAuthViewModel {
    private val logger = BossLogger.forComponent("PasskeyAuthViewModel")
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Store authentication job reference for cancellation
    private var authJob: Job? = null

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // Cross-device authentication state
    private val _showCrossDeviceQR = MutableStateFlow(false)
    val showCrossDeviceQR: StateFlow<Boolean> = _showCrossDeviceQR.asStateFlow()
    
    private val _crossDeviceQRUrl = MutableStateFlow<String?>(null)
    val crossDeviceQRUrl: StateFlow<String?> = _crossDeviceQRUrl.asStateFlow()
    
    private val _crossDeviceChallenge = MutableStateFlow<String?>(null)
    val crossDeviceChallenge: StateFlow<String?> = _crossDeviceChallenge.asStateFlow()
    
    private val _crossDeviceSessionId = MutableStateFlow<String?>(null)
    val crossDeviceSessionId: StateFlow<String?> = _crossDeviceSessionId.asStateFlow()

    // Passkey selection state
    private val _availablePasskeys = MutableStateFlow<List<ai.rever.boss.services.passkey.PasskeyInfo>>(emptyList())
    val availablePasskeys: StateFlow<List<ai.rever.boss.services.passkey.PasskeyInfo>> = _availablePasskeys.asStateFlow()

    private val _fetchingPasskeys = MutableStateFlow(false)
    val fetchingPasskeys: StateFlow<Boolean> = _fetchingPasskeys.asStateFlow()

    // Passkey state from PasskeyService (for embedded browser trigger)
    val passkeyState: StateFlow<ai.rever.boss.services.passkey.PasskeyState>?
        get() = AuthService.getPasskeyState()

    /**
     * Set available passkeys from external source (e.g. UserExistence check)
     * Used during login flow when credentials are already known from checkUserExists()
     */
    fun setAvailablePasskeys(credentials: List<ai.rever.boss.services.supabase.models.AvailableWebAuthnCredential>) {
        val passkeyInfos = credentials.map { cred ->
            ai.rever.boss.services.passkey.PasskeyInfo(
                id = "",  // Not needed for selection
                credentialId = cred.credentialId,
                displayName = cred.displayName,
                createdAt = System.currentTimeMillis(),  // Use current time as placeholder
                lastUsed = null,  // Last used is nullable
                rpId = "localhost",  // Use local rpId for local testing
                transports = cred.transports
            )
        }
        _availablePasskeys.value = passkeyInfos
        logger.debug(LogCategory.PASSKEY, "Set available passkeys from credentials", mapOf("count" to passkeyInfos.size))
    }

    /**
     * Fetch user's registered passkeys for selection (used in settings/management screens)
     */
    suspend fun fetchUserPasskeys(email: String): Result<List<ai.rever.boss.services.passkey.PasskeyInfo>> {
        _fetchingPasskeys.value = true
        val result = AuthService.getUserPasskeys()
        _fetchingPasskeys.value = false

        result.onSuccess { passkeys ->
            _availablePasskeys.value = passkeys
        }

        return result
    }

    /**
     * Cancel ongoing authentication and reset state
     */
    fun cancelAuthentication() {
        authJob?.cancel()
        authJob = null
        _isLoading.value = false
        _errorMessage.value = null
        logger.debug(LogCategory.PASSKEY, "Authentication cancelled")
    }

    /**
     * Authenticate with email and Touch ID - streamlined flow
     */
    fun authenticateWithEmailAndPasskey(email: String, onSuccess: () -> Unit) {
        if (email.isBlank()) {
            _errorMessage.value = "Please enter your email"
            return
        }

        authJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            // Use email-based passkey authentication 
            // This will trigger Touch ID and identify the user from their credential
            AuthService.authenticateWithPasskey(email = email).fold(
                onSuccess = {
                    logger.info(LogCategory.PASSKEY, "Email + Touch ID authentication successful")
                    _isLoading.value = false
                    onSuccess()
                },
                onFailure = { error ->
                    logger.warn(LogCategory.PASSKEY, "Email + Touch ID authentication failed", error = error)

                    // Check if this is a cross-device authentication requirement
                    if (error is CrossDeviceAuthenticationRequired) {
                        // Only show QR dialog if browser is not already opened (embedded browser case)
                        if (!error.browserAlreadyOpened) {
                            _showCrossDeviceQR.value = true
                            _crossDeviceQRUrl.value = error.qrCodeUrl
                            _crossDeviceChallenge.value = error.challenge
                            _crossDeviceSessionId.value = error.sessionId
                        } else {
                            logger.debug(LogCategory.PASSKEY, "Browser already opened (embedded), skipping QR dialog")
                        }
                        _isLoading.value = false
                        return@fold
                    }

                    _errorMessage.value = when {
                        error.message?.contains("not supported") == true -> 
                            "Touch ID authentication is not supported on this device"
                        error.message?.contains("cancelled") == true -> 
                            "Touch ID authentication was cancelled"
                        error.message?.contains("not available") == true -> 
                            "Touch ID not available. Please ensure you have set up Touch ID on your Mac"
                        error.message?.contains("unavailable") == true -> 
                            "Touch ID authentication is not available"
                        else -> error.message ?: "Email + Touch ID authentication failed"
                    }
                    _isLoading.value = false
                }
            )
        }
    }

    /**
     * Authenticate with specific passkey (when user selects from multiple passkeys)
     */
    fun authenticateWithSpecificPasskey(email: String, credentialId: String, onSuccess: () -> Unit) {
        if (email.isBlank()) {
            _errorMessage.value = "Please enter your email"
            return
        }

        authJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            // Authenticate with specific credential ID
            AuthService.authenticateWithPasskey(email = email, credentialId = credentialId).fold(
                onSuccess = {
                    logger.info(LogCategory.PASSKEY, "Specific passkey authentication successful")
                    _isLoading.value = false
                    onSuccess()
                },
                onFailure = { error ->
                    logger.warn(LogCategory.PASSKEY, "Specific passkey authentication failed", error = error)

                    // Check if this is a cross-device authentication requirement
                    if (error is CrossDeviceAuthenticationRequired) {
                        // Only show QR dialog if browser is not already opened (embedded browser case)
                        if (!error.browserAlreadyOpened) {
                            _showCrossDeviceQR.value = true
                            _crossDeviceQRUrl.value = error.qrCodeUrl
                            _crossDeviceChallenge.value = error.challenge
                            _crossDeviceSessionId.value = error.sessionId
                        } else {
                            logger.debug(LogCategory.PASSKEY, "Browser already opened (embedded), skipping QR dialog")
                        }
                        _isLoading.value = false
                        return@fold
                    }

                    _errorMessage.value = when {
                        error.message?.contains("not supported") == true ->
                            "Biometric authentication is not supported on this device"
                        error.message?.contains("cancelled") == true ->
                            "Authentication was cancelled"
                        error.message?.contains("not available") == true ->
                            "Biometric authentication not available"
                        else -> error.message ?: "Authentication failed"
                    }
                    _isLoading.value = false
                }
            )
        }
    }

    /**
     * Dismiss the cross-device QR dialog
     */
    fun dismissCrossDeviceQR() {
        _showCrossDeviceQR.value = false
        _crossDeviceQRUrl.value = null
        _crossDeviceChallenge.value = null
    }

}
