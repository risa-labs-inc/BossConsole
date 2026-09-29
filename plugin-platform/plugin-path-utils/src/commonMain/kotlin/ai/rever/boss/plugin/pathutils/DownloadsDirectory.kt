package ai.rever.boss.plugin.pathutils

import java.io.File

/**
 * The user's Downloads folder, resolved the same way for every caller.
 *
 * BOSS used to answer this question in three places that disagreed: the browser's save
 * location read the XDG user-dirs config, the in-process `FileSystemDataProvider` used
 * `~/Downloads` and fell back to the home folder, and the out-of-process proxy
 * concatenated `user.home + "/Downloads"` - a path that on Windows carried mixed
 * separators and, when `~/Downloads` did not exist, did not exist either. A plugin
 * therefore saw a different Downloads folder depending on whether it happened to be
 * loaded in-process or out-of-process.
 *
 * [resolve] takes every platform input as data so each rule is testable on any OS;
 * [current] reads them from the running system.
 */
object DownloadsDirectory {
    private const val DOWNLOADS = "Downloads"
    private const val XDG_KEY = "XDG_DOWNLOAD_DIR="

    /**
     * @property userDirsConfig contents of `user-dirs.dirs`, or null when absent. Linux only.
     * @property windowsKnownFolder the shell's Downloads known folder, or null when it cannot
     *   be read. Windows only; see [WindowsDownloadsFolder]. The user can move it to another
     *   drive, which is why the in-process provider admits the resolved folder as well as home.
     * @property isDirectory existence test, injected so tests need no real directories.
     */
    data class Inputs(
        val osName: String,
        val userHome: String,
        val userDirsConfig: String? = null,
        val windowsKnownFolder: String? = null,
        val isDirectory: (String) -> Boolean,
    )

    /**
     * The Downloads folder for [inputs].
     *
     * The platform's own answer wins when it names a real directory. Otherwise the answer is
     * `<home>/Downloads`, whether or not it exists yet: both write paths create missing
     * parents, so naming the conventional folder is better than naming the home folder, which
     * is what the in-process provider used to do.
     */
    fun resolve(inputs: Inputs): String {
        val conventional = File(inputs.userHome, DOWNLOADS).absolutePath

        return preferredFor(inputs)?.takeIf(inputs.isDirectory) ?: conventional
    }

    /** [resolve] for the running system. [windowsKnownFolder] is given `user.home`. */
    fun current(windowsKnownFolder: (String) -> String? = WindowsDownloadsFolder::current): String {
        val osName = System.getProperty("os.name").orEmpty()
        val userHome = System.getProperty("user.home").orEmpty()

        return resolve(
            Inputs(
                osName = osName,
                userHome = userHome,
                userDirsConfig = if (isLinux(osName)) readUserDirsConfig(userHome) else null,
                windowsKnownFolder = if (isWindows(osName)) windowsKnownFolder(userHome) else null,
                isDirectory = { File(it).isDirectory },
            ),
        )
    }

    private fun preferredFor(inputs: Inputs): String? =
        when {
            isWindows(inputs.osName) -> {
                inputs.windowsKnownFolder
                    ?.takeIf { it.isNotBlank() }
                    ?.let { File(it).absolutePath }
            }

            isLinux(inputs.osName) -> {
                xdgDownloadDir(inputs.userDirsConfig, inputs.userHome)
            }

            else -> {
                null
            }
        }

    /**
     * The `XDG_DOWNLOAD_DIR` entry of [config], or null.
     *
     * Per the xdg-user-dirs format the value is quoted and either absolute or written
     * relative to `$HOME`. The last assignment wins, matching shell semantics, and an empty
     * last value (how xdg-user-dirs records a disabled directory) means none. A hand-written
     * relative value is read against home, not the working directory. A value naming home
     * itself, `$HOME/.` and `$HOME/sub/..` included, is ignored: saving loose into home is
     * what the fallback exists to prevent.
     * Comment lines start with `#`, so they never match the key.
     */
    private fun xdgDownloadDir(
        config: String?,
        userHome: String,
    ): String? =
        config
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.startsWith(XDG_KEY) }
            ?.map { it.removePrefix(XDG_KEY).trim().removeSurrounding("\"") }
            ?.lastOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.replace("\${HOME}", userHome)
            ?.replace("\$HOME", userHome)
            ?.let { if (File(it).isAbsolute) File(it) else File(userHome, it) }
            ?.let { normalized(it) }
            ?.takeIf { it != normalized(File(userHome)) }

    /** Absolute, with `.` and `..` segments removed, without touching the filesystem. */
    private fun normalized(file: File): String =
        file
            .toPath()
            .toAbsolutePath()
            .normalize()
            .toString()

    private fun readUserDirsConfig(userHome: String): String? {
        val configHome =
            System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: File(userHome, ".config").path

        return try {
            File(configHome, "user-dirs.dirs").takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            // An unreadable config is the same as an absent one: fall back to the default.
            null
        }
    }

    private fun isWindows(osName: String): Boolean = osName.lowercase().contains("windows")

    private fun isLinux(osName: String): Boolean = osName.lowercase().contains("linux")
}
