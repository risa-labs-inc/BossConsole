package ai.rever.boss.services.passkey.supabase

import ai.rever.boss.services.passkey.*
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Exception thrown when no passkeys are found for a user
 */
class NoPasskeysFoundException(message: String) : Exception(message)

/**
 * Handles passkey authentication flow operations
 */
internal object PasskeyAuthenticationHandler {

    private val logger = BossLogger.forComponent("PasskeyAuthenticationHandler")
    
    /**
     * Request passkey authentication challenge
     */
    suspend fun requestChallenge(
        email: String? = null,
        sessionId: String? = null
    ): Result<PasskeyAuthenticationChallenge> {
        return try {
            logger.debug(LogCategory.PASSKEY, "Requesting authentication challenge", mapOf(
                "email" to (email?.let { LogSanitizer.maskEmail(it) } ?: "usernameless"),
                "hasSessionId" to (sessionId != null)
            ))

            val challenge = PasskeyDataMapper.generateChallenge()

            val requestData = PasskeyDataMapper.createAuthenticationRequest(
                email = email,
                challenge = challenge,
                sessionId = sessionId
            )

            // Call Edge Function for authentication challenge - operation is in body
            val response = SupabaseApiClient.invokeAuthenticationChallenge(requestData)

            logger.trace(LogCategory.PASSKEY, "Got response", mapOf("status" to response.status.toString()))
            val responseText = response.bodyAsText()

            // Check if the response indicates no passkeys found (404)
            if (response.status.value == 404 && responseText.contains("No passkeys found")) {
                logger.debug(LogCategory.PASSKEY, "No passkeys found for user - expected for users without passkeys")
                return Result.failure(NoPasskeysFoundException("No passkeys found for user"))
            }

            // Check for other error statuses
            if (response.status.value >= 400) {
                logger.warn(LogCategory.PASSKEY, "Server returned error status", mapOf("status" to response.status.toString()))
                return Result.failure(Exception("Server error: $responseText"))
            }

            val challengeResponse = PasskeyDataMapper.parseAuthenticationChallenge(responseText)
            logger.debug(LogCategory.PASSKEY, "Parsed challenge response successfully")

            Result.success(challengeResponse)
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Failed to request authentication challenge", error = e)
            Result.failure(e)
        }
    }
    
    /**
     * Complete passkey authentication
     */
    suspend fun completeAuthentication(
        assertion: PasskeyAssertion,
        challenge: String
    ): Result<PasskeyAuthenticationResult> {
        return try {
            logger.debug(LogCategory.PASSKEY, "Completing authentication", mapOf("credentialId" to LogSanitizer.maskCredentialId(assertion.credentialId)))

            val authenticationData = PasskeyDataMapper.createAuthenticationData(
                assertion = assertion,
                challenge = challenge
            )

            // Call Edge Function for authentication completion
            val response = SupabaseApiClient.completeAuthentication(authenticationData)

            val responseText = response.bodyAsText()
            val authResult = PasskeyDataMapper.parseAuthenticationResult(responseText)

            if (authResult.success) {
                logger.info(LogCategory.PASSKEY, "Passkey authentication completed successfully")
                Result.success(authResult)
            } else {
                logger.warn(LogCategory.PASSKEY, "Passkey authentication failed", mapOf("error" to (authResult.error ?: "unknown")))
                Result.failure(Exception(authResult.error ?: "Authentication failed"))
            }
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Failed to complete authentication", error = e)
            Result.failure(e)
        }
    }

    /**
     * Check authentication status for cross-device flows
     */
    suspend fun checkStatus(challenge: String, sessionId: String? = null): Result<PasskeyAuthenticationResult> {
        return try {
            logger.debug(LogCategory.PASSKEY, "Checking authentication status")

            // Use sessionId for status check endpoint (GET /auth/status/{sessionId})
            val effectiveSessionId = sessionId ?: challenge // Fallback to challenge if no sessionId

            val response = SupabaseApiClient.checkAuthenticationStatus(effectiveSessionId)

            val responseText = response.bodyAsText()
            logger.trace(LogCategory.PASSKEY, "Authentication status check response received")

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Failed to check authentication status: HTTP ${response.status.value}"))
            }

            val authResult = PasskeyDataMapper.parseAuthenticationResult(responseText)
            Result.success(authResult)

        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Error checking authentication status", error = e)
            Result.failure(e)
        }
    }
    
    /**
     * Validate authentication request parameters
     */
    fun validateAuthenticationRequest(
        email: String?,
        sessionId: String?
    ): Result<Unit> {
        return when {
            email != null && email.isBlank() -> Result.failure(
                IllegalArgumentException("Email cannot be blank if provided")
            )
            email != null && !isValidEmail(email) -> Result.failure(
                IllegalArgumentException("Invalid email format")
            )
            sessionId != null && sessionId.isBlank() -> Result.failure(
                IllegalArgumentException("Session ID cannot be blank if provided")
            )
            sessionId != null && sessionId.length > 128 -> Result.failure(
                IllegalArgumentException("Session ID cannot exceed 128 characters")
            )
            else -> Result.success(Unit)
        }
    }
    
    /**
     * Validate authentication completion data
     */
    fun validateAuthenticationCompletion(
        assertion: PasskeyAssertion,
        challenge: String
    ): Result<Unit> {
        return when {
            challenge.isBlank() -> Result.failure(
                IllegalArgumentException("Challenge cannot be blank")
            )
            assertion.credentialId.isBlank() -> Result.failure(
                IllegalArgumentException("Credential ID cannot be blank")
            )
            assertion.authenticatorData.isBlank() -> Result.failure(
                IllegalArgumentException("Authenticator data cannot be blank")
            )
            assertion.signature.isBlank() -> Result.failure(
                IllegalArgumentException("Signature cannot be blank")
            )
            assertion.clientDataJSON.isBlank() -> Result.failure(
                IllegalArgumentException("Client data JSON cannot be blank")
            )
            else -> Result.success(Unit)
        }
    }
    
    /**
     * Validate authentication status check parameters
     */
    fun validateStatusCheck(
        challenge: String,
        sessionId: String?
    ): Result<Unit> {
        return when {
            challenge.isBlank() -> Result.failure(
                IllegalArgumentException("Challenge cannot be blank")
            )
            sessionId != null && sessionId.isBlank() -> Result.failure(
                IllegalArgumentException("Session ID cannot be blank if provided")
            )
            else -> Result.success(Unit)
        }
    }

    /**
     * Simple email validation
     */
    private fun isValidEmail(email: String): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
        return email.matches(emailRegex)
    }
}
