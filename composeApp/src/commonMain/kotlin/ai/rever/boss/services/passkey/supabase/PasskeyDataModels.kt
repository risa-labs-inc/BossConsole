package ai.rever.boss.services.passkey.supabase

import ai.rever.boss.services.passkey.AuthenticatorSelectionCriteria
import kotlinx.serialization.Serializable

// Data classes for Supabase API communication

@Serializable
data class PasskeyRegistrationRequest(
    val userId: String,
    val displayName: String,
    val challenge: String,
    val authenticatorSelection: AuthenticatorSelectionCriteria? = null
)

@Serializable
data class PasskeyChallengeResponse(
    val challenge: String,
    val timeout: Long = 60000L,
    val rpId: String? = null, // Optional - client will provide when opening browser page
    val rpName: String = "BOSS",
    val attestation: String = "none",
    val authenticatorSelection: AuthenticatorSelectionCriteria? = null,
    val excludeCredentials: List<String>? = null,
    val challengeId: String = "", // Kept for legacy API compatibility
    val sessionId: String? = null // Optional session ID for efficient status polling
)

@Serializable
data class PasskeyRegistrationData(
    val userId: String,
    val challenge: String,
    val credentialId: String,
    val attestationObject: String,
    val clientDataJSON: String,
    val transports: List<String>
)

@Serializable
data class PasskeyCredential(
    val id: String? = null,
    val user_id: String? = null,
    val credential_id: String,
    val public_key: String? = null,
    val display_name: String,
    val transports: List<String>,
    val created_at: Long = System.currentTimeMillis(),
    val last_used_at: Long? = null,
    val active: Boolean = true
)

@Serializable
data class PasskeyCredentialResponse(
    val success: Boolean,
    val credential: PasskeyCredential? = null,
    val error: String? = null
)

@Serializable
data class PasskeyAuthenticationRequest(
    val email: String? = null,
    val challenge: String,
    val sessionId: String? = null,
    val op: String? = null
)

@Serializable
data class PasskeyAuthenticationChallenge(
    val challenge: String,
    val timeout: Long = 60000L,
    val rpId: String, // No default - must be provided based on environment
    val allowCredentials: List<PasskeyAllowedCredential>? = null,
    val userVerification: String = "preferred"
)

@Serializable
data class PasskeyAllowedCredential(
    val id: String,
    val type: String = "public-key",
    val transports: List<String>? = null
)

@Serializable
data class PasskeyAuthenticationChallengeResponse(
    val challenge: String,
    val timeout: Long = 60000L,
    val rpId: String? = null, // Optional - client will fall back to config if not provided
    val allowCredentials: List<PasskeyAllowedCredential>? = null,
    val userVerification: String = "preferred"
)

@Serializable
data class PasskeyAuthenticationData(
    val challenge: String,
    val credentialId: String,
    val authenticatorData: String,
    val signature: String,
    val clientDataJSON: String,
    val userHandle: String? = null
)

@Serializable
data class PasskeyAuthenticationResult(
    val success: Boolean,
    val userId: String? = null,
    val email: String? = null,
    val sessionToken: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val error: String? = null
)

@Serializable
data class PasskeyManagementRequest(
    val action: String, // "delete" or "list"
    val userId: String,
    val passkeyId: String? = null // Required for delete action
)

@Serializable
data class PasskeyManagementResponse(
    val success: Boolean = false, // Default to false for error responses
    val passkeys: List<PasskeyCredential>? = null, // Server returns "passkeys" field, not "data"
    val data: List<PasskeyCredential>? = null, // Keep for backward compatibility
    val message: String? = null,
    val error: String? = null
) {
    // Helper to get passkeys from either field
    fun passkeyList(): List<PasskeyCredential> = passkeys ?: data ?: emptyList()
}

@Serializable
data class PasskeyAuthStatusResponse(
    val status: String, // "pending", "completed", "expired"
    val expiresAt: Long? = null, // Unix timestamp (seconds since epoch)
    val userId: String? = null,
    val email: String? = null,
    val completedAt: String? = null,
    val message: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null
)

