package ai.rever.boss.plugin.pathutils

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Where the Windows shell keeps the user's Downloads folder, which Explorer's Location tab lets
 * the user move, to another drive for instance. `%USERPROFILE%\Downloads` is only the default.
 *
 * The answer comes from `reg.exe` rather than `SHGetKnownFolderPath`, so it needs no native
 * binding and reads the same in the host and in plugin processes, which is what keeps the
 * in-process and out-of-process providers agreeing.
 */
object WindowsDownloadsFolder {
    /** `FOLDERID_Downloads`, the value name the shell stores the folder under. */
    private const val KNOWN_FOLDER_ID = "{374DE290-123F-4565-9164-39C4925E467B}"
    private const val USER_SHELL_FOLDERS =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders"
    private const val TIMEOUT_MILLIS = 5_000L

    private val valueRow = Regex("""^\s*(\S+)\s+(REG_SZ|REG_EXPAND_SZ)\s+(.+)$""")
    private val variable = Regex("%([^%]+)%")
    private val windowsAbsolute = Regex("""^([A-Za-z]:[\\/]|\\\\).*""")

    // Read at most once per JVM: the folder rarely moves, and a moved folder is picked up on restart.
    private val registryValue: String? by lazy {
        fromRegQuery(runRegQuery(regCommand(), TIMEOUT_MILLIS), System::getenv)
    }

    /**
     * The shell's Downloads folder, or null when it cannot be read.
     *
     * Also null off Windows, and when [userHome] is not the Windows profile folder. The
     * registry describes the profile's folders, so it says nothing about a home the JVM was
     * pointed elsewhere, as the test tasks do; in either case the registry is not queried.
     */
    fun current(userHome: String): String? {
        val onWindows =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("windows")
        val describesHome = onWindows && isProfileHome(userHome, System.getenv("USERPROFILE"))

        return if (describesHome) registryValue else null
    }

    internal fun isProfileHome(
        userHome: String,
        profile: String?,
    ): Boolean = profile != null && File(userHome).absolutePath.equals(File(profile).absolutePath, ignoreCase = true)

    /**
     * The Downloads folder named in [output] of `reg.exe query`, with `%NAME%` variables expanded
     * from [env], or null when the value is absent, of another type, or not an absolute path.
     *
     * Both string types are expanded: Explorer writes the default as `REG_EXPAND_SZ`, and a
     * literal `%NAME%` naming a set variable is not a realistic folder name. An unset variable
     * is left as written, as Windows does, which leaves a path that is not absolute.
     */
    internal fun fromRegQuery(
        output: String?,
        env: (String) -> String?,
    ): String? =
        output
            ?.lineSequence()
            ?.mapNotNull { valueRow.find(it.trimEnd()) }
            ?.firstOrNull { it.groupValues[1].equals(KNOWN_FOLDER_ID, ignoreCase = true) }
            ?.groupValues
            ?.get(3)
            ?.let { value -> variable.replace(value) { env(it.groupValues[1]) ?: it.value } }
            ?.takeIf { File(it).isAbsolute || windowsAbsolute.matches(it) }

    /**
     * Standard output of [command], or null when it cannot be started, exits non-zero (`reg.exe`
     * does when the value is missing) or runs past [timeoutMillis].
     */
    internal fun runRegQuery(
        command: List<String>,
        timeoutMillis: Long,
    ): String? =
        try {
            val process =
                ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            // The output is a few hundred bytes, well under the pipe buffer, so waiting before
            // reading cannot deadlock.
            if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                process.destroyForcibly()
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    /** The system copy of reg.exe, so a same-named program earlier on PATH is not run instead. */
    private fun regCommand(): List<String> {
        val reg =
            System
                .getenv("SystemRoot")
                ?.let { File(File(it, "System32"), "reg.exe").path }
                ?: "reg.exe"

        return listOf(reg, "query", USER_SHELL_FOLDERS, "/v", KNOWN_FOLDER_ID)
    }
}
