package ai.rever.boss.services.passkey.supabase

import ai.rever.boss.services.passkey.*
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import io.ktor.client.statement.*

/**
 * Handles passkey registration flow operations
 */
internal object PasskeyRegistrationHandler {

    private val logger = BossLogger.forComponent("PasskeyRegistrationHandler")
    
    /**
     * Request passkey registration challenge
     */
    suspend fun requestChallenge(
        userId: String,
        displayName: String,
        authenticatorSelection: AuthenticatorSelectionCriteria? = null
    ): Result<PasskeyChallenge> {
        return try {
            logger.debug(LogCategory.PASSKEY, "Requesting registration challenge", mapOf("userId" to LogSanitizer.maskUserId(userId)))

            val challenge = PasskeyDataMapper.generateChallenge()
            val requestData = PasskeyDataMapper.createRegistrationRequest(
                userId = userId,
                displayName = displayName,
                challenge = challenge,
                authenticatorSelection = authenticatorSelection
            )

            logger.trace(LogCategory.PASSKEY, "Registration request created")
            
            // Call Edge Function for registration challenge
            val response = SupabaseApiClient.invokeRegistrationChallenge(requestData)
            
            val responseText = response.bodyAsText()
            val parsedChallenge = PasskeyDataMapper.parsePasskeyChallenge(
                responseText = responseText,
                userId = userId,
                displayName = displayName,
                authenticatorSelection = authenticatorSelection
            )
            
            Result.success(parsedChallenge)
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Failed to request registration challenge", error = e)
            Result.failure(e)
        }
    }

    /**
     * Complete passkey registration
     */
    suspend fun completeRegistration(
        userId: String,
        registration: PasskeyRegistration,
        challenge: String
    ): Result<PasskeyCredential> {
        return try {
            logger.debug(LogCategory.PASSKEY, "Completing passkey registration", mapOf("userId" to LogSanitizer.maskUserId(userId)))

            val registrationData = PasskeyDataMapper.createRegistrationData(
                userId = userId,
                registration = registration,
                challenge = challenge
            )

            // Call Edge Function for registration completion
            val response = SupabaseApiClient.completeRegistration(registrationData)

            val responseText = response.bodyAsText()
            logger.debug(LogCategory.PASSKEY, "Registration completion response received", mapOf("status" to response.status.toString()))

            val result = PasskeyDataMapper.parseRegistrationResponse(responseText)

            when {
                result.isSuccess -> {
                    logger.info(LogCategory.PASSKEY, "Passkey registration completed successfully")
                    result
                }
                else -> {
                    logger.warn(LogCategory.PASSKEY, "Passkey registration failed", error = result.exceptionOrNull())
                    result
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Failed to complete registration", error = e)
            Result.failure(e)
        }
    }
    
    /**
     * Validate registration request parameters
     */
    fun validateRegistrationRequest(
        userId: String,
        displayName: String
    ): Result<Unit> {
        return when {
            userId.isBlank() -> Result.failure(
                IllegalArgumentException("User ID cannot be blank")
            )
            displayName.isBlank() -> Result.failure(
                IllegalArgumentException("Display name cannot be blank")
            )
            userId.length > 64 -> Result.failure(
                IllegalArgumentException("User ID cannot exceed 64 characters")
            )
            displayName.length > 64 -> Result.failure(
                IllegalArgumentException("Display name cannot exceed 64 characters")
            )
            else -> Result.success(Unit)
        }
    }
    
    /**
     * Validate registration completion data
     */
    fun validateRegistrationCompletion(
        userId: String,
        registration: PasskeyRegistration,
        challenge: String
    ): Result<Unit> {
        return when {
            userId.isBlank() -> Result.failure(
                IllegalArgumentException("User ID cannot be blank")
            )
            challenge.isBlank() -> Result.failure(
                IllegalArgumentException("Challenge cannot be blank")
            )
            registration.credentialId.isBlank() -> Result.failure(
                IllegalArgumentException("Credential ID cannot be blank")
            )
            registration.attestationObject.isBlank() -> Result.failure(
                IllegalArgumentException("Attestation object cannot be blank")
            )
            registration.clientDataJSON.isBlank() -> Result.failure(
                IllegalArgumentException("Client data JSON cannot be blank")
            )
            registration.transports.isEmpty() -> Result.failure(
                IllegalArgumentException("Transport methods cannot be empty")
            )
            else -> Result.success(Unit)
        }
    }
    
    /**
     * Validate authenticator selection criteria
     *
     * Note: AuthenticatorSelectionCriteria properties are non-nullable with default values.
     * When the object exists (not null), all properties have values from either:
     * 1. Server JSON response
     * 2. Default values during deserialization
     *
     * Validation checks enum values directly (no null checks needed).
     * Example: "platform" !in ["platform", "cross-platform"] = FALSE = passes validation
     */
    fun validateAuthenticatorSelection(
        authenticatorSelection: AuthenticatorSelectionCriteria?
    ): Result<Unit> {
        if (authenticatorSelection == null) {
            return Result.success(Unit)
        }

        return when {
            // Validate authenticatorAttachment enum
            authenticatorSelection.authenticatorAttachment !in listOf("platform", "cross-platform") -> {
                Result.failure(
                    IllegalArgumentException("Invalid authenticator attachment: ${authenticatorSelection.authenticatorAttachment}")
                )
            }
            // Validate userVerification enum
            authenticatorSelection.userVerification !in listOf("required", "preferred", "discouraged") -> {
                Result.failure(
                    IllegalArgumentException("Invalid user verification requirement: ${authenticatorSelection.userVerification}")
                )
            }
            // Validate residentKey enum
            authenticatorSelection.residentKey !in listOf("required", "preferred", "discouraged") -> {
                Result.failure(
                    IllegalArgumentException("Invalid resident key requirement: ${authenticatorSelection.residentKey}")
                )
            }
            // Note: requireResidentKey validation removed - was non-functional with non-nullable defaults
            // Server-side validation in TypeScript handles this case
            else -> Result.success(Unit)
        }
    }

}
