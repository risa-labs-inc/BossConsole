package ai.rever.boss.services.passkey

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.services.passkey.desktop.*
import ai.rever.boss.services.supabase.CrossDeviceAuthenticationRequired
import ai.rever.boss.services.supabase.getSupabaseFunctionUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder
import java.util.*

/**
 * Desktop implementation of PasskeyService using component-based architecture
 * Coordinates biometric authentication, WebAuthn operations, and cross-device flows
 * Supports macOS Touch ID, Windows Hello, and cross-device browser authentication
 */
class DesktopPasskeyService : PasskeyService {
    private val logger = BossLogger.forComponent("DesktopPasskeyService")

    private val _passkeyState = MutableStateFlow<PasskeyState>(PasskeyState.Idle)
    override val passkeyState: StateFlow<PasskeyState> = _passkeyState.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Component dependencies
    private val biometricAuthProvider = BiometricAuthProvider()
    private val browserManager = CrossDeviceBrowserManager()
    private val dataMapper = DesktopPasskeyDataMapper()
    
    init {
        // Show enhanced capabilities after a short delay
        scope.launch {
            delay(1000)
            browserManager.showEnhancedCapabilities()
        }
    }

    override suspend fun isPasskeySupported(): Boolean {
        return try {
            val isSupported = biometricAuthProvider.isBiometricSupported()
            logger.debug(LogCategory.PASSKEY, "Passkey support check result", mapOf(
                "isSupported" to isSupported,
                "platform" to biometricAuthProvider.getCurrentPlatform()
            ))
            isSupported
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Error checking passkey support", error = e)
            false
        }
    }
    override suspend fun registerPasskey(
        userId: String,
        displayName: String,
        challenge: ByteArray,
        rpId: String
    ): Result<PasskeyRegistration> = withContext(Dispatchers.Main) {
        try {
            _passkeyState.value = PasskeyState.Loading
            
            val challengeB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
            logger.info(LogCategory.PASSKEY, "Starting WebAuthn registration via browser")
            
            // Build server WebAuthn registration URL using RESTful endpoint
            val sessionId = UUID.randomUUID().toString()
            val baseUrl = getSupabaseFunctionUrl()
            val registrationUrl = "$baseUrl/passkey/register/mobile?" +
                "challenge=${URLEncoder.encode(challengeB64, "UTF-8")}&" +
                "email=${URLEncoder.encode(displayName, "UTF-8")}&" +
                "sessionId=${URLEncoder.encode(sessionId, "UTF-8")}&" +
                "rpId=${URLEncoder.encode(rpId, "UTF-8")}&" +
                "rpName=${URLEncoder.encode("BOSS", "UTF-8")}"
            
            // Open WebAuthn registration page in embedded Fluck browser
            logger.debug(LogCategory.PASSKEY, "Opening WebAuthn registration in embedded browser")
            _passkeyState.value = PasskeyState.UserGestureRequired

            // Try to use embedded Fluck browser first, fallback to system browser if needed
            val browserResult = browserManager.openInFluckBrowser(registrationUrl, sessionId)
            if (browserResult.isFailure) {
                // Fallback to system browser if Fluck is not available
                logger.debug(LogCategory.PASSKEY, "Fluck browser not available, using system browser fallback")
                val systemBrowserResult = browserManager.openInSystemBrowser(registrationUrl)
                if (systemBrowserResult.isFailure) {
                    throw systemBrowserResult.exceptionOrNull() ?: Exception("Failed to open browser")
                }
                _passkeyState.value = PasskeyState.Success("browser-registration-initiated")
            } else {
                // Set state to show embedded browser in UI
                _passkeyState.value = PasskeyState.ShowEmbeddedBrowser(registrationUrl, sessionId)
                logger.debug(LogCategory.PASSKEY, "Embedded browser ready for WebAuthn registration")
            }

            // Return a special result that tells AuthService not to call completeRegistration
            Result.success(dataMapper.createBrowserPasskeyRegistration(sessionId))
            
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Browser registration error", error = e)
            val errorCode = dataMapper.mapPlatformError(e)
            _passkeyState.value = PasskeyState.Error(e.message ?: "Registration failed", errorCode)
            Result.failure(e)
        }
    }


    override suspend fun authenticateWithPasskey(
        challenge: ByteArray,
        allowedCredentials: List<String>?,
        rpId: String,
        userEmail: String,
        sessionId: String?,
        allowedCredentialTransports: Map<String, List<String>>?
    ): Result<PasskeyAssertion> = withContext(Dispatchers.Main) {
        try {
            _passkeyState.value = PasskeyState.Loading

            logger.info(LogCategory.PASSKEY, "Starting biometric authentication", mapOf("platform" to biometricAuthProvider.getCurrentPlatform()))
            _passkeyState.value = PasskeyState.UserGestureRequired
            
            // Determine authentication method based on credential transports instead of ID patterns
            val actualCredentialId = if (!allowedCredentials.isNullOrEmpty()) {
                allowedCredentials.first()
            } else {
                "unknown-credential"
            }
            
            val currentPlatform = biometricAuthProvider.getCurrentPlatform()
            val transports = allowedCredentialTransports?.get(actualCredentialId) ?: emptyList()

            logger.debug(LogCategory.PASSKEY, "Authentication details", mapOf(
                "platform" to currentPlatform,
                "transports" to transports.toString()
            ))

            logger.debug(LogCategory.PASSKEY, "Using browser WebAuthn for all passkey authentication")

            // Always use browser WebAuthn for passkey authentication - this is the correct approach
            // The browser handles the choice between Touch ID, security keys, or cross-device flow
            val crossDeviceSessionId = sessionId ?: UUID.randomUUID().toString()
            val challengeB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
            val baseUrl = getSupabaseFunctionUrl()
            val authUrl = "$baseUrl/passkey/auth/mobile?" +
                "challenge=${URLEncoder.encode(challengeB64, "UTF-8")}&" +
                "email=${URLEncoder.encode(userEmail, "UTF-8")}&" +
                "sessionId=${URLEncoder.encode(crossDeviceSessionId, "UTF-8")}&" +
                "credentialId=${URLEncoder.encode(actualCredentialId, "UTF-8")}&" +
                "rpId=${URLEncoder.encode(rpId, "UTF-8")}"

            // Open WebAuthn authentication page in embedded Fluck browser
            logger.debug(LogCategory.PASSKEY, "Opening WebAuthn authentication in embedded browser")

            // Try to use embedded Fluck browser first, fallback to system browser if needed
            val browserResult = browserManager.openInFluckBrowser(authUrl, crossDeviceSessionId)
            if (browserResult.isFailure) {
                // Fallback to system browser and QR code flow if Fluck is not available
                logger.debug(LogCategory.PASSKEY, "Fluck browser not available, using system browser fallback")
                val systemBrowserResult = browserManager.openInSystemBrowser(authUrl)
                if (systemBrowserResult.isFailure) {
                    // If system browser also fails, show QR code
                    throw CrossDeviceAuthenticationRequired(
                        qrCodeUrl = authUrl,
                        challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge),
                        sessionId = crossDeviceSessionId,
                        message = "Complete authentication in browser - Touch ID will be available there"
                    )
                }
                // Throw exception to trigger polling flow for system browser
                throw CrossDeviceAuthenticationRequired(
                    qrCodeUrl = authUrl,
                    challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge),
                    sessionId = crossDeviceSessionId,
                    message = "Complete authentication in browser - Touch ID will be available there"
                )
            } else {
                // Set state to show embedded browser in UI
                _passkeyState.value = PasskeyState.ShowEmbeddedBrowser(authUrl, crossDeviceSessionId)
                logger.debug(LogCategory.PASSKEY, "Embedded browser ready for WebAuthn authentication")

                // Throw exception to trigger polling flow while embedded browser is shown
                // Set browserAlreadyOpened=true to prevent system browser from opening
                throw CrossDeviceAuthenticationRequired(
                    qrCodeUrl = authUrl,
                    challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge),
                    sessionId = crossDeviceSessionId,
                    browserAlreadyOpened = true,
                    message = "Complete authentication in embedded browser - Touch ID will be available there"
                )
            }

        } catch (e: CrossDeviceAuthenticationRequired) {
            // Return as Result.failure to trigger polling flow in PasskeyAuthService
            logger.debug(LogCategory.PASSKEY, "Cross-device authentication required, propagating as Result.failure")
            Result.failure(e)
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Biometric authentication error", error = e)
            val errorCode = dataMapper.mapPlatformError(e)
            _passkeyState.value = PasskeyState.Error(e.message ?: "Authentication failed", errorCode)
            Result.failure(e)
        }
    }
    override suspend fun isUserPresent(): Boolean {
        // Check if user gesture is available (simplified implementation)
        return true
    }

    /**
     * Reset passkey state to Idle
     * Call this after successful authentication or on logout to clean up state
     */
    override fun resetState() {
        _passkeyState.value = PasskeyState.Idle
        logger.debug(LogCategory.PASSKEY, "State reset to Idle")
    }

}
