package ai.rever.boss.services.auth

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * PasskeySessionEventHandler - Handles passkey session completion events from deep links
 *
 * This service coordinates cross-device passkey flows by:
 * - Tracking active passkey sessions by sessionId
 * - Notifying listeners when passkey operations complete via deep links
 * - Triggering appropriate UI updates and authentication completion
 */
object PasskeySessionEventHandler {
    private val logger = BossLogger.forComponent("PasskeySessionEventHandler")

    /**
     * Passkey session event types
     */
    sealed class PasskeySessionEvent {
        data class RegistrationCompleted(
            val sessionId: String,
        ) : PasskeySessionEvent()

        data class AuthenticationCompleted(
            val sessionId: String,
        ) : PasskeySessionEvent()
    }

    /**
     * Flow of passkey session events
     */
    private val _sessionEvents = MutableStateFlow<PasskeySessionEvent?>(null)

    /**
     * Map of active sessions being tracked
     * Key: sessionId, Value: session metadata
     */
    private val activeSessions = mutableMapOf<String, SessionMetadata>()

    data class SessionMetadata(
        val sessionId: String,
        val email: String,
        val type: SessionType,
        val timestamp: Long = System.currentTimeMillis(),
    )

    enum class SessionType

    /**
     * Handle passkey registration completion from deep link
     */
    fun handleRegistrationCompleted(sessionId: String) {
        logger.info(LogCategory.PASSKEY, "Registration completed for session")

        val metadata = activeSessions[sessionId]
        if (metadata != null) {
            _sessionEvents.value = PasskeySessionEvent.RegistrationCompleted(sessionId)
            logger.debug(LogCategory.PASSKEY, "Notified listeners of registration completion")
        } else {
            logger.warn(LogCategory.PASSKEY, "No active session found for registration completion")
        }
    }

    /**
     * Handle passkey authentication completion from deep link
     */
    fun handleAuthenticationCompleted(sessionId: String) {
        logger.info(LogCategory.PASSKEY, "Authentication completed for session")

        val metadata = activeSessions[sessionId]
        if (metadata != null) {
            _sessionEvents.value = PasskeySessionEvent.AuthenticationCompleted(sessionId)
            logger.debug(LogCategory.PASSKEY, "Notified listeners of authentication completion")
        } else {
            logger.warn(LogCategory.PASSKEY, "No active session found for authentication completion")
        }
    }

    /**
     * Get metadata for an active session
     */
    fun getSessionMetadata(sessionId: String): SessionMetadata? = activeSessions[sessionId]
}
