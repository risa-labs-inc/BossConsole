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

internal interface CoreGraphics : Library {
    /**
     * Returns true if the app has screen capture access, false otherwise.
     * This does NOT trigger a permission prompt.
     */
    @Suppress("ktlint:standard:function-naming") // JNA maps by native symbol name
    fun CGPreflightScreenCaptureAccess(): Boolean

    /**
     * Requests screen capture access. If access hasn't been determined yet,
     * this will trigger the system permission dialog.
     * Returns true if access is granted, false otherwise.
     */
    @Suppress("ktlint:standard:function-naming") // JNA maps by native symbol name
    fun CGRequestScreenCaptureAccess(): Boolean
}

/**
 * Helper object for macOS screen capture permissions.
 * On non-macOS platforms, these methods return true (permission assumed granted).
 */
object MacOSScreenCapture {
    private val permissions =
        ScreenCapturePermissions(
            isMacOS = System.getProperty("os.name")?.lowercase()?.contains("mac") == true,
            load = { Native.load("CoreGraphics", CoreGraphics::class.java) },
        )

    /**
     * Check if screen recording permission is granted.
     * @return true if permission is granted or not on macOS
     */
    fun hasPermission(): Boolean = permissions.hasPermission()

    /**
     * Request screen recording permission.
     * On macOS, this will trigger the system permission dialog if permission
     * hasn't been determined yet.
     * @return true if permission is granted or not on macOS
     */
    fun requestPermission(): Boolean = permissions.requestPermission()
}

/** Lazy binding and fail-closed calls, injectable without touching real OS permissions in tests. */
internal class ScreenCapturePermissions(
    private val isMacOS: Boolean,
    private val load: () -> CoreGraphics?,
) {
    private val coreGraphics: CoreGraphics? by lazy {
        runCatching { load() }.getOrElse { error ->
            macOSScreenCaptureLogger.warn(LogCategory.SYSTEM, "CoreGraphics not available", error = error)
            null
        }
    }

    fun hasPermission(): Boolean = invokePermission("checking") { it.CGPreflightScreenCaptureAccess() }

    fun requestPermission(): Boolean = invokePermission("requesting") { it.CGRequestScreenCaptureAccess() }

    private fun invokePermission(
        action: String,
        invoke: (CoreGraphics) -> Boolean,
    ): Boolean {
        if (!isMacOS) return true
        // JNA can throw LinkageError during both binding and symbol invocation. Neither must
        // escape the browser callback and leave its permission response unresolved.
        return runCatching { coreGraphics?.let(invoke) ?: false }.getOrElse { error ->
            macOSScreenCaptureLogger.warn(LogCategory.SYSTEM, "Error $action screen capture permission", error = error)
            false
        }
    }
}
