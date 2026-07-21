package ai.rever.boss.utils

import platform.Foundation.NSHomeDirectory

actual object SystemUtils {
    actual fun getUserHome(): String = NSHomeDirectory()

    actual fun getCurrentDirectory(): String = NSHomeDirectory()

    actual fun getDefaultProjectPath(): String = NSHomeDirectory()

    // iOS is neither macOS, Windows, nor Linux in this context
    actual val isMacOS: Boolean = false
    actual val isWindows: Boolean = false
    actual val isLinux: Boolean = false
}
