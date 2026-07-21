package ai.rever.boss.crash

import java.security.MessageDigest

/**
 * Utility for generating crash signatures for deduplication.
 *
 * The signature is based on:
 * - Exception type (fully qualified class name)
 * - Top 5 stack frames from BOSS code (ai.rever.boss.*)
 *
 * The exception message is intentionally excluded as it may contain:
 * - File paths
 * - User data
 * - Variable values that change between occurrences
 *
 * This allows the same crash type to be grouped together even if
 * the specific values in the exception message differ.
 */
object CrashSignature {

    private const val BOSS_PACKAGE_PREFIX = "ai.rever.boss"
    private const val MAX_BOSS_FRAMES = 5
    private const val SIGNATURE_LENGTH = 12

    /**
     * Generate a unique signature for a crash.
     *
     * @param throwable The exception that caused the crash
     * @return A 12-character hexadecimal signature
     */
    fun generate(throwable: Throwable): String {
        val signatureInput = buildString {
            // Include exception type
            append(throwable.javaClass.name)
            append("\n")

            // Include top 5 BOSS stack frames
            val bossFrames = throwable.stackTrace
                .filter { it.className.startsWith(BOSS_PACKAGE_PREFIX) }
                .take(MAX_BOSS_FRAMES)

            bossFrames.forEach { frame ->
                append(frame.className)
                append(".")
                append(frame.methodName)
                append(":")
                append(frame.lineNumber)
                append("\n")
            }

            // If no BOSS frames, use top 3 frames from any source
            if (bossFrames.isEmpty()) {
                throwable.stackTrace.take(3).forEach { frame ->
                    append(frame.className)
                    append(".")
                    append(frame.methodName)
                    append(":")
                    append(frame.lineNumber)
                    append("\n")
                }
            }
        }

        return sha256(signatureInput).take(SIGNATURE_LENGTH)
    }

    /**
     * Compute SHA-256 hash and return as hexadecimal string.
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Format a signature for display in issue titles.
     * Returns format: [abc123def456]
     */
    fun formatForTitle(signature: String): String {
        return "[$signature]"
    }
}
