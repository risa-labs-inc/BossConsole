package ai.rever.boss.services.auth

import ai.rever.boss.services.passkey.PasskeyService
import ai.rever.boss.services.passkey.SupabasePasskeyService
import ai.rever.boss.services.supabase.models.*
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import io.ktor.http.encodeURLParameter

/**
 * Handles user existence checking and credential detection
 */
internal object UserExistenceService {
    private var passkeyService: PasskeyService? = null
    private val logger = BossLogger.forComponent("UserExistenceService")

    /**
     * Set the platform-specific passkey service implementation
     */
    fun setPasskeyService(service: PasskeyService) {
        passkeyService = service
    }

    /**
     * Check if a user exists with the given email address
     */
    suspend fun checkUserExists(email: String): Result<UserExistence> =
        try {
            logger.debug(LogCategory.AUTH, "Checking if user exists", mapOf("email" to LogSanitizer.maskEmail(email)))

            // Check if user actually exists by attempting to get authentication challenge
            // If user doesn't exist, the server will return a 404 "User not found" error

            var userExists = true // Default to true for security
            val hasPasskeys =
                try {
                    // ALWAYS check server first for available authentication methods
                    val challengeResult =
                        SupabasePasskeyService.requestAuthenticationChallenge(
                            email,
                            "check-${java.util.UUID.randomUUID()}",
                        )

                    if (challengeResult.isSuccess) {
                        val challenge = challengeResult.getOrNull()
                        // If we get a challenge with allowCredentials, it means passkeys exist on server
                        val hasServerPasskeys = challenge?.allowCredentials?.isNotEmpty() == true
                        logger.debug(LogCategory.AUTH, "Server passkeys check result", mapOf("hasPasskeys" to hasServerPasskeys))
                        userExists = true // User exists if we got a successful challenge response
                        hasServerPasskeys
                    } else {
                        val errorMessage = challengeResult.exceptionOrNull()?.message ?: ""
                        logger.debug(LogCategory.AUTH, "Authentication challenge failed")

                        // Check if the error indicates user doesn't exist
                        if (errorMessage.contains("User not found") || errorMessage.contains("404")) {
                            logger.debug(LogCategory.AUTH, "User does not exist on server")
                            userExists = false
                        }

                        // No local fallback - authentication requires server connection
                        logger.debug(LogCategory.AUTH, "Cannot check passkeys availability (server error)")
                        false
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.AUTH, "Could not check passkeys availability", error = e)
                    // If we can't check passkeys, assume false for security
                    false
                }

            // Get available credential details for enhanced login options
            val availableCredentials =
                try {
                    if (hasPasskeys) {
                        val challengeResult =
                            SupabasePasskeyService.requestAuthenticationChallenge(
                                email,
                                "check-${java.util.UUID.randomUUID()}",
                            )
                        if (challengeResult.isSuccess) {
                            val challenge = challengeResult.getOrNull()
                            challenge?.allowCredentials?.map { cred ->
                                // Determine credential type based on credential ID pattern and transports
                                val credType =
                                    when {
                                        // Check if it's a Touch ID credential by ID pattern
                                        cred.id.contains("touchid-credential") -> WebAuthnCredentialType.PLATFORM

                                        // Check for USB security keys
                                        cred.transports?.contains(
                                            "usb",
                                        ) == true && !cred.transports.contains("internal") -> WebAuthnCredentialType.USB_KEY

                                        // Check for NFC security keys
                                        cred.transports?.contains(
                                            "nfc",
                                        ) == true && !cred.transports.contains("internal") -> WebAuthnCredentialType.NFC_KEY

                                        // Cross-device/hybrid credentials (not Touch ID)
                                        cred.transports?.contains(
                                            "hybrid",
                                        ) == true && !cred.id.contains("touchid-credential") -> WebAuthnCredentialType.CROSS_DEVICE

                                        // Platform authenticators (Touch ID, Windows Hello)
                                        cred.transports?.contains("internal") == true -> WebAuthnCredentialType.PLATFORM

                                        // Fallback
                                        else -> WebAuthnCredentialType.UNKNOWN
                                    }

                                AvailableWebAuthnCredential(
                                    credentialId = cred.id,
                                    displayName = credType.displayName,
                                    transports = cred.transports ?: emptyList(),
                                    credentialType = credType,
                                )
                            } ?: emptyList()
                        } else {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.AUTH, "Error getting credential details", error = e)
                    emptyList()
                }

            Result.success(
                UserExistence(
                    exists = userExists, // Use actual server response
                    hasPasskeys = hasPasskeys,
                    email = email,
                    availableCredentials = availableCredentials,
                ),
            )
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Error checking user existence", error = e)
            Result.failure(e)
        }
}
