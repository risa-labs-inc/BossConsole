package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

actual object CLIInstaller {
    private val logger = BossLogger.forComponent("CLIInstaller")

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

    private val homeDir = System.getProperty("user.home")

    /** `WM_SETTINGCHANGE` (0x001A). */
    private const val WM_SETTINGCHANGE = 0x001A

    /** `SMTO_ABORTIFHUNG` (0x0002). */
    private const val SMTO_ABORTIFHUNG = 0x0002

    /**
     * Check if BOSS is installed in /Applications with CLI script in Resources
     */
    private fun isHomebrewStyleInstallation(): Boolean {
        if (!isMacOS) return false
        val appPath = File("/Applications/BOSS.app")
        val cliScriptPath = File("/Applications/BOSS.app/Contents/Resources/boss")
        return appPath.exists() && appPath.isDirectory && cliScriptPath.exists()
    }

    /**
     * Get the CLI script path in app bundle Resources
     */
    private fun getHomebrewCLISourcePath(): String = "/Applications/BOSS.app/Contents/Resources/boss"

    /**
     * Get the Homebrew bin path for symlink
     */
    private fun getHomebrewBinPath(): String = "/opt/homebrew/bin/boss"

    /**
     * Get the installation path for CLI script
     */
    private fun getInstallPath(): String {
        // Check Homebrew-style installation first (macOS only)
        if (isMacOS) {
            val homebrewBin = File(getHomebrewBinPath())
            if (homebrewBin.exists()) {
                return homebrewBin.absolutePath
            }
        }

        // Fall back to legacy installation path
        return if (isWindows) {
            "$homeDir\\bin\\boss.bat"
        } else {
            "$homeDir/.local/bin/boss"
        }
    }

    actual suspend fun installCLI(): CLIInstallResult =
        withContext(Dispatchers.IO) {
            try {
                if (isWindows) {
                    installWindows()
                } else {
                    installUnix()
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "CLI installation error", error = e)
                CLIInstallResult(
                    success = false,
                    message = "Installation failed: ${e.message ?: "Unknown error"}",
                    requiresRestart = false,
                )
            }
        }

    actual fun isInstalled(): Boolean {
        // Check Homebrew-style installation (macOS only)
        if (isMacOS) {
            val homebrewBin = File(getHomebrewBinPath())
            if (homebrewBin.exists()) {
                return true
            }
        }

        // Check legacy installation
        return File(if (isWindows) "$homeDir\\bin\\boss.bat" else "$homeDir/.local/bin/boss").exists()
    }

    /**
     * Install CLI on Windows
     */
    private fun installWindows(): CLIInstallResult {
        // Create bin directory
        val binDir = File("$homeDir\\bin")
        binDir.mkdirs()

        // Read script from resources
        val scriptContent =
            readResourceScript("boss.bat")
                ?: return CLIInstallResult(
                    success = false,
                    message = "Failed to read boss.bat from application resources",
                )

        // Write script to destination
        val installPath = File(getInstallPath())
        installPath.writeText(scriptContent)

        // Update PATH environment variable
        val pathUpdated = updateWindowsPath(binDir.absolutePath)

        val message =
            if (pathUpdated) {
                "Successfully installed to: ${installPath.absolutePath}\n\n" +
                    "PATH has been updated. Restart your terminal to use:\n  boss --help"
            } else {
                "Successfully installed to: ${installPath.absolutePath}\n\n" +
                    "Please add the following to your PATH manually:\n  ${binDir.absolutePath}\n\n" +
                    "Then restart your terminal to use:\n  boss --help"
            }

        return CLIInstallResult(
            success = true,
            installPath = installPath.absolutePath,
            shellConfigPath = if (pathUpdated) "User PATH" else null,
            message = message,
            requiresRestart = true,
        )
    }

    /**
     * Install CLI on Unix (macOS/Linux)
     */
    private fun installUnix(): CLIInstallResult {
        // Check if we can use Homebrew-style installation (macOS only)
        if (isMacOS && isHomebrewStyleInstallation()) {
            return installHomebrewStyle()
        }

        // Fall back to legacy installation
        return installLegacyUnix()
    }

    /**
     * Install CLI using Homebrew-style symlink (macOS only)
     */
    private fun installHomebrewStyle(): CLIInstallResult {
        try {
            val sourcePath = File(getHomebrewCLISourcePath())
            val targetPath = File(getHomebrewBinPath())

            // Ensure /opt/homebrew/bin directory exists
            targetPath.parentFile?.mkdirs()

            // Remove existing symlink/file if present
            if (targetPath.exists()) {
                if (Files.isSymbolicLink(targetPath.toPath())) {
                    // Check if it points to the correct location
                    val existingTarget = Files.readSymbolicLink(targetPath.toPath())
                    if (existingTarget.toString() == sourcePath.absolutePath) {
                        // Symlink already correct
                        return CLIInstallResult(
                            success = true,
                            installPath = targetPath.absolutePath,
                            shellConfigPath = null,
                            message =
                                "CLI already installed at: ${targetPath.absolutePath}\n\n" +
                                    "Symlinked to: ${sourcePath.absolutePath}\n\n" +
                                    "Use: boss --help",
                            requiresRestart = false,
                        )
                    }
                }
                // Remove existing file/symlink
                targetPath.delete()
            }

            // Create symlink
            Files.createSymbolicLink(targetPath.toPath(), sourcePath.toPath())

            return CLIInstallResult(
                success = true,
                installPath = targetPath.absolutePath,
                shellConfigPath = null,
                message =
                    "Successfully installed CLI at: ${targetPath.absolutePath}\n\n" +
                        "Symlinked to: ${sourcePath.absolutePath}\n\n" +
                        "/opt/homebrew/bin is already in your PATH.\n\n" +
                        "Use immediately: boss --help",
                requiresRestart = false,
            )
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Homebrew-style installation failed, falling back to legacy", error = e)
            // Fall back to legacy installation
            return installLegacyUnix()
        }
    }

    /**
     * Install CLI using legacy method (copy to ~/.local/bin)
     */
    private fun installLegacyUnix(): CLIInstallResult {
        // Create .local/bin directory
        val binDir = File("$homeDir/.local/bin")
        binDir.mkdirs()

        // Read script from resources
        val scriptContent =
            readResourceScript("boss")
                ?: return CLIInstallResult(
                    success = false,
                    message = "Failed to read boss script from application resources",
                )

        // Write script to destination
        val installPath = File("$homeDir/.local/bin/boss")
        installPath.writeText(scriptContent)

        // Make executable
        makeExecutable(installPath)

        // Update shell configuration
        val shellConfigResult = updateShellConfig()

        val message =
            if (shellConfigResult.success) {
                "Successfully installed to: ${installPath.absolutePath}\n\n" +
                    "Updated: ${shellConfigResult.configPath}\n\n" +
                    "Restart your terminal or run:\n  source ${shellConfigResult.configPath}\n\n" +
                    "Then use:\n  boss --help"
            } else {
                "Successfully installed to: ${installPath.absolutePath}\n\n" +
                    "Please add the following to your shell configuration:\n  export PATH=\"\$HOME/.local/bin:\$PATH\"\n\n" +
                    "Then restart your terminal to use:\n  boss --help"
            }

        return CLIInstallResult(
            success = true,
            installPath = installPath.absolutePath,
            shellConfigPath = shellConfigResult.configPath,
            message = message,
            requiresRestart = true,
        )
    }

    /**
     * Read script content from bundled resources
     */
    private fun readResourceScript(scriptName: String): String? =
        try {
            val resourcePath = "/cli/$scriptName"
            val stream = CLIInstaller::class.java.getResourceAsStream(resourcePath)
            stream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to read resource", mapOf("scriptName" to scriptName), error = e)
            null
        }

    /**
     * Make file executable on Unix systems
     */
    private fun makeExecutable(file: File) {
        try {
            val path = file.toPath()
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms.add(PosixFilePermission.OWNER_READ)
            perms.add(PosixFilePermission.OWNER_WRITE)
            perms.add(PosixFilePermission.OWNER_EXECUTE)
            perms.add(PosixFilePermission.GROUP_READ)
            perms.add(PosixFilePermission.GROUP_EXECUTE)
            perms.add(PosixFilePermission.OTHERS_READ)
            perms.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(path, perms)
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to make file executable", error = e)
            // Fallback to chmod command
            try {
                ProcessBuilder("chmod", "+x", file.absolutePath).start().waitFor()
            } catch (e2: Exception) {
                logger.warn(LogCategory.SYSTEM, "chmod fallback also failed", error = e2)
            }
        }
    }

    /**
     * Update shell configuration to add PATH
     */
    private fun updateShellConfig(): ShellConfigResult {
        // Detect shell configuration files
        val shellConfigs =
            listOf(
                "$homeDir/.zshrc" to "zsh",
                "$homeDir/.bashrc" to "bash",
                "$homeDir/.bash_profile" to "bash",
                "$homeDir/.config/fish/config.fish" to "fish",
            )

        val pathExport = "export PATH=\"\$HOME/.local/bin:\$PATH\""
        val fishPathExport = "set -gx PATH \$HOME/.local/bin \$PATH"

        // Find first existing config file
        for ((configPath, shell) in shellConfigs) {
            val configFile = File(configPath)
            if (!configFile.exists()) continue

            try {
                // Read current content
                val content = configFile.readText()

                // Check if PATH is already configured
                val exportLine = if (shell == "fish") fishPathExport else pathExport
                if (content.contains(".local/bin") && content.contains("PATH")) {
                    return ShellConfigResult(
                        success = true,
                        configPath = configPath,
                        alreadyConfigured = true,
                    )
                }

                // Append PATH export
                val updatedContent =
                    content.trimEnd() + "\n\n" +
                        "# Added by BOSS CLI installer\n" +
                        exportLine + "\n"

                configFile.writeText(updatedContent)

                return ShellConfigResult(
                    success = true,
                    configPath = configPath,
                    alreadyConfigured = false,
                )
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Failed to update shell config", mapOf("configPath" to configPath), error = e)
                continue
            }
        }

        // No config file found or all failed
        return ShellConfigResult(
            success = false,
            configPath = null,
            alreadyConfigured = false,
        )
    }

    /**
     * Merge the bin directory into the user-scope PATH (#1058): appended once,
     * never duplicated, and never mixing system-scope entries into user scope.
     *
     * Behaviour, in order:
     *  1. A blank [binPath] returns [currentUserScopePath] unchanged.
     *  2. If [binPath] is already present (case-insensitive match against any
     *     trimmed `;`-separated entry), the input is returned unchanged so a
     *     re-run is a no-op rather than a duplication.
     *  3. Otherwise the entry is appended. A trailing semicolon in
     *     [currentUserScopePath] is preserved as-is; otherwise `;binDir;` is
     *     appended so the merged string stays well-formed `REG_EXPAND_SZ`.
     *
     * **Byte-faithful on every entry the user already had.** Only [binPath]
     * is normalized - trimmed of leading/trailing whitespace and a trailing
     * backslash - because Windows compares paths ignoring both. The existing
     * entries are passed through verbatim, including any trailing
     * backslashes, drive letters with or without a trailing `\`, and any
     * internal whitespace the user actually wrote. The duplicate check
     * normalizes a temporary copy of each entry for comparison - the
     * comparison is purely a read, it never reaches the output. The PATH
     * editor touches only what the install asked for.
     *
     * Pure on purpose so this is the only function the unit tests need to
     * cover - the real PowerShell calls are exercised by hand or by an
     * integration test, not here.
     */
    @Suppress("ReturnCount")
    internal fun mergeUserPath(
        currentUserScopePath: String,
        binPath: String,
    ): String {
        if (binPath.isBlank()) return currentUserScopePath
        val normalizedBin = normalizePathEntry(binPath)
        val trimmedCurrent = currentUserScopePath.trim()
        val existingEntries = trimmedCurrent.split(';').map(::normalizePathEntry).filter { it.isNotEmpty() }
        if (existingEntries.any { it.equals(normalizedBin, ignoreCase = true) }) {
            return currentUserScopePath
        }
        // Preserve any trailing semicolon in the input - if the user already
        // terminated their PATH with one, adding binPath directly is correct.
        // If they did not, insert a separator so the merged value stays
        // well-formed REG_EXPAND_SZ.
        val separator = if (currentUserScopePath.isEmpty() || currentUserScopePath.endsWith(';')) "" else ";"
        return currentUserScopePath + separator + normalizedBin + ";"
    }

    private fun normalizePathEntry(entry: String): String = entry.trim().trimEnd('\\')

    /**
     * Read the USER-scope Path (not the merged system+user view) so we never
     * copy system entries into user scope, and never write a value setx would
     * silently truncate at 1024 characters.
     *
     * Returns `""` for both "value absent" (a fresh user profile has no
     * HKCU\Environment\Path at all) and "value present-but-empty" (a real PATH
     * the user has cleared). The caller appends to both. The previous
     * implementation returned null for absent, which the caller treated as a
     * hard failure - so a fresh user profile silently got no BOSS entry added.
     *
     * Stdout and stderr are drained on background threads concurrently with
     * `waitFor` - a long PATH that overflows the OS pipe buffer before the
     * process closes its stdout would otherwise deadlock. Two drains because
     * `redirectErrorStream(true)` would lose the diagnostic that PowerShell
     * surfaces on a non-zero exit.
     *
     * Uses `[Environment]::GetEnvironmentVariable('Path', 'User')` rather than
     * `reg query` so the raw REG_EXPAND_SZ bytes come back without
     * `DoNotExpandEnvironmentNames` workaround - .NET returns the raw
     * unexpanded string by design, so portable entries like
     * `%USERPROFILE%\bin` stay portable.
     */
    private fun readUserScopePath(): String =
        runCatching {
            val process =
                ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "[Environment]::GetEnvironmentVariable('Path', 'User')",
                ).start()

            // Concurrent drain - FutureTask gives us a typed result and the same
            // pattern BoundedCommand uses for plugin metrics. Each thread owns
            // its stream end and the wait can complete as soon as the process exits.
            val stdoutDrain =
                java.util.concurrent.FutureTask {
                    process.inputStream.bufferedReader().use { it.readText() }
                }
            val stderrDrain =
                java.util.concurrent.FutureTask {
                    process.errorStream.bufferedReader().use { it.readText() }
                }
            Thread(stdoutDrain, "powershell-userpath-stdout").apply { isDaemon = true }.start()
            Thread(stderrDrain, "powershell-userpath-stderr").apply { isDaemon = true }.start()

            process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            val stdout = stdoutDrain.get()
            val stderr = stderrDrain.get()

            if (process.exitValue() != 0 && stderr.isNotBlank()) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "powershell GetEnvironmentVariable exited non-zero",
                    mapOf("exit" to process.exitValue(), "stderr" to stderr.trim()),
                )
            }

            // Coalesce null/absent and present-but-empty both to "" so the
            // caller's merge produces an initial PATH for a fresh profile AND
            // appends to a cleared user PATH. The previous null return turned
            // both into a silent skip with no error reported.
            stdout.trim()
        }.getOrDefault("")

    /**
     * Write the user-scope Path via .NET (no 1024-char setx truncation, keeps
     * REG_EXPAND_SZ semantics). Returns false on failure - never a silent
     * partial write reported as success.
     *
     * `setx` had two bugs this replaces:
     *  1. Silent truncation at 1024 characters, which drops the BOSS bin entry
     *     on long-PATH machines.
     *  2. Re-writing the value as `REG_SZ`, which freezes
     *     `%USERPROFILE%\bin`-style references as literal strings - portable
     *     paths stop expanding on the next logon. .NET
     *     `[Environment]::SetEnvironmentVariable` writes the value as
     *     `REG_EXPAND_SZ` when the existing value is `REG_EXPAND_SZ`, and as
     *     `REG_SZ` when the existing value is `REG_SZ` - same semantics as
     *     `reg add /t REG_EXPAND_SZ` without the spawning overhead.
     *
     * The merged value is passed in through an env var rather than the command
     * line because long PATHs exceed the Win32 command-line limit. PowerShell
     * reads it back via `[Environment]::GetEnvironmentVariable` and hands the
     * string verbatim to `SetEnvironmentVariable`, so no quoting or escaping
     * round-trip can drop a backslash or semicolon.
     *
     * Stdout and stderr are drained on background threads concurrently with
     * `waitFor`, matching the read path. A truncated write to a long PATH
     * could otherwise hang the install the same way a long read could.
     */
    private fun writeUserScopePath(merged: String): Boolean =
        runCatching {
            val process =
                ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "[Environment]::SetEnvironmentVariable(" +
                        "'Path', [Environment]::GetEnvironmentVariable('BOSS_MERGED_USER_PATH'), 'User')",
                ).apply { environment()["BOSS_MERGED_USER_PATH"] = merged }.start()

            val stdoutDrain =
                java.util.concurrent.FutureTask {
                    process.inputStream.bufferedReader().use { it.readText() }
                }
            val stderrDrain =
                java.util.concurrent.FutureTask {
                    process.errorStream.bufferedReader().use { it.readText() }
                }
            Thread(stdoutDrain, "powershell-setpath-stdout").apply { isDaemon = true }.start()
            Thread(stderrDrain, "powershell-setpath-stderr").apply { isDaemon = true }.start()

            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching false
            }
            val stderr = stderrDrain.get()
            if (process.exitValue() != 0 && stderr.isNotBlank()) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "powershell SetEnvironmentVariable exited non-zero",
                    mapOf("exit" to process.exitValue(), "stderr" to stderr.trim()),
                )
            }
            process.exitValue() == 0
        }.getOrDefault(false)

    /**
     * Update the user-scope PATH environment variable (#1058): the old
     * `setx PATH "%bin%;%PATH%"` wrote the combined system+user PATH into
     * user scope and silently truncated at 1024 chars (exit code 0).
     *
     * `readUserScopePath` coalesces both "absent" (fresh user profile) and
     * "present-but-empty" to `""`, so this function creates the initial value
     * for the former and appends for the latter. A short-circuit returns true
     * when the bin directory is already present, so a re-run is a no-op rather
     * than a needless registry write.
     *
     * On success the new value is broadcast to all top-level windows via
     * `WM_SETTINGCHANGE` so running apps pick up the new PATH without waiting
     * for logoff. .NET's `SetEnvironmentVariable` does broadcast this on
     * modern Windows, but the broadcast is a no-op for a process whose own
     * environment block is already cached - the explicit broadcast ensures
     * shells and IDEs already running actually see the change.
     */
    private fun updateWindowsPath(binPath: String): Boolean =
        try {
            val userScopePath = readUserScopePath()
            val merged = mergeUserPath(userScopePath, binPath)
            val alreadyPresent = merged == userScopePath.trim()
            val written = alreadyPresent || writeUserScopePath(merged)
            if (written && !alreadyPresent) {
                broadcastEnvironmentChange()
            }
            written
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to update Windows PATH", error = e)
            false
        }

    /**
     * Broadcast `WM_SETTINGCHANGE` to all top-level windows so running apps
     * pick up the new user PATH without waiting for logoff. Sends the message
     * via `SendMessageTimeoutW` with `SMTO_ABORTIFHUNG` and a 5s timeout,
     * matching what `Microsoft.VisualBasic.Interaction` shells out to.
     *
     * Bound through JNA rather than `rundll32 user32.dll,...` because the
     * user32 path needs the literal string `"Environment"` as the `lParam` (a
     * pointer into the broadcast message's read-only memory), and `rundll32`
     * would have to be told that string from another channel. JNA is already
     * on the composeApp classpath for the macOS Launch Services binding.
     */
    // JNA's User32 surface can raise RuntimeException for any native failure; the broadcast is best-effort.
    @Suppress("TooGenericExceptionCaught")
    private fun broadcastEnvironmentChange() {
        try {
            val user32 = User32.INSTANCE
            // WM_SETTINGCHANGE's lParam is a pointer to a wide-char section name
            // ("Environment"). The string only needs to live for the duration of
            // the SendMessageTimeoutW call, so a JNA Memory block is enough; it
            // is freed when the broadcast returns or the GC clears it.
            val sectionName = "Environment"
            val mem =
                Memory(
                    (sectionName.length + 1L) * Native.WCHAR_SIZE,
                )
            mem.setString(0, sectionName)
            user32.SendMessageTimeout(
                WinDef.HWND(Pointer.createConstant(0xFFFFL)),
                WM_SETTINGCHANGE,
                WinDef.WPARAM(0L),
                WinDef.LPARAM(Pointer.nativeValue(mem)),
                SMTO_ABORTIFHUNG,
                5000,
                null,
            )
        } catch (t: Exception) {
            // The broadcast is best-effort - the registry value is already
            // updated and will be picked up at the next logon or process spawn.
            logger.warn(LogCategory.SYSTEM, "WM_SETTINGCHANGE broadcast failed", error = t)
        }
    }

    private data class ShellConfigResult(
        val success: Boolean,
        val configPath: String?,
        val alreadyConfigured: Boolean,
    )
}
