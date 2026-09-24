package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
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
    @Suppress("LongMethod", "ReturnCount")
    internal fun updateShellConfig(homeDir: String = this.homeDir): ShellConfigResult {
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

        // Last symlinked candidate we refused, for the post-loop "every existing
        // candidate was a symlink" case. Without it, returning nothing would lose
        // the reason the install refused, and the existing test pins that contract.
        var lastSymlinkPath: String? = null

        // Find first existing config file
        for ((configPath, shell) in shellConfigs) {
            val configFile = File(configPath)
            if (!configFile.exists()) continue

            try {
                // Refuse to write through a symlink. `File.writeText` and `File.readText`
                // both follow symlinks, so a `~/.zshrc` pointing at, say, a notes file
                // would have that notes file overwritten with a shell config on the next
                // CLI install. The check is on the candidate, not the resolved target,
                // because the resolved target is exactly what we are refusing to touch.
                //
                // `continue`, not `return`: a symlinked `.zshrc` (common with dotfile
                // managers that symlink the rc into a repo) must not prevent the
                // remaining candidates - a perfectly writable plain `~/.bashrc` - from
                // being considered. The post-loop fallback reports the symlink so the
                // refusal is observable end-to-end.
                if (Files.isSymbolicLink(configFile.toPath())) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Refusing to update a symlinked shell config; add the PATH export by hand",
                        mapOf("configPath" to configPath, "target" to configFile.toPath().toAbsolutePath()),
                    )
                    lastSymlinkPath = configPath
                    continue
                }

                // Read current content
                val content = configFile.readText()

                // Check if PATH is already configured
                val exportLine = if (shell == "fish") fishPathExport else pathExport
                if (content.contains(".local/bin") && content.contains("PATH")) {
                    return ShellConfigResult(
                        success = true,
                        configPath = configPath,
                        alreadyConfigured = true,
                        skippedReason = null,
                    )
                }

                // Append PATH export through atomic write so a crash mid-write leaves
                // the previous content intact rather than a truncated shell rc.
                val updatedContent =
                    content.trimEnd() + "\n\n" +
                        "# Added by BOSS CLI installer\n" +
                        exportLine + "\n"

                configFile.atomicWriteText(updatedContent)

                return ShellConfigResult(
                    success = true,
                    configPath = configPath,
                    alreadyConfigured = false,
                    skippedReason = null,
                )
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to update shell config",
                    mapOf("configPath" to configPath),
                    error = e,
                )
                continue
            }
        }

        // No candidate was written. If every existing candidate was a symlink we
        // refused, surface that rather than a generic "nothing to write", so the
        // caller and the test can distinguish "no rc on disk" from "all candidates
        // were symlinked". Otherwise nothing existed and skippedReason stays null.
        return if (lastSymlinkPath != null) {
            ShellConfigResult(
                success = false,
                configPath = lastSymlinkPath,
                alreadyConfigured = false,
                skippedReason = "symlink",
            )
        } else {
            ShellConfigResult(
                success = false,
                configPath = null,
                alreadyConfigured = false,
                skippedReason = null,
            )
        }
    }

    /**
     * Update Windows PATH environment variable
     */
    private fun updateWindowsPath(binPath: String): Boolean {
        return try {
            // Use setx command to update user PATH
            val currentPath = System.getenv("PATH") ?: ""

            // Check if already in PATH
            if (currentPath.contains(binPath)) {
                return true
            }

            // Use setx to add to PATH
            val process =
                ProcessBuilder(
                    "cmd",
                    "/c",
                    "setx",
                    "PATH",
                    "$binPath;%PATH%",
                ).start()

            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to update Windows PATH", error = e)
            false
        }
    }

    internal data class ShellConfigResult(
        val success: Boolean,
        val configPath: String?,
        val alreadyConfigured: Boolean,
        /**
         * Why a candidate rc was skipped rather than written to. `null` means
         * "wrote" or "already configured"; `"symlink"` means the candidate was a
         * symlink and we refused to follow it. Surfaced for tests so the symlink
         * refusal is observable end-to-end.
         */
        val skippedReason: String? = null,
    )

    /**
     * Test seam: drives [updateShellConfig] against a chosen home directory
     * without touching the developer's real `~/.bashrc`. The class-level
     * [homeDir] field is captured at object init from `System.getProperty`, and
     * the `desktopTest` redirect of `user.home` runs after that init, which is
     * why an explicit parameter is needed for tests that want a different home.
     */
    @Suppress("unused", "ktlint:standard:max-line-length", "MaxLineLength")
    internal fun testUpdateShellConfigForHome(homeDir: File): ShellConfigResult = updateShellConfig(homeDir.absolutePath)
}
