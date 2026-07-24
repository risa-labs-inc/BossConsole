@file:Suppress("UNUSED")

package ai.rever.boss.utils.logging

/**
 * Re-exports from plugin-logging module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.logging
 */

// Re-export LogSanitizer object
// Note: We can't typealias an object, so we delegate to it
object LogSanitizer {
    private val delegate = ai.rever.boss.plugin.logging.LogSanitizer

    fun maskEmail(email: String?): String = delegate.maskEmail(email)

    fun maskToken(token: String?): String = delegate.maskToken(token)

    fun maskCredentialId(credentialId: String?): String = delegate.maskCredentialId(credentialId)

    fun maskUserId(userId: String?): String = delegate.maskUserId(userId)

    fun maskUriParams(uri: String?): String = delegate.maskUriParams(uri)

    fun maskSessionId(sessionId: String?): String = delegate.maskSessionId(sessionId)

    fun describeUri(uri: String?): String = delegate.describeUri(uri)

    fun looksLikeSecret(value: String?): Boolean = delegate.looksLikeSecret(value)

    fun sanitizeMap(map: Map<String, Any?>?): Map<String, Any?> = delegate.sanitizeMap(map)

    fun sanitizeExceptionMessage(message: String?): String = delegate.sanitizeExceptionMessage(message)

    fun sanitizeLogMessage(message: String?): String = delegate.sanitizeLogMessage(message)

    fun sanitizeStackTrace(stackTrace: String?): String = delegate.sanitizeStackTrace(stackTrace)
}
