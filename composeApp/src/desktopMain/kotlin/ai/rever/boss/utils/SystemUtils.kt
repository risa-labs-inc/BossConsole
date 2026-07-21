package ai.rever.boss.utils

actual object SystemUtils {
    private val osName: String = System.getProperty("os.name").lowercase()

    actual fun getUserHome(): String = System.getProperty("user.home")

    actual fun getCurrentDirectory(): String = System.getProperty("user.dir") ?: getUserHome()

    actual fun getDefaultProjectPath(): String {
        // For desktop, use current directory if available, otherwise user home
        return getCurrentDirectory()
    }

    actual val isMacOS: Boolean = osName.contains("mac")

    actual val isWindows: Boolean = osName.contains("windows")

    actual val isLinux: Boolean = osName.contains("linux") || osName.contains("nix") || osName.contains("nux")
}
