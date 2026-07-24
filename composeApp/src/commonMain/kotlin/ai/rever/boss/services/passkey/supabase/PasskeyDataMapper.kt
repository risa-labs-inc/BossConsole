package ai.rever.boss.services.passkey.supabase

import ai.rever.boss.services.passkey.*
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.util.Base64

/**
 * Handles data transformation and CBOR parsing for passkey operations
 */
internal object PasskeyDataMapper {
    private val logger = BossLogger.forComponent("PasskeyDataMapper")

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Public json instance for other components to use
    val publicJson = json

    /**
     * Generate a cryptographically secure challenge
     */
    fun generateChallenge(): String {
        val random = SecureRandom()
        val challengeBytes = ByteArray(32) // 256 bits
        random.nextBytes(challengeBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)
    }

    /**
     * Creates registration request data
     */
    fun createRegistrationRequest(
        userId: String,
        displayName: String,
        challenge: String,
        authenticatorSelection: AuthenticatorSelectionCriteria?,
    ): PasskeyRegistrationRequest =
        PasskeyRegistrationRequest(
            userId = userId,
            displayName = displayName,
            challenge = challenge,
            authenticatorSelection = authenticatorSelection,
        )

    /**
     * Creates registration completion data
     */
    fun createRegistrationData(
        userId: String,
        registration: PasskeyRegistration,
        challenge: String,
    ): PasskeyRegistrationData =
        PasskeyRegistrationData(
            userId = userId,
            challenge = challenge,
            credentialId = registration.credentialId,
            attestationObject = registration.attestationObject,
            clientDataJSON = registration.clientDataJSON,
            transports = registration.transports,
        )

    /**
     * Creates authentication request data
     */
    fun createAuthenticationRequest(
        email: String?,
        challenge: String,
        sessionId: String?,
    ): PasskeyAuthenticationRequest =
        PasskeyAuthenticationRequest(
            email = email,
            challenge = challenge,
            sessionId = sessionId,
            op = "auth-challenge",
        )

    /**
     * Creates authentication completion data
     */
    fun createAuthenticationData(
        assertion: PasskeyAssertion,
        challenge: String,
    ): PasskeyAuthenticationData =
        PasskeyAuthenticationData(
            challenge = challenge,
            credentialId = assertion.credentialId,
            authenticatorData = assertion.authenticatorData,
            signature = assertion.signature,
            clientDataJSON = assertion.clientDataJSON,
            userHandle = assertion.userHandle,
        )

    /**
     * Creates passkey management request
     */
    fun createManagementRequest(
        action: String,
        userId: String,
        passkeyId: String? = null,
    ): PasskeyManagementRequest =
        PasskeyManagementRequest(
            action = action,
            userId = userId,
            passkeyId = passkeyId,
        )

    /**
     * Parses challenge response from API
     */
    fun parsePasskeyChallenge(
        responseText: String,
        userId: String,
        displayName: String,
        authenticatorSelection: AuthenticatorSelectionCriteria?,
    ): PasskeyChallenge {
        val challengeResponse = json.decodeFromString<PasskeyChallengeResponse>(responseText)

        // Use rpId from response, or fall back to PasskeyConfigHelper if not provided
        val rpId = challengeResponse.rpId ?: PasskeyConfigHelper.getRpId()

        return PasskeyChallenge(
            challenge = challengeResponse.challenge,
            timeout = challengeResponse.timeout,
            rpId = rpId,
            rpName = challengeResponse.rpName,
            userId = userId,
            userDisplayName = displayName,
            attestation = challengeResponse.attestation,
            authenticatorSelection = authenticatorSelection ?: challengeResponse.authenticatorSelection,
            excludeCredentials = challengeResponse.excludeCredentials,
        )
    }

    /**
     * Parses registration response from API
     */
    fun parseRegistrationResponse(responseText: String): Result<PasskeyCredential> =
        try {
            val credentialResponse = json.decodeFromString<PasskeyCredentialResponse>(responseText)

            if (credentialResponse.success && credentialResponse.credential != null) {
                Result.success(credentialResponse.credential)
            } else {
                Result.failure(Exception(credentialResponse.error ?: "Registration failed"))
            }
        } catch (parseException: Exception) {
            // Exception type only: kotlinx decode errors embed a snippet of the
            // response near the failure offset, and server responses can carry
            // credential/session material that must not reach the logs.
            logger.warn(
                LogCategory.PASSKEY,
                "Registration response did not parse as credential - extracting error message",
                mapOf("exception" to (parseException::class.simpleName ?: "Exception")),
            )
            // Try to extract error message from raw response
            val errorMessage =
                try {
                    val errorResponse = json.decodeFromString<JsonObject>(responseText)
                    errorResponse["error"]?.toString()?.removeSurrounding("\"") ?: "Unknown error"
                } catch (e: Exception) {
                    logger.debug(
                        LogCategory.PASSKEY,
                        "Raw error extraction from response also failed",
                        mapOf(
                            "exception" to (e::class.simpleName ?: "Exception"),
                        ),
                    )
                    "Failed to parse response: $responseText"
                }

            Result.failure(Exception("Registration completion failed: $errorMessage"))
        }

    /**
     * Parses authentication challenge response from API
     */
    fun parseAuthenticationChallenge(responseText: String): PasskeyAuthenticationChallenge {
        val challengeResponse = json.decodeFromString<PasskeyAuthenticationChallengeResponse>(responseText)

        // Use rpId from response, or fall back to PasskeyConfigHelper if not provided
        // This ensures consistency with registration flow
        val rpId = challengeResponse.rpId ?: PasskeyConfigHelper.getRpId()

        return PasskeyAuthenticationChallenge(
            challenge = challengeResponse.challenge,
            timeout = challengeResponse.timeout,
            rpId = rpId,
            allowCredentials = challengeResponse.allowCredentials,
            userVerification = challengeResponse.userVerification,
        )
    }

    /**
     * Parses authentication result from API
     */
    fun parseAuthenticationResult(responseText: String): PasskeyAuthenticationResult {
        // Try to parse as status response first (for polling endpoints)
        return try {
            val statusResponse = json.decodeFromString<PasskeyAuthStatusResponse>(responseText)
            // Convert status response to authentication result
            when (statusResponse.status) {
                "completed" -> {
                    PasskeyAuthenticationResult(
                        success = true,
                        userId = statusResponse.userId,
                        email = statusResponse.email,
                        accessToken = statusResponse.accessToken,
                        refreshToken = statusResponse.refreshToken,
                        expiresAt = statusResponse.expiresAt,
                        error = null,
                    )
                }

                "pending" -> {
                    PasskeyAuthenticationResult(
                        success = false,
                        error = "Authentication pending",
                    )
                }

                "expired" -> {
                    PasskeyAuthenticationResult(
                        success = false,
                        error = statusResponse.message ?: "Authentication expired",
                    )
                }

                else -> {
                    PasskeyAuthenticationResult(
                        success = false,
                        error = "Unknown status: ${statusResponse.status}",
                    )
                }
            }
        } catch (e: Exception) {
            // Fall back to parsing as authentication result (for completion endpoints).
            // Exception type only - decode errors can embed response snippets.
            logger.debug(
                LogCategory.PASSKEY,
                "Response is not a status envelope - parsing as authentication result",
                mapOf("exception" to (e::class.simpleName ?: "Exception")),
            )
            json.decodeFromString<PasskeyAuthenticationResult>(responseText)
        }
    }

    /**
     * Parses passkey management response from API
     */
    fun parseManagementResponse(responseText: String): PasskeyManagementResponse =
        json.decodeFromString<PasskeyManagementResponse>(responseText)
}
