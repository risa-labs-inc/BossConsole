package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Helper object for macOS camera and microphone permissions.
 * Uses JNA to call AVFoundation permission APIs.
 */
private val macOSCameraLogger = BossLogger.forComponent("MacOSCamera")

object MacOSCamera {
    private val isMacOS: Boolean = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    /**
     * Check if camera permission status.
     * Returns true if:
     * - Not on macOS (permission assumed granted)
     * - On macOS and permission is granted or not determined
     */
    fun logCameraPermissionStatus() {
        if (!isMacOS) {
            macOSCameraLogger.info(LogCategory.SYSTEM, "Not on macOS, camera permission assumed granted")
            return
        }

        try {
            // On macOS, camera permissions are handled by the app bundle's entitlements
            // and the system permission dialog. We can't programmatically check/request
            // like we can for screen capture.
            //
            // The app needs:
            // 1. NSCameraUsageDescription in Info.plist (handled in build.gradle.kts)
            // 2. com.apple.security.device.camera entitlement (handled in BOSS.entitlements)
            //
            // If the user hasn't granted permission yet, macOS will show a dialog
            // when the app tries to access the camera.
            macOSCameraLogger.info(LogCategory.SYSTEM, "macOS camera permission is handled by system dialog when accessed")
        } catch (e: Exception) {
            macOSCameraLogger.warn(LogCategory.SYSTEM, "Error checking camera permission status", error = e)
        }
    }
}
