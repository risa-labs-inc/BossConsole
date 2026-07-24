package ai.rever.boss.services.auth

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized service for magic link verification errors
 * Bridges BossAppWithAuth (deep link handler) and UI layer
 *
 * Flow: BossAppWithAuth → setError() → AuthScreenContainer observes → UI displays
 */
object MagicLinkErrorService {
    private val _verificationError = MutableStateFlow<String?>(null)
    val verificationError: StateFlow<String?> = _verificationError.asStateFlow()
    private val logger = BossLogger.forComponent("MagicLinkErrorService")

    /**
     * Set magic link verification error
     * Called from BossAppWithAuth when deep link verification fails
     */
    fun setError(message: String) {
        logger.warn(LogCategory.AUTH, "Setting magic link error", mapOf("message" to message))
        _verificationError.value = message
    }

    /**
     * Clear magic link verification error
     * Called when user navigates away or resends magic link
     */
    fun clearError() {
        logger.debug(LogCategory.AUTH, "Clearing magic link error")
        _verificationError.value = null
    }
}
