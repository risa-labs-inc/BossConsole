package ai.rever.boss.viewmodels

import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.services.supabase.CrossDeviceAuthenticationRequired
import ai.rever.boss.services.supabase.models.*
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Passkey authentication view model handling WebAuthn flows
 * Responsible for: passkey authentication, registration, cross-device authentication
 *
 * State machine invariant: at most ONE authentication attempt is current at a time.
 * Starting a new attempt cancels the previous one and advances an epoch, so even an
 * authentication implementation that swallows cancellation cannot let a superseded
 * attempt mutate auth state or fire its onSuccess callback. Dismissing or cancelling
 * locally retires the QR URL, challenge and session id held by this view model; it does
 * not revoke the server-side challenge or close an external browser.
 */
class PasskeyAuthViewModel(
    // Default preserves production behavior; injectable so a test can assert the scope is cancelled.
    private val viewModelScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val passkeyAuthentication: suspend (email: String, credentialId: String?) -> Result<Unit> =
        AuthService::authenticateWithPasskey,
) {
    private val logger = BossLogger.forComponent("PasskeyAuthViewModel")

    // Handle of the single in-flight authentication attempt
    private var authJob: Job? = null
    private var authAttemptEpoch = 0L

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
        val passkeyInfos =
            credentials.map { cred ->
                ai.rever.boss.services.passkey.PasskeyInfo(
                    id = "", // Not needed for selection
                    credentialId = cred.credentialId,
                    displayName = cred.displayName,
                    createdAt = System.currentTimeMillis(), // Use current time as placeholder
                    lastUsed = null, // Last used is nullable
                    rpId = "localhost", // Use local rpId for local testing
                    transports = cred.transports,
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
     * Start [block] as the single in-flight authentication attempt, cancelling any
     * attempt that is still running so a superseded attempt can neither mutate auth
     * state nor fire its onSuccess callback.
     */
    private fun launchAuthentication(block: suspend (isCurrent: () -> Boolean) -> Unit) {
        authJob?.cancel()
        val epoch = ++authAttemptEpoch
        authJob = viewModelScope.launch { block { epoch == authAttemptEpoch } }
    }

    /**
     * Cancel ongoing authentication and reset state
     */
    fun cancelAuthentication() {
        authAttemptEpoch++
        authJob?.cancel()
        authJob = null
        _isLoading.value = false
        _errorMessage.value = null
        retireCrossDeviceIdentity()
        logger.debug(LogCategory.PASSKEY, "Authentication cancelled")
    }

    /**
     * Authenticate with email and Touch ID - streamlined flow
     */
    fun authenticateWithEmailAndPasskey(
        email: String,
        onSuccess: () -> Unit,
    ) {
        if (email.isBlank()) {
            _errorMessage.value = "Please enter your email"
            return
        }

        launchAuthentication { isCurrent ->
            _isLoading.value = true
            _errorMessage.value = null

            // Use email-based passkey authentication
            // This will trigger Touch ID and identify the user from their credential
            val result = passkeyAuthentication(email, null)
            if (!isCurrent()) return@launchAuthentication
            result.fold(
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

                    _errorMessage.value =
                        when {
                            error.message?.contains("not supported") == true -> {
                                "Touch ID authentication is not supported on this device"
                            }

                            error.message?.contains("cancelled") == true -> {
                                "Touch ID authentication was cancelled"
                            }

                            error.message?.contains("not available") == true -> {
                                "Touch ID not available. Please ensure you have set up Touch ID on your Mac"
                            }

                            error.message?.contains("unavailable") == true -> {
                                "Touch ID authentication is not available"
                            }

                            else -> {
                                error.message ?: "Email + Touch ID authentication failed"
                            }
                        }
                    _isLoading.value = false
                },
            )
        }
    }

    /**
     * Authenticate with specific passkey (when user selects from multiple passkeys)
     */
    fun authenticateWithSpecificPasskey(
        email: String,
        credentialId: String,
        onSuccess: () -> Unit,
    ) {
        if (email.isBlank()) {
            _errorMessage.value = "Please enter your email"
            return
        }

        launchAuthentication { isCurrent ->
            _isLoading.value = true
            _errorMessage.value = null

            // Authenticate with specific credential ID
            val result = passkeyAuthentication(email, credentialId)
            if (!isCurrent()) return@launchAuthentication
            result.fold(
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

                    _errorMessage.value =
                        when {
                            error.message?.contains("not supported") == true -> {
                                "Biometric authentication is not supported on this device"
                            }

                            error.message?.contains("cancelled") == true -> {
                                "Authentication was cancelled"
                            }

                            error.message?.contains("not available") == true -> {
                                "Biometric authentication not available"
                            }

                            else -> {
                                error.message ?: "Authentication failed"
                            }
                        }
                    _isLoading.value = false
                },
            )
        }
    }

    /**
     * Dismiss the cross-device QR dialog
     *
     * Clears the local presentation identity of the dismissed attempt. This does not
     * revoke its server-side challenge or close an external browser.
     */
    fun dismissCrossDeviceQR() {
        retireCrossDeviceIdentity()
    }

    private fun retireCrossDeviceIdentity() {
        _showCrossDeviceQR.value = false
        _crossDeviceQRUrl.value = null
        _crossDeviceChallenge.value = null
        _crossDeviceSessionId.value = null
    }

    /**
     * Cancel the view-model scope so any in-flight passkey/cross-device work cannot outlive the
     * auth screen. Call from the owning composable's onDispose.
     */
    fun dispose() {
        viewModelScope.cancel()
    }
}
