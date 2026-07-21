package ai.rever.boss.services.passkey

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Cross-platform interface for passkey authentication
 * Supports WebAuthn/FIDO2 passkeys on desktop platforms
 */
interface PasskeyService {
    
    /**
     * Current state of passkey authentication
     */
    val passkeyState: StateFlow<PasskeyState>
    
    /**
     * Check if passkeys are supported on the current platform
     */
    suspend fun isPasskeySupported(): Boolean

    /**
     * Register a new passkey for the user
     * @param userId Unique user identifier
     * @param displayName Human-readable name for the user
     * @param challenge Server-provided challenge bytes
     * @param rpId Relying party identifier (domain)
     * @return Registration result with credential data
     */
    suspend fun registerPasskey(
        userId: String,
        displayName: String,
        challenge: ByteArray,
        rpId: String = "api.risaboss.com"
    ): Result<PasskeyRegistration>

    /**
     * Authenticate using an existing passkey
     * @param challenge Server-provided challenge bytes
     * @param allowedCredentials Optional list of allowed credential IDs
     * @param rpId Relying party identifier (domain)
     * @return Authentication result with assertion data
     */
    suspend fun authenticateWithPasskey(
        challenge: ByteArray,
        allowedCredentials: List<String>? = null,
        rpId: String = "api.risaboss.com",
        userEmail: String,
        sessionId: String? = null,
        allowedCredentialTransports: Map<String, List<String>>? = null
    ): Result<PasskeyAssertion>
    
    /**
     * Check if user gesture is available (user presence)
     */
    suspend fun isUserPresent(): Boolean

    /**
     * Reset passkey state to Idle
     * Called after successful authentication or on logout to clean up state
     */
    fun resetState()
}

/**
 * Passkey authentication state
 */
sealed class PasskeyState {
    object Idle : PasskeyState()
    object Loading : PasskeyState()
    object UserGestureRequired : PasskeyState()
    data class ShowEmbeddedBrowser(val url: String, val sessionId: String) : PasskeyState()
    data class Success(val credentialId: String) : PasskeyState()
    data class Error(val message: String, val code: PasskeyErrorCode) : PasskeyState()
}

/**
 * Passkey error codes
 */
enum class PasskeyErrorCode {
    NOT_SUPPORTED,
    USER_CANCELLED,
    INVALID_STATE,
    TIMEOUT_ERROR,
    UNKNOWN_ERROR
}

/**
 * Passkey registration result
 */
@Serializable
data class PasskeyRegistration(
    val credentialId: String,
    val publicKey: String,
    val attestationObject: String,
    val clientDataJSON: String,
    val transports: List<String> = listOf("internal", "hybrid")
)

/**
 * Passkey authentication assertion
 */
@Serializable
data class PasskeyAssertion(
    val credentialId: String,
    val authenticatorData: String,
    val signature: String,
    val clientDataJSON: String,
    val userHandle: String?
)

/**
 * Passkey information
 */
@Serializable
data class PasskeyInfo(
    val id: String? = null, // Database ID (required for deletion)
    val credentialId: String,
    val displayName: String,
    val createdAt: Long,
    val lastUsed: Long?,
    val rpId: String,
    val transports: List<String>
)

/**
 * WebAuthn challenge data
 */
@Serializable
data class PasskeyChallenge(
    val challenge: String,
    val timeout: Long = 60000L,
    val rpId: String = "api.risaboss.com",
    val rpName: String = "BOSS",
    val userId: String,
    val userDisplayName: String,
    val attestation: String = "none",
    val authenticatorSelection: AuthenticatorSelectionCriteria? = null,
    val excludeCredentials: List<String>? = null
)

/**
 * Authenticator selection criteria
 */
@Serializable
data class AuthenticatorSelectionCriteria(
    val authenticatorAttachment: String = "platform",  // "platform" for built-in, "cross-platform" for external
    val residentKey: String = "preferred",  // "required", "preferred", "discouraged"
    val requireResidentKey: Boolean = false,
    val userVerification: String = "preferred"  // "required", "preferred", "discouraged"
)
