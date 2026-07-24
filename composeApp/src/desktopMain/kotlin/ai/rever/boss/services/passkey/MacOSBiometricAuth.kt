package ai.rever.boss.services.passkey

/**
 * Unified interface for macOS biometric authentication
 * Only checks biometric availability - passkey management removed
 */
object MacOSBiometricAuth {
    /**
     * Check if biometric authentication is available on this device
     */
    fun isBiometricAvailable(): Boolean = MacOSTouchIDAuth.isBiometricAvailable()
}
