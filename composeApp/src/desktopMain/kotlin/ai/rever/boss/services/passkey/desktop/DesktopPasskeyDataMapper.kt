package ai.rever.boss.services.passkey.desktop

import ai.rever.boss.services.passkey.*

/**
 * Handles desktop-specific data transformation and mapping operations
 * Converts between different data formats and creates platform-specific URLs and HTML
 */
class DesktopPasskeyDataMapper {

    /**
     * Create platform-specific PasskeyRegistration for browser flows
     */
    fun createBrowserPasskeyRegistration(sessionId: String): PasskeyRegistration {
        return PasskeyRegistration(
            credentialId = "browser-registration-${sessionId}",
            publicKey = "",
            attestationObject = "",
            clientDataJSON = "",
            transports = listOf("internal", "hybrid")
        )
    }

    /**
     * Extract platform-specific error information
     */
    fun mapPlatformError(error: Throwable): PasskeyErrorCode {
        val message = error.message?.lowercase() ?: ""
        return when {
            message.contains("cancelled") || message.contains("user") -> PasskeyErrorCode.USER_CANCELLED
            message.contains("unavailable") || message.contains("not supported") -> PasskeyErrorCode.NOT_SUPPORTED
            message.contains("timeout") -> PasskeyErrorCode.TIMEOUT_ERROR
            message.contains("invalid") -> PasskeyErrorCode.INVALID_STATE
            else -> PasskeyErrorCode.UNKNOWN_ERROR
        }
    }

}
