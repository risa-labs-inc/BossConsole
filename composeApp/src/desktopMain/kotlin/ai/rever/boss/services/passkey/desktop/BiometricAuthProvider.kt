package ai.rever.boss.services.passkey.desktop

import ai.rever.boss.services.passkey.MacOSBiometricAuth
import ai.rever.boss.services.passkey.WindowsBiometricAuth
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides platform-specific biometric authentication capabilities
 * Handles Touch ID on macOS, Windows Hello on Windows, and fallback for Linux
 */
class BiometricAuthProvider {
    private val logger = BossLogger.forComponent("BiometricAuthProvider")
    private val currentPlatform = System.getProperty("os.name").lowercase()

    /**
     * Check if biometric authentication is supported and available on the current platform
     */
    suspend fun isBiometricSupported(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                when {
                    currentPlatform.contains("mac") -> {
                        val available = MacOSBiometricAuth.isBiometricAvailable()
                        logger.debug(LogCategory.PASSKEY, "macOS Touch ID availability", mapOf("available" to available))
                        available
                    }

                    currentPlatform.contains("windows") -> {
                        val available = WindowsBiometricAuth.isBiometricAvailable()
                        logger.debug(LogCategory.PASSKEY, "Windows Hello availability", mapOf("available" to available))
                        available
                    }

                    currentPlatform.contains("linux") -> {
                        logger.debug(LogCategory.PASSKEY, "Linux biometric support not available")
                        false
                    }

                    else -> {
                        logger.warn(LogCategory.PASSKEY, "Unknown platform", mapOf("platform" to currentPlatform))
                        false
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.PASSKEY, "Error checking biometric support", error = e)
                false
            }
        }

    /**
     * Check if the current platform is macOS
     */
    fun isMacOS(): Boolean = currentPlatform.contains("mac")

    /**
     * Check if the current platform is Windows
     */
    fun isWindows(): Boolean = currentPlatform.contains("windows")

    /**
     * Get the current platform identifier
     */
    fun getCurrentPlatform(): String =
        when {
            currentPlatform.contains("mac") -> "mac"
            currentPlatform.contains("windows") -> "windows"
            currentPlatform.contains("linux") -> "linux"
            else -> "unknown"
        }
}
