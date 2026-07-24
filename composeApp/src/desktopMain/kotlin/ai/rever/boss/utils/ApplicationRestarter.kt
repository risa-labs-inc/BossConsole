package ai.rever.boss.utils

import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

object ApplicationRestarter {
    private val logger = BossLogger.forComponent("ApplicationRestarter")

    private var isRestarting = false

    @OptIn(DelicateCoroutinesApi::class)
    fun restartApplication() {
        if (isRestarting) return // Prevent multiple restart attempts
        isRestarting = true

        // Launch restart in a coroutine
        GlobalScope.launch {
            try {
                // Build the relaunch command BEFORE shutting anything down, so detection
                // (process handle, code source) still reflects the running install.
                val command = buildRelaunchCommand()

                // Perform graceful shutdown
                performGracefulShutdown()

                logger.info(LogCategory.SYSTEM, "Restarting application", mapOf("command" to command.joinToString(" ")))

                // Start the relauncher as a detached child. On Unix it is re-parented to
                // launchd/init when we exit, so it survives to bring the app back up.
                val processBuilder = ProcessBuilder(command)
                processBuilder.directory(File(System.getProperty("user.dir")))
                processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
                processBuilder.start()

                // Give the relauncher a moment to spin up, then exit. The relauncher
                // waits for this PID to terminate before launching the new instance.
                delay(500)

                // Exit current instance
                exitProcess(0)
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to restart application", error = e)
                isRestarting = false

                // Show error and exit anyway
                exitProcess(1)
            }
        }
    }

    /**
     * Build the command that brings BOSS back up after this process exits.
     *
     * For a packaged install we must relaunch the native bundle/launcher — the old
     * `java -jar <single-jar>` path cannot boot a jpackage app (wrong classpath, missing
     * native libs like JxBrowser/Skia), so the app would quit and never come back. We
     * wrap the relaunch in a tiny shell that waits for the current PID to fully terminate
     * first, otherwise LaunchServices just re-activates the dying instance.
     *
     * Falls back to the JAR / Gradle commands for development runs.
     */
    private fun buildRelaunchCommand(): List<String> {
        val osName = System.getProperty("os.name").lowercase()
        val pid = runCatching { ProcessHandle.current().pid() }.getOrNull()
        val launcher =
            runCatching {
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null)
            }.getOrNull()
        val isJavaLauncher =
            launcher != null &&
                Regex(""".*[/\\](java|javaw)(\.exe)?$""").matches(launcher.lowercase())

        // macOS packaged .app → relaunch the bundle via LaunchServices once we're gone.
        if (osName.contains("mac")) {
            val appPath = detectMacAppBundle(launcher)
            if (appPath != null) {
                return listOf("/bin/sh", "-c", "${waitForPidPrefix(pid)}open ${shellQuote(appPath)}")
            }
        }

        // Windows / Linux packaged native launcher → re-exec it (with the same args)
        // once the old process exits. The wait-for-old-PID is essential on every
        // platform: SingleInstanceManager.acquireLock() returns false while the old PID
        // is still alive, so a new instance that starts too early just exits — and
        // nothing comes back (the very bug this fixes).
        if (launcher != null && !isJavaLauncher) {
            val args =
                runCatching {
                    ProcessHandle
                        .current()
                        .info()
                        .arguments()
                        .orElse(emptyArray())
                        .toList()
                }.getOrDefault(emptyList())
            if (osName.contains("windows")) {
                val waitPs =
                    if (pid != null) {
                        "while (Get-Process -Id $pid -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 200 }; "
                    } else {
                        "Start-Sleep -Milliseconds 1000; "
                    }
                val argList =
                    if (args.isEmpty()) {
                        ""
                    } else {
                        " -ArgumentList @(${args.joinToString(",") { psQuote(it) }})"
                    }
                val script = "$waitPs Start-Process -FilePath ${psQuote(launcher)}$argList"
                return listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            }
            val relaunch = (listOf(launcher) + args).joinToString(" ") { shellQuote(it) }
            return listOf("/bin/sh", "-c", "${waitForPidPrefix(pid)}$relaunch")
        }

        // Development fallbacks (previous behavior).
        val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val currentJar =
            runCatching {
                File(
                    ApplicationRestarter::class.java.protectionDomain.codeSource.location
                        .toURI(),
                )
            }.getOrNull()
        return when {
            currentJar?.name?.endsWith(".jar") == true -> {
                listOf(javaBin, "-jar", currentJar.path)
            }

            currentJar?.name == "classes" || currentJar?.path?.contains("build") == true -> {
                val gradlew = if (osName.contains("windows")) "gradlew.bat" else "./gradlew"
                listOf(gradlew, "desktopRun", "-DmainClass=ai.rever.boss.MainKt", "--quiet")
            }

            else -> {
                listOf(javaBin, "-cp", System.getProperty("java.class.path"), "ai.rever.boss.MainKt")
            }
        }
    }

    /** A `sh` snippet that blocks until [pid] is gone (best-effort), or a short sleep if unknown. */
    private fun waitForPidPrefix(pid: Long?): String =
        if (pid != null) "while kill -0 $pid 2>/dev/null; do sleep 0.2; done; " else "sleep 1; "

    /** Single-quote a string for safe embedding in an `sh -c` command. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Single-quote a string for safe embedding in a PowerShell command. */
    private fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"

    /**
     * Locate the running macOS `.app` bundle, or null in dev mode. Mirrors the proven
     * detection in UpdateInstaller (launcher path → java.library.path → code-source walk).
     */
    private fun detectMacAppBundle(launcher: String?): String? {
        // Each candidate is validated with File.exists() (mirroring UpdateInstaller) so a
        // stray ".app" substring on the path never gets handed to `open`; on a miss we
        // fall through to the next method rather than returning a bogus bundle.

        // a) The jpackage launcher lives at <App>.app/Contents/MacOS/<App>.
        if (launcher != null) {
            val idx = launcher.indexOf(".app/Contents/MacOS/")
            if (idx >= 0) {
                val candidate = launcher.substring(0, idx + ".app".length)
                if (File(candidate).exists()) return candidate
            }
        }
        // b) java.library.path usually contains the bundle directory.
        System
            .getProperty("java.library.path")
            ?.split(File.pathSeparator)
            ?.firstOrNull { it.contains(".app") }
            ?.let { it.substringBefore(".app") + ".app" }
            ?.takeIf { File(it).exists() }
            ?.let { return it }
        // c) Walk up from the code source looking for a .app bundle (these are real
        //    ancestors of the running code, so they exist by construction).
        runCatching {
            var current: File? =
                File(
                    ApplicationRestarter::class.java.protectionDomain.codeSource.location
                        .toURI(),
                )
            repeat(6) {
                val f = current ?: return@runCatching
                if (f.name.endsWith(".app")) return f.absolutePath
                current = f.parentFile
            }
        }
        return null
    }

    private suspend fun performGracefulShutdown() {
        try {
            // Close browser engine if it exists
            val engine = FluckEngine.currentEngine
            if (engine != null && !engine.isClosed) {
                logger.debug(LogCategory.SYSTEM, "Closing browser engine")
                engine.close()
                delay(500) // Give time for engine to close
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error during graceful shutdown", error = e)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun scheduleRestart(delayMillis: Long = 1000) {
        GlobalScope.launch {
            delay(delayMillis)
            restartApplication()
        }
    }

    /**
     * Quit the application without restarting
     * Used when an update helper script is waiting to install the update after the app quits
     *
     * This performs a graceful shutdown and exits cleanly, allowing external scripts
     * to monitor the process PID and proceed with installation once the app has fully terminated.
     *
     * Uses runBlocking to ensure cleanup completes synchronously before exit,
     * preventing race conditions with other code.
     */
    fun quitForUpdate() {
        if (isRestarting) return // Prevent multiple quit attempts
        isRestarting = true

        logger.info(LogCategory.SYSTEM, "Quitting application for update installation")

        // Use runBlocking to ensure cleanup completes synchronously
        // This prevents race conditions where the function might return
        // before cleanup has even started
        runBlocking {
            try {
                // Perform graceful shutdown
                performGracefulShutdown()

                logger.info(LogCategory.SYSTEM, "Shutdown complete. Exiting")

                // Give cleanup time to complete
                delay(200)

                // Exit cleanly - update script will wait for this PID to terminate
                exitProcess(0)
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Error during quit", error = e)
                // Exit anyway
                exitProcess(1)
            }
        }
        // This point is NEVER reached - exitProcess() terminates the JVM
    }
}
