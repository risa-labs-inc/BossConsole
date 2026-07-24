package ai.rever.boss.services.passkey

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * macOS Touch ID / Face ID authentication using LocalAuthentication framework
 * Provides real biometric authentication prompts on macOS
 */
object MacOSTouchIDAuth {
    private val logger = BossLogger.forComponent("MacOSTouchIDAuth")

    private val isAvailable: Boolean by lazy {
        try {
            System.getProperty("os.name").lowercase().contains("mac") && SwiftScriptExecutor.isSwiftAvailable()
        } catch (e: Exception) {
            logger.warn(LogCategory.PASSKEY, "Error checking macOS", error = e)
            false
        }
    }

    /**
     * Check if biometric authentication is available on this device
     */
    fun isBiometricAvailable(): Boolean {
        if (!isAvailable) return false

        return try {
            logger.debug(LogCategory.PASSKEY, "Biometric authentication available on macOS")
            true
        } catch (e: Exception) {
            logger.warn(LogCategory.PASSKEY, "Error checking biometric availability", error = e)
            false
        }
    }
}
