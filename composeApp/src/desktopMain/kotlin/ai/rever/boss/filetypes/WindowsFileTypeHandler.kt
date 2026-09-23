package ai.rever.boss.filetypes

import ai.rever.boss.utils.DefaultHandlerState
import ai.rever.boss.utils.WindowsDefaultBrowserHandler
import ai.rever.boss.utils.codeSourceFile
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Windows file associations for a [FileTypeCategory].
 *
 * **What was missing.** `WindowsDefaultBrowserHandler` wrote
 * `Capabilities\FileAssociations` entries for `.htm` and `.html` pointing at the
 * ProgID `BOSS` - and nothing ever created a ProgID called `BOSS`. A
 * `FileAssociations` value naming a ProgID that does not exist cannot resolve, so
 * BOSS never appeared as a candidate for those extensions, let alone became the
 * default. This creates a real ProgID per extension, which is what makes the
 * `FileAssociations` entry mean something.
 *
 * **One process, not hundreds.** Registration is a generated `.reg` script
 * imported in a single `reg import`, and the status is a single
 * `reg query <FileExts> /s`. The per-`reg add` version ran roughly 415 processes
 * for a five-category "Set all" and 83 more just to read the status, each with its
 * own timeout, from a Settings screen and from the first-run offer at startup. See
 * [WindowsRegistryScript], which also explains the quoting hazard the script form
 * removes.
 *
 * **Setting the default is the user's, by design of the OS.** Since Windows 10,
 * writing the `UserChoice` key yourself is blocked - it carries a hash the shell
 * verifies, and a mismatch is reverted. So [register] prepares everything and
 * [openDefaultAppsSettings] takes the user to the one place that can finish it,
 * called once per user action by `DefaultAppsManager`.
 */
internal object WindowsFileTypeHandler {
    private val logger = BossLogger.forComponent("WindowsFileTypeHandler")

    private const val PROCESS_TIMEOUT_SECONDS = 30L

    private const val FILE_EXTS_KEY =
        """HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts"""

    /** The ProgID naming rule, in one place. See [WindowsRegistryScript.progIdFor]. */
    internal fun progIdFor(extension: String): String = WindowsRegistryScript.progIdFor(extension)

    /**
     * Writes the ProgIDs and capability entries for [category] in one `reg import`.
     *
     * Idempotent: `reg import` overwrites the values it names, so this is safe to
     * call on every attempt rather than tracking what was already written.
     */
    fun register(category: FileTypeCategory): Boolean {
        val appPath = applicationPath()
        if (appPath == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "Could not determine the executable path; cannot register file associations",
            )
            return false
        }

        // A category with schemes needs the `StartMenuInternet` registration as
        // well, and `web-links` is schemes only - so returning early on "no
        // extensions" meant claiming Web links from Settings > Default Apps opened
        // the Windows settings page without ever making BOSS appear in the browser
        // list it sends the user to.
        val schemesRegistered =
            if (FileTypeCategories.table.schemesFor(category.id).isEmpty()) {
                true
            } else {
                WindowsDefaultBrowserHandler.registerAsBrowserCandidate()
            }

        val extensions = FileTypeCategories.table.extensionsFor(category.id)
        if (extensions.isEmpty()) return schemesRegistered

        val script = WindowsRegistryScript.buildScript(extensions, appPath, category.displayName)

        var scriptFile: File? = null
        return try {
            // UTF-16LE with a BOM: that is what `reg import` requires of a
            // "Windows Registry Editor Version 5.00" script. An ANSI file is
            // mis-read rather than rejected loudly.
            scriptFile =
                File.createTempFile("boss-file-types", ".reg").apply {
                    writeBytes(
                        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + script.toByteArray(StandardCharsets.UTF_16LE),
                    )
                }

            val imported = runSucceeds(listOf("reg", "import", scriptFile.absolutePath))
            logger.info(
                LogCategory.SYSTEM,
                if (imported) {
                    "Registered Windows file associations"
                } else {
                    "Could not import the file-association script"
                },
                mapOf("category" to category.id, "extensions" to extensions.size),
            )
            imported
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not write the file-association script", error = e)
            false
        } finally {
            // Best effort. The script holds no secrets, but one temp file per
            // attempt left in %TEMP% is untidy.
            scriptFile?.delete()
        }
    }

    /**
     * Who owns [category] according to the shell's per-extension `UserChoice`.
     *
     * Reduced the same way macOS is: BOSS only when every extension points at a
     * BOSS ProgID. An extension with no `UserChoice` reads as
     * [DefaultHandlerState.Other] with a null id, which is honest - the shell has
     * no recorded user preference, so the association falls back to the machine
     * default.
     */
    fun statusOf(category: FileTypeCategory): DefaultHandlerState {
        val extensions = FileTypeCategories.table.extensionsFor(category.id)
        val schemes = FileTypeCategories.table.schemesFor(category.id)
        if (extensions.isEmpty() && schemes.isEmpty()) return DefaultHandlerState.Other(null)

        // One query over the whole FileExts tree, parsed once, instead of one
        // process per extension.
        val choices = if (extensions.isEmpty()) emptyMap() else userChoices()

        val extensionStates =
            extensions.map { extension ->
                val progId = choices[extension.lowercase()]
                when {
                    progId == null -> DefaultHandlerState.Other(null)
                    progId.equals(progIdFor(extension), ignoreCase = true) -> DefaultHandlerState.Ours
                    else -> DefaultHandlerState.Other(progId)
                }
            }

        // Schemes live under UrlAssociations, not FileExts, and `web-links` is
        // schemes with NO extensions - so reading only the extension side reported
        // Other on every machine, including one where BOSS held http and https.
        // macOS has always counted both (MacOSFileTypeHandler reads
        // `schemesFor` alongside the types); this is Windows catching up.
        val schemeStates = schemes.map { WindowsDefaultBrowserHandler.schemeState(it) }

        return DefaultHandlerState.reduce(extensionStates + schemeStates)
    }

    /** Opens Settings > Default apps, the only place Windows lets the default actually change. */
    fun openDefaultAppsSettings() {
        // Not through Desktop.browse: this is an ms-settings: URI, which the AWT
        // Desktop refuses as an unsupported scheme on some JDK builds.
        runSucceeds(listOf("cmd", "/c", "start", "", "ms-settings:defaultapps"))
    }

    /** Every recorded `.ext -> ProgId` the shell holds, or empty when it cannot be read. */
    private fun userChoices(): Map<String, String> =
        runCapturing(listOf("reg", "query", FILE_EXTS_KEY, "/s"))
            ?.let(WindowsRegistryScript::parseUserChoices)
            ?: emptyMap()

    /**
     * Runs a command for its exit code, discarding its output.
     *
     * `DISCARD` rather than `redirectErrorStream(true)` with nobody reading: an
     * undrained pipe deadlocks the child as soon as it fills, and the timeout
     * below cannot rescue it because the child never exits.
     */
    private fun runSucceeds(command: List<String>): Boolean =
        try {
            val process =
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(LogCategory.SYSTEM, "Registry command timed out", mapOf("command" to command.first()))
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not run a registry command", error = e)
            false
        }

    /**
     * Runs a command and returns its stdout, or null.
     *
     * The reader runs on another thread so the `waitFor` timeout can actually
     * fire. Reading to EOF first and waiting afterwards - which this did - means a
     * child that never closes stdout hangs forever and the timeout is decorative.
     * `MacOSDefaultBrowserHandler` avoids the same trap the same way.
     */
    private fun runCapturing(command: List<String>): String? =
        try {
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
            val output =
                CompletableFuture.supplyAsync {
                    process.inputStream.bufferedReader().use { it.readText() }
                }

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(LogCategory.SYSTEM, "Registry query timed out", mapOf("command" to command.first()))
                null
            } else if (process.exitValue() != 0) {
                // A missing key exits non-zero, which is the common case here and
                // not worth a warning.
                null
            } else {
                output.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "Could not query the registry",
                mapOf("reason" to (e.message ?: "unknown")),
            )
            null
        }

    /**
     * Path to the launcher a shell should run, or null when it cannot be found.
     *
     * Same resolution the browser registration uses, kept separate rather than
     * shared because that one is `private` and lives in a file about URL schemes;
     * both would be better off in one place, which is a follow-up rather than part
     * of this change.
     */
    private fun applicationPath(): String? =
        try {
            val codeSource = codeSourceFile(WindowsFileTypeHandler::class.java.protectionDomain.codeSource.location)
            when {
                codeSource.name.endsWith(".jar") -> {
                    val launcher = codeSource.parentFile?.resolve("BOSS.exe")
                    launcher?.takeIf { it.exists() }?.absolutePath
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not resolve the executable path", error = e)
            null
        }
}
