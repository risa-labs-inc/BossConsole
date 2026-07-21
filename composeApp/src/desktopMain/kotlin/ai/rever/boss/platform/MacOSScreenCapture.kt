package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.jna.Library
import com.sun.jna.Native

/**
 * JNA bindings for macOS CoreGraphics screen capture permission APIs.
 * These APIs allow checking and requesting screen recording permission
 * from the main application process.
 */
private val macOSScreenCaptureLogger = BossLogger.forComponent("MacOSScreenCapture")

private interface CoreGraphics : Library {
    companion object {
        val INSTANCE: CoreGraphics? = try {
            Native.load("CoreGraphics", CoreGraphics::class.java)
        } catch (e: Exception) {
            macOSScreenCaptureLogger.debug(LogCategory.SYSTEM, "CoreGraphics not available", mapOf("error" to (e.message ?: "unknown")))
            null
        }
    }

    /**
     * Returns true if the app has screen capture access, false otherwise.
     * This does NOT trigger a permission prompt.
     */
    fun CGPreflightScreenCaptureAccess(): Boolean

    /**
     * Requests screen capture access. If access hasn't been determined yet,
     * this will trigger the system permission dialog.
     * Returns true if access is granted, false otherwise.
     */
    fun CGRequestScreenCaptureAccess(): Boolean
}

/**
 * Helper object for macOS screen capture permissions.
 * On non-macOS platforms, these methods return true (permission assumed granted).
 */
object MacOSScreenCapture {
    private val isMacOS: Boolean = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    /**
     * Check if screen recording permission is granted.
     * @return true if permission is granted or not on macOS
     */
    fun hasPermission(): Boolean {
        if (!isMacOS) return true

        return try {
            CoreGraphics.INSTANCE?.CGPreflightScreenCaptureAccess() ?: true
        } catch (e: Exception) {
            macOSScreenCaptureLogger.warn(LogCategory.SYSTEM, "Error checking screen capture permission", error = e)
            true // Assume granted on error
        }
    }

    /**
     * Request screen recording permission.
     * On macOS, this will trigger the system permission dialog if permission
     * hasn't been determined yet.
     * @return true if permission is granted or not on macOS
     */
    fun requestPermission(): Boolean {
        if (!isMacOS) return true

        return try {
            val result = CoreGraphics.INSTANCE?.CGRequestScreenCaptureAccess() ?: true
            macOSScreenCaptureLogger.debug(LogCategory.SYSTEM, "Screen capture permission request", mapOf("result" to result))
            result
        } catch (e: Exception) {
            macOSScreenCaptureLogger.warn(LogCategory.SYSTEM, "Error requesting screen capture permission", error = e)
            true // Assume granted on error
        }
    }
}
