package ai.rever.boss.services.passkey

import ai.rever.boss.services.passkey.supabase.*
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import io.ktor.client.statement.bodyAsText

/**
 * Server-side passkey service that handles backend operations via Supabase
 * Works in conjunction with platform-specific PasskeyService implementations
 *
 * This service coordinates various specialized handlers for different passkey operations:
 * - Registration flows via PasskeyRegistrationHandler
 * - Authentication flows via PasskeyAuthenticationHandler
 * - Cross-device URL generation via CrossDeviceUrlGenerator
 * - Data transformation via PasskeyDataMapper
 * - HTTP communication via SupabaseApiClient
 */
object SupabasePasskeyService {
    private val logger = BossLogger.forComponent("SupabasePasskeyService")

    suspend fun requestRegistrationChallenge(
        userId: String,
        displayName: String,
        authenticatorSelection: AuthenticatorSelectionCriteria?,
    ): Result<PasskeyChallenge> {
        // Validate input parameters
        PasskeyRegistrationHandler
            .validateRegistrationRequest(userId, displayName)
            .onFailure { return Result.failure(it) }

        PasskeyRegistrationHandler
            .validateAuthenticatorSelection(authenticatorSelection)
            .onFailure { return Result.failure(it) }

        return PasskeyRegistrationHandler.requestChallenge(
            userId = userId,
            displayName = displayName,
            authenticatorSelection = authenticatorSelection,
        )
    }

    /**
     * Complete passkey registration with Supabase
     * @param userId User identifier
     * @param registration Registration result from platform service
     * @param challenge Original challenge used for registration
     * @return Success or failure result
     */
    suspend fun completeRegistration(
        userId: String,
        registration: PasskeyRegistration,
        challenge: String,
    ): Result<PasskeyCredential> {
        // Validate input parameters
        PasskeyRegistrationHandler
            .validateRegistrationCompletion(userId, registration, challenge)
            .onFailure { return Result.failure(it) }

        return PasskeyRegistrationHandler.completeRegistration(
            userId = userId,
            registration = registration,
            challenge = challenge,
        )
    }

    /**
     * Request passkey authentication challenge from Supabase
     * @param email Optional user email (for usernameless login)
     * @return Challenge and authentication options
     */
    suspend fun requestAuthenticationChallenge(
        email: String? = null,
        sessionId: String? = null,
    ): Result<PasskeyAuthenticationChallenge> {
        // Validate input parameters
        PasskeyAuthenticationHandler
            .validateAuthenticationRequest(email, sessionId)
            .onFailure { return Result.failure(it) }

        return PasskeyAuthenticationHandler.requestChallenge(
            email = email,
            sessionId = sessionId,
        )
    }

    /**
     * Complete passkey authentication with Supabase
     * @param assertion Authentication assertion from platform service
     * @param challenge Original challenge used for authentication
     * @return Authentication result with user session
     */
    suspend fun completeAuthentication(
        assertion: PasskeyAssertion,
        challenge: String,
    ): Result<PasskeyAuthenticationResult> {
        // Validate input parameters
        PasskeyAuthenticationHandler
            .validateAuthenticationCompletion(assertion, challenge)
            .onFailure { return Result.failure(it) }

        return PasskeyAuthenticationHandler.completeAuthentication(
            assertion = assertion,
            challenge = challenge,
        )
    }

    /**
     * Get user's registered passkey credentials from Supabase
     * @param userId User identifier
     * @return List of user's passkey credentials
     */
    suspend fun getUserPasskeys(userId: String): Result<List<PasskeyCredential>> {
        return try {
            if (userId.isBlank()) {
                return Result.failure(IllegalArgumentException("User ID cannot be blank"))
            }

            logger.debug(LogCategory.PASSKEY, "Fetching passkeys for user", mapOf("userId" to LogSanitizer.maskUserId(userId)))

            val requestData =
                PasskeyDataMapper.createManagementRequest(
                    action = "list",
                    userId = userId,
                )

            // Call Edge Function for passkey management
            val response = SupabaseApiClient.listPasskeys(requestData)

            val responseText = response.bodyAsText()
            val managementResponse = PasskeyDataMapper.parseManagementResponse(responseText)

            if (managementResponse.success) {
                val passkeys = managementResponse.passkeyList()
                logger.info(
                    LogCategory.PASSKEY,
                    "Fetched user passkeys",
                    mapOf(
                        "userId" to LogSanitizer.maskUserId(userId),
                        "count" to passkeys.size,
                    ),
                )
                Result.success(passkeys)
            } else {
                logger.warn(LogCategory.PASSKEY, "Failed to fetch user passkeys", mapOf("error" to managementResponse.error))
                Result.failure(Exception(managementResponse.error ?: "Failed to fetch passkeys"))
            }
        } catch (e: Exception) {
            // Ignore cancellation exceptions (when composable is disposed)
            if (e is java.util.concurrent.CancellationException) {
                return Result.failure(e)
            }
            logger.error(LogCategory.PASSKEY, "Failed to fetch user passkeys", error = e)
            Result.failure(e)
        }
    }

    /**
     * Delete a passkey credential
     * @param userId User identifier
     * @param credentialId Credential ID to delete
     * @return Success or failure result
     */
    suspend fun deletePasskey(
        userId: String,
        credentialId: String,
    ): Result<Unit> {
        return try {
            if (userId.isBlank() || credentialId.isBlank()) {
                return Result.failure(IllegalArgumentException("User ID and credential ID cannot be blank"))
            }

            logger.debug(
                LogCategory.PASSKEY,
                "Deleting passkey",
                mapOf(
                    "userId" to LogSanitizer.maskUserId(userId),
                    "credentialId" to LogSanitizer.maskCredentialId(credentialId),
                ),
            )

            val requestData =
                PasskeyDataMapper.createManagementRequest(
                    action = "delete",
                    userId = userId,
                    passkeyId = credentialId,
                )

            // Call Edge Function for passkey management
            val response = SupabaseApiClient.deletePasskey(requestData)

            val responseText = response.bodyAsText()
            logger.debug(LogCategory.PASSKEY, "Delete passkey response received")
            val managementResponse = PasskeyDataMapper.parseManagementResponse(responseText)

            if (managementResponse.success) {
                logger.info(
                    LogCategory.PASSKEY,
                    "Passkey deleted successfully",
                    mapOf(
                        "userId" to LogSanitizer.maskUserId(userId),
                    ),
                )
                Result.success(Unit)
            } else {
                logger.warn(LogCategory.PASSKEY, "Failed to delete passkey", mapOf("error" to managementResponse.error))
                Result.failure(Exception(managementResponse.error ?: "Failed to delete passkey"))
            }
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Failed to delete passkey", error = e)
            Result.failure(e)
        }
    }

    /**
     * Check authentication status for cross-device flows
     */
    suspend fun checkAuthenticationStatus(
        challenge: String,
        sessionId: String? = null,
    ): Result<PasskeyAuthenticationResult> {
        // Validate input parameters
        PasskeyAuthenticationHandler
            .validateStatusCheck(challenge, sessionId)
            .onFailure { return Result.failure(it) }

        return PasskeyAuthenticationHandler.checkStatus(
            challenge = challenge,
            sessionId = sessionId,
        )
    }

    // Utility methods for backward compatibility and convenience
}
