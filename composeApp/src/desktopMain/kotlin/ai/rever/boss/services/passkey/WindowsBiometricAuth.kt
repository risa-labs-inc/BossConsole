package ai.rever.boss.services.passkey

/**
 * Unified interface for Windows biometric authentication
 * Only checks biometric availability - passkey management removed
 */
object WindowsBiometricAuth {

    /**
     * Check if biometric authentication is available on this device
     */
    fun isBiometricAvailable(): Boolean = WindowsHelloAuth.isBiometricAvailable()
}
