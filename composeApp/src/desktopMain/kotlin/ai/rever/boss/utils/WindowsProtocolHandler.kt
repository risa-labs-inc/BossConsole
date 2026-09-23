package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

private val logger = BossLogger.forComponent("WindowsProtocolHandler")
private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

/** Root of the per-user protocol registration this file owns. */
private const val PROTOCOL_KEY = """HKEY_CURRENT_USER\Software\Classes\boss"""

/** The `shell\open\command` value under it — where the launch command lives. */
private const val PROTOCOL_COMMAND_KEY = PROTOCOL_KEY + """\shell\open\command"""

/** A wedged `reg.exe` must not hang the uninstall hook. */
private const val REG_TIMEOUT_SECONDS = 5L

/**
 * Windows-specific protocol handler for registering URL schemes.
 *
 * Registration happens at runtime rather than in the MSI (see
 * [docs/WINDOWS_DEEP_LINK_SETUP.md]), which means an uninstall leaves the `boss:`
 * handler in the registry pointing at a deleted executable. [unregisterProtocol] is the
 * cleanup hook for that — reachable as `BOSS.exe --unregister-protocol` (see
 * [unregisterProtocolExitCode]) so an uninstall action, or support instructions, can
 * call it.
 */
object WindowsProtocolHandler {
    /**
     * Outcome of [unregisterProtocol]. Distinguishes "there is nothing left to clean"
     * from "I deliberately did not touch it" — deleting a registration we merely could
     * not parse would break a working install.
     */
    enum class UnregisterOutcome {
        /** The registration was ours, pointed at a deleted exe, or had no command value — and was removed. */
        REMOVED,

        /** Nothing was registered. */
        ABSENT,

        /** Not a Windows host — there is no registry registration to remove. */
        NOT_APPLICABLE,

        /** Registered to a different, still present BOSS installation. Left alone. */
        OTHER_INSTALL,

        /** Registered to a command this code cannot parse (e.g. installer-authored, unquoted). Left alone. */
        UNREADABLE,

        /** `reg delete` itself failed. */
        FAILED,
    }

    /**
     * Register the boss:// protocol in Windows Registry
     * This should be called on first launch or during installation
     *
     * Production-safe: Only registers if needed, validates existing registrations,
     * and prevents conflicts with other BOSS installations
     */
    fun registerProtocol() {
        if (!isWindows) return

        try {
            // 1. Get application path
            val appPath = getApplicationPath()
            if (appPath.isNullOrEmpty()) {
                // Development mode or unable to determine path
                return
            }

            // 2. Check current registry state
            val currentCommand = getCurrentRegistryCommand()

            // 3. Determine if registration is needed
            val needsRegistration =
                when {
                    currentCommand == null -> {
                        logger.info(LogCategory.SYSTEM, "Protocol not registered. Registering...")
                        true
                    }

                    !commandPointsToValidExecutable(currentCommand) -> {
                        logger.info(
                            LogCategory.SYSTEM,
                            "Protocol points to invalid path, re-registering",
                            mapOf("command" to WindowsProtocolCleanup.maskUserPath(currentCommand)),
                        )
                        true
                    }

                    !currentCommand.contains(appPath, ignoreCase = true) -> {
                        // SAFETY CHECK: Only re-register if current path doesn't exist
                        val currentExePath = WindowsProtocolCleanup.extractExecutablePath(currentCommand)
                        if (currentExePath != null && File(currentExePath).exists()) {
                            logger.info(
                                LogCategory.SYSTEM,
                                "Protocol already registered to different valid BOSS installation, skipping",
                                mapOf("path" to WindowsProtocolCleanup.maskUserPath(currentExePath)),
                            )
                            false
                        } else {
                            logger.info(
                                LogCategory.SYSTEM,
                                "Protocol points to non-existent path, re-registering",
                                mapOf("command" to WindowsProtocolCleanup.maskUserPath(currentCommand)),
                            )
                            true
                        }
                    }

                    else -> {
                        logger.debug(LogCategory.SYSTEM, "Protocol already correctly registered")
                        false
                    }
                }

            // 4. Perform registration if needed
            if (needsRegistration) {
                performRegistration(appPath)
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to register Windows protocol", error = e)
        }
    }

    /**
     * Perform the actual registry writes.
     *
     * FOLLOW-UP: these still go through `Runtime.getRuntime().exec(String)`, which tokenizes on
     * whitespace — so a `/d` value containing spaces does not survive, and the *write* path can
     * produce exactly the malformed registrations the *read* path now goes out of its way not to
     * touch. Converting to an argv vector changes how that value is quoted, i.e. the
     * registration itself, so it needs a Windows box to verify. It is the last `exec(String)`
     * left in this file now that the queries share [queryRootKeyPresent].
     */
    private fun performRegistration(appPath: String) {
        logger.info(
            LogCategory.SYSTEM,
            "Starting BOSS protocol registration",
            mapOf("appPath" to WindowsProtocolCleanup.maskUserPath(appPath)),
        )

        val commands =
            listOf(
                // Create protocol key
                """reg add "$PROTOCOL_KEY" /ve /d "URL:BOSS Protocol" /f""",
                """reg add "$PROTOCOL_KEY" /v "URL Protocol" /d "" /f""",
                // Set icon
                """reg add "$PROTOCOL_KEY\DefaultIcon" /ve /d "$appPath,0" /f""",
                // Set command to open the app with URL
                """reg add "$PROTOCOL_COMMAND_KEY" /ve /d "\"$appPath\" \"%1\"" /f""",
            )

        var successCount = 0
        commands.forEach { command ->
            try {
                val process = Runtime.getRuntime().exec(command)
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    successCount++
                } else {
                    logger.warn(LogCategory.SYSTEM, "Registry command failed", mapOf("exitCode" to exitCode))
                }
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to execute registry command", error = e)
            }
        }

        if (successCount == commands.size) {
            logger.info(LogCategory.SYSTEM, "Protocol registration successful")
        } else {
            logger.warn(
                LogCategory.SYSTEM,
                "Protocol registration partial",
                mapOf(
                    "successCount" to successCount,
                    "totalCommands" to commands.size,
                ),
            )
        }
    }

    /**
     * Remove the `boss:` protocol registration.
     *
     * Because registration happens at runtime, an uninstall would otherwise leave a
     * handler pointing at a deleted `BOSS.exe`, and later `boss://` links fail with
     * Windows' generic "no app associated" error. Invoke via
     * `BOSS.exe --unregister-protocol` from an uninstall action or by hand.
     *
     * Safety policy lives in [classifyProtocolCleanup] and [parseCommandState] (both pure
     * and unit-tested): the key is deleted only when it points at *this* installation, at an
     * executable that no longer exists, or carries no command value at all — a partial
     * registration this code produced, identified by reg's own "unable to find" output
     * rather than by an exit code (`reg.exe` exits 1 for access-denied too) or by "the value
     * did not parse". Anything else is left alone: a registration owned by a different live
     * install, a command in a form this code does not read (`REG_EXPAND_SZ`, unquoted —
     * what a WiX/MSI-authored registration looks like), and any registry read that failed
     * for any reason. Failing to *read* must never escalate to *deleting*.
     *
     * Note this protection is delete-only. [registerProtocol] still overwrites an
     * unparseable command (`commandPointsToValidExecutable` fails closed), so an
     * installer-authored registration survives cleanup but not a re-registration.
     */
    fun unregisterProtocol(): UnregisterOutcome {
        if (!isWindows) return UnregisterOutcome.NOT_APPLICABLE

        val command = queryProtocolCommand()
        val decision =
            WindowsProtocolCleanup.classifyProtocolCleanup(
                rootPresent = queryRootKeyPresent(),
                command = command,
                appPath = getApplicationPath(),
                exeExists = { File(it).exists() },
            )
        logCleanupDecision(decision, command)
        return when (decision) {
            is WindowsProtocolCleanup.CleanupDecision.Report -> decision.outcome
            WindowsProtocolCleanup.CleanupDecision.Delete -> deleteProtocolKey()
        }
    }

    /**
     * CLI mapping for `--unregister-protocol`: 0 when nothing is left to clean up
     * (removed / nothing registered / not a Windows host), 1 when the registration was
     * deliberately left in place, 2 when the delete itself failed.
     */
    fun unregisterProtocolExitCode(): Int {
        val outcome = unregisterProtocol()
        logger.info(LogCategory.SYSTEM, "boss:// protocol cleanup finished", mapOf("outcome" to outcome.name))
        return WindowsProtocolCleanup.exitCodeFor(outcome)
    }

    /**
     * Check if the protocol is already registered.
     *
     * Shares [queryRootKeyPresent] with the cleanup path, so this startup-path call gets the
     * same argv invocation, 5s timeout and narrow error handling — a wedged `reg.exe` used to
     * be able to block app init here, which is worse than hanging the uninstall hook the
     * timeout was added for. An unknown answer stays `false`: callers only decide whether to
     * (re-)register, and registration is idempotent.
     */
    fun isProtocolRegistered(): Boolean = isWindows && queryRootKeyPresent() == true

    /**
     * Get the path to the running application
     */
    private fun getApplicationPath(): String? {
        return try {
            // Priority 1: Check for jpackage installation (MSI/EXE)
            // This is the most reliable method for production deployments
            val jpackagePath = System.getProperty("jpackage.app-path")
            if (!jpackagePath.isNullOrEmpty()) {
                val file = File(jpackagePath)
                if (file.exists()) {
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Detected jpackage installation",
                        mapOf("path" to WindowsProtocolCleanup.maskUserPath(jpackagePath)),
                    )
                    return jpackagePath
                } else {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "jpackage.app-path set but file doesn't exist",
                        mapOf("path" to WindowsProtocolCleanup.maskUserPath(jpackagePath)),
                    )
                }
            }

            // Priority 2: Try to get the path from the running JAR/EXE
            val jarFile = codeSourceFile(WindowsProtocolHandler::class.java.protectionDomain.codeSource.location)

            // Convert to Windows path format and handle different packaging scenarios
            when {
                jarFile.name.endsWith(".jar") -> {
                    // Running from JAR - look for launcher executable
                    val launcherPath = jarFile.parentFile.resolve("BOSS.exe")
                    if (launcherPath.exists()) {
                        launcherPath.absolutePath
                    } else {
                        // Cannot use "javaw.exe -jar" as registry needs executable path
                        logger.warn(LogCategory.SYSTEM, "Running from JAR without launcher executable")
                        null
                    }
                }

                jarFile.path.contains("BOSS.exe") -> {
                    // Already an executable
                    jarFile.absolutePath
                }

                else -> {
                    // Development environment - return null to skip registration
                    logger.debug(LogCategory.SYSTEM, "Running in development mode - deep links require MSI installation")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Error determining application path", error = e)
            null
        }
    }

    /**
     * Parse command line arguments to extract deep link URL
     */
    fun extractDeepLinkFromArgs(args: Array<String>): String? {
        // Windows passes the URL as the first argument when launched via protocol
        return args.firstOrNull { it.startsWith("boss://") }
    }

    /**
     * Get the current command registered in the Windows registry for boss:// protocol,
     * or null when it is absent or unreadable. Callers that must tell those two apart
     * (cleanup does — see [classifyProtocolCleanup]) use [queryProtocolCommand] instead.
     */
    private fun getCurrentRegistryCommand(): String? {
        val state = queryProtocolCommand()
        return (state as? WindowsProtocolCleanup.CommandState.Present)?.command
    }

    /**
     * Check if the command points to a valid executable file
     */
    private fun commandPointsToValidExecutable(command: String): Boolean {
        val exePath = WindowsProtocolCleanup.extractExecutablePath(command) ?: return false
        return File(exePath).exists()
    }
}

/** Result of a `reg.exe` invocation. */
private class RegResult(
    val exitCode: Int,
    val output: String,
)

/**
 * Run `reg.exe` with a timeout and narrow error handling, returning null when the command
 * could not be run to completion.
 *
 * Centralized because both cleanup paths need identical guarantees: a wedged `reg.exe` must
 * not hang the `--unregister-protocol` hook an uninstaller is waiting on, and a failure to
 * *run* a query must stay distinguishable from a definitive answer.
 *
 * Output goes to a temp file rather than a pipe. Reading `process.inputStream` first blocks
 * until the child closes stdout — which for a genuinely wedged process happens when it
 * exits, so the timeout below would never be reached; reading after `waitFor` instead risks
 * filling the pipe buffer and deadlocking. A file avoids both, so the 5s budget really does
 * bound the call.
 */
private fun runReg(vararg args: String): RegResult? {
    val outputFile = createRegOutputFile() ?: return null
    return try {
        val process =
            ProcessBuilder(listOf("reg", *args))
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
        if (process.waitFor(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            RegResult(process.exitValue(), outputFile.readText(consoleCharset()))
        } else {
            process.destroyForcibly()
            logger.warn(
                LogCategory.SYSTEM,
                "reg.exe timed out",
                mapOf("args" to args.joinToString(" "), "timeoutSeconds" to REG_TIMEOUT_SECONDS),
            )
            null
        }
    } catch (e: IOException) {
        logger.debug(LogCategory.SYSTEM, "reg.exe could not be run", mapOf("error" to e.toString()))
        null
    } catch (e: SecurityException) {
        logger.debug(LogCategory.SYSTEM, "reg.exe was not permitted to run", mapOf("error" to e.toString()))
        null
    } catch (e: UnsupportedOperationException) {
        logger.debug(LogCategory.SYSTEM, "reg.exe could not be spawned", mapOf("error" to e.toString()))
        null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.debug(LogCategory.SYSTEM, "Interrupted running reg.exe", mapOf("error" to e.toString()))
        null
    } finally {
        outputFile.delete()
    }
}

/**
 * Charset `reg.exe` writes in — the console/OEM code page, not the JVM default (UTF-8 on
 * JDK 18+). Decoding with the default turns a non-ASCII install path
 * (`C:\Users\Björn\…`) into U+FFFD, and a mangled path is a path that does not exist, which
 * the cleanup policy would otherwise read as "dead registration, safe to delete".
 * `native.encoding` (JDK 18+) is the closest portable handle; fall back to the default.
 */
private fun consoleCharset(): Charset =
    try {
        System.getProperty("native.encoding")?.let { Charset.forName(it) } ?: Charset.defaultCharset()
    } catch (e: IllegalArgumentException) {
        logger.debug(
            LogCategory.SYSTEM,
            "Unknown native.encoding, using the default charset",
            mapOf("error" to e.toString()),
        )
        Charset.defaultCharset()
    }

/** Scratch file for [runReg]'s redirected output; null when even that fails. */
private fun createRegOutputFile(): File? =
    try {
        File.createTempFile("boss-reg", ".txt").apply {
            // destroyForcibly() is asynchronous, so on Windows the child can still hold the
            // handle when the `finally` delete runs; this keeps a timeout from leaking a file.
            deleteOnExit()
        }
    } catch (e: IOException) {
        logger.debug(
            LogCategory.SYSTEM,
            "Could not create a temp file for reg.exe output",
            mapOf("error" to e.toString()),
        )
        null
    } catch (e: SecurityException) {
        logger.debug(
            LogCategory.SYSTEM,
            "Not permitted to create a temp file for reg.exe output",
            mapOf("error" to e.toString()),
        )
        null
    }

/**
 * `reg delete` the whole `boss` key tree. File-private rather than an object member so
 * [WindowsProtocolHandler] stays within its function budget; it is only ever reached from
 * [WindowsProtocolHandler.unregisterProtocol], which decides whether deleting is safe.
 */
private fun deleteProtocolKey(): WindowsProtocolHandler.UnregisterOutcome {
    val result = runReg("delete", PROTOCOL_KEY, "/f")
    return when {
        result == null -> {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to remove boss:// protocol registration",
                mapOf("key" to PROTOCOL_KEY),
            )
            WindowsProtocolHandler.UnregisterOutcome.FAILED
        }

        result.exitCode == 0 -> {
            logger.info(LogCategory.SYSTEM, "Removed boss:// protocol registration", mapOf("key" to PROTOCOL_KEY))
            WindowsProtocolHandler.UnregisterOutcome.REMOVED
        }

        else -> {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to remove boss:// protocol registration",
                mapOf("key" to PROTOCOL_KEY, "output" to result.output.trim()),
            )
            WindowsProtocolHandler.UnregisterOutcome.FAILED
        }
    }
}

/**
 * Read the registered `shell\open\command`, keeping the absent/unreadable distinction.
 * Process I/O only — the decision lives in [parseCommandState] so it can be tested.
 */
private fun queryProtocolCommand(): WindowsProtocolCleanup.CommandState {
    val result = runReg("query", PROTOCOL_COMMAND_KEY, "/ve") ?: return WindowsProtocolCleanup.CommandState.Unreadable
    return WindowsProtocolCleanup.parseCommandState(result.exitCode, result.output)
}

/**
 * Whether the protocol root key exists, or null when that could not be determined.
 * Process I/O only — see [parseRootKeyPresence].
 */
private fun queryRootKeyPresent(): Boolean? {
    val result = runReg("query", PROTOCOL_KEY) ?: return null
    return WindowsProtocolCleanup.parseRootKeyPresence(result.exitCode, result.output)
}

/**
 * Log the cleanup decision with the context a support case needs — "why did
 * `--unregister-protocol` refuse?" has to be answerable from the log, since the CLI hook
 * has no console output (BOSS.exe is GUI-subsystem).
 *
 * Registered paths embed a Windows username, so they are masked (AGENTS.md).
 */
private fun logCleanupDecision(
    decision: WindowsProtocolCleanup.CleanupDecision,
    command: WindowsProtocolCleanup.CommandState,
) {
    val registered = (command as? WindowsProtocolCleanup.CommandState.Present)?.command
    when {
        decision is WindowsProtocolCleanup.CleanupDecision.Report &&
            decision.outcome == WindowsProtocolHandler.UnregisterOutcome.ABSENT -> {
            logger.debug(LogCategory.SYSTEM, "boss:// protocol is not registered - nothing to clean up")
        }

        decision is WindowsProtocolCleanup.CleanupDecision.Report -> {
            logger.info(
                LogCategory.SYSTEM,
                "boss:// protocol left registered",
                mapOf(
                    "key" to PROTOCOL_KEY,
                    "outcome" to decision.outcome.name,
                    "command" to WindowsProtocolCleanup.maskUserPath(registered),
                ),
            )
        }

        else -> {
            logger.info(
                LogCategory.SYSTEM,
                "boss:// protocol will be removed",
                mapOf("key" to PROTOCOL_KEY, "command" to WindowsProtocolCleanup.maskUserPath(registered)),
            )
        }
    }
}
