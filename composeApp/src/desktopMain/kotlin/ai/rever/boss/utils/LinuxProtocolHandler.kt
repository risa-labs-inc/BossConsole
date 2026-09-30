package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Registers BOSS as the `boss://` handler on Linux, at startup, the way [WindowsProtocolHandler]
 * does on Windows.
 *
 * Before this, `boss://` was registered on Linux only as a side effect of making BOSS the
 * default browser or a default app (`LinuxDefaultBrowserHandler`), so on a fresh install a
 * Google or Apple sign-in, and the magic link, had no way back into the app.
 *
 * It writes its own hidden entry, [DESKTOP_FILE_NAME], rather than the `boss.desktop` the
 * default-browser flow owns: that one lists `http`, `https` and every file type under
 * `Categories=WebBrowser`, and writing it at every launch would put BOSS forward as a browser
 * candidate nobody asked for. The association is claimed only when nothing else holds it, so a
 * `boss.desktop` the user set up, or a packaged entry, is left alone.
 *
 * Skipped off Linux, and under a bare `java` launcher (a Gradle run), where the command line
 * would not start BOSS again.
 */
internal object LinuxProtocolHandler {
    private val logger = BossLogger.forComponent("LinuxProtocolHandler")

    const val DESKTOP_FILE_NAME = "boss-url-handler.desktop"
    private const val SCHEME_MIME = "x-scheme-handler/boss"
    private const val COMMAND_TIMEOUT_SECONDS = 5L

    /** Reserved in an `Exec` argument; any one of them forces quoting. */
    private const val RESERVED = " \t'><~|&;*?#()"

    /**
     * Would need escaping inside the quotes on top of the string-level escaping, or cannot be
     * written on one line at all; refused instead.
     */
    private val UNQUOTABLE = setOf('"', '`', '$', '\\', '\n', '\r')

    /** What one [register] call did, for the log and for tests. */
    internal enum class Outcome {
        NOT_LINUX,
        NO_LAUNCHER,
        UNUSABLE_PATH,
        HELD_ELSEWHERE,
        UP_TO_DATE,
        REGISTERED,
    }

    /** Idempotent; safe to call on every launch. Never throws. */
    // Startup must not throw, whatever the file system or the xdg tools do.
    @Suppress("TooGenericExceptionCaught")
    fun ensureRegistered() {
        try {
            val outcome =
                register(
                    osName = System.getProperty("os.name").orEmpty(),
                    launcher = currentLauncher(),
                    applicationsDir = File(System.getProperty("user.home"), ".local/share/applications"),
                    run = ::run,
                )
            logger.debug(LogCategory.SYSTEM, "boss:// handler check", mapOf("outcome" to outcome.name))
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not register boss:// handler", error = e)
        }
    }

    /**
     * Claim `boss://` for [launcher] in [applicationsDir] when nothing else holds it. [run] is a
     * command's output when it exited 0, else null. Separated from [ensureRegistered] so the
     * decisions can be tested without touching the machine's own associations.
     */
    // Each guard is a reason to leave the scheme alone.
    @Suppress("ReturnCount")
    internal fun register(
        osName: String,
        launcher: String?,
        applicationsDir: File,
        run: (List<String>) -> String?,
    ): Outcome {
        // xdg-mime exists on the BSDs too, but nothing there was ever tested or packaged.
        if (!osName.lowercase().contains("linux")) return Outcome.NOT_LINUX
        if (launcher == null) return Outcome.NO_LAUNCHER
        val content = desktopEntryFor(launcher)
        if (content == null) {
            logger.warn(LogCategory.SYSTEM, "Not registering boss:// - the launcher path cannot go in a desktop entry")
            return Outcome.UNUSABLE_PATH
        }
        val holder = run(listOf("xdg-mime", "query", "default", SCHEME_MIME))?.trim().orEmpty()
        if (holder.isNotEmpty() && holder != DESKTOP_FILE_NAME) {
            logger.debug(LogCategory.SYSTEM, "boss:// already has a handler", mapOf("handler" to holder))
            return Outcome.HELD_ELSEWHERE
        }
        val file = File(applicationsDir, DESKTOP_FILE_NAME)
        if (holder == DESKTOP_FILE_NAME && file.isFile && file.readText() == content) return Outcome.UP_TO_DATE
        applicationsDir.mkdirs()
        file.writeText(content)
        run(listOf("update-desktop-database", applicationsDir.absolutePath))
        val claimed = run(listOf("xdg-mime", "default", DESKTOP_FILE_NAME, SCHEME_MIME)) != null
        logger.info(LogCategory.SYSTEM, "Registered boss:// handler", mapOf("claimed" to claimed))
        return Outcome.REGISTERED
    }

    /** The packaged launcher running this process, or null for a plain `java` launch. */
    private fun currentLauncher(): String? {
        val command =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null) ?: return null
        val name = File(command).name
        return command.takeUnless { name == "java" || name == "javaw" }
    }

    /**
     * The hidden desktop entry that hands `boss://` links to [launcher], or null when the path
     * carries a character the entry would have to escape twice over.
     */
    internal fun desktopEntryFor(launcher: String): String? {
        if (launcher.any { it in UNQUOTABLE }) return null
        return listOf(
            "[Desktop Entry]",
            "Version=1.0",
            "Type=Application",
            "Name=BOSS Console",
            "Comment=Opens boss:// links in BOSS",
            "Exec=${execQuoted(launcher)} %u",
            "Terminal=false",
            "NoDisplay=true",
            "MimeType=$SCHEME_MIME;",
        ).joinToString("\n", postfix = "\n")
    }

    /**
     * [path] as one `Exec` argument: `%` doubled, since a lone one starts a field code even inside
     * quotes, and double-quoted when it holds a reserved character.
     */
    internal fun execQuoted(path: String): String {
        val literal = path.replace("%", "%%")
        return if (literal.any { it in RESERVED }) "\"$literal\"" else literal
    }

    /** [command]'s output when it exits 0 in time, else null. */
    private fun run(command: List<String>): String? =
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            // Waited on before reading: these commands print a line at most, well under a pipe
            // buffer, and reading first would block past the timeout on a wedged command.
            if (process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                process.destroyForcibly()
                null
            }
        } catch (e: IOException) {
            logger.debug(
                LogCategory.SYSTEM,
                "Command unavailable",
                mapOf("command" to command.first(), "reason" to (e.message ?: "")),
            )
            null
        }
}
