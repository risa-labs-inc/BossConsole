package ai.rever.boss.profile

import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.mcp.WorkspaceMcpToolProvider
import ai.rever.boss.plugin.browser.WindowBrowserProfiles
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens a BOSS profile in a window of its own, optionally showing a given Space there.
 *
 * Where that window runs depends on how the profile signs in:
 * - [ProfileAuthMode.SHARED] (the main account): a NEW WINDOW IN THIS PROCESS, sharing its sign-in,
 *   plugins and plugin settings, with its browser tabs on the profile's own browser profile
 *   ([WindowBrowserProfiles]). No process is started and no session is created.
 * - [ProfileAuthMode.SEPARATE]: a separate BOSS process whose data root is the profile's
 *   (see [BossDirectories.profileId]), which signs in through the normal BOSS login.
 *
 * This is the hidden half of Spaces-as-profiles. Nothing in the default flow reaches it - a
 * Space still opens in the current window, under the current profile - and it is driven only
 * through its API surface (the `profile_*` MCP tools, which sit behind the approval gate).
 *
 * A separate-account profile is one process at a time, like the main profile: its root carries
 * its own single-instance channel, so opening one that is already running hands the Space to that
 * process over its channel rather than starting a second one.
 */
@Suppress("TooManyFunctions") // two open paths, one per kind of profile, each in small steps
object BossProfileLauncher {
    private val logger = BossLogger.forComponent("BossProfileLauncher")

    /** How long a just-launched profile is given to start answering on its channel. */
    private const val STARTUP_WAIT_MS = 90_000L
    private const val STARTUP_POLL_MS = 500L

    /** Environment that describes the LAUNCHING process and must not leak into the profile. */
    private val INHERITED_ENV_TO_DROP =
        listOf(
            "BOSS_DATA_DIR",
            "BOSS_WINDOW_ID",
            "BOSS_PROJECT_PATH",
            "BOSS_MCP_PORT",
            "BOSS_PLUGIN_CLASSPATH",
        )

    private val launchLocks = ConcurrentHashMap<String, Mutex>()
    private val launchedAt = ConcurrentHashMap<String, Long>()

    sealed class Outcome {
        abstract val profile: BossProfile

        /** A same-account profile's window was opened in this process. */
        data class WindowOpened(
            override val profile: BossProfile,
            val windowId: String?,
        ) : Outcome()

        /** A separate-account profile was already running; the Space (if any) was handed to it. */
        data class Forwarded(
            override val profile: BossProfile,
            val workspaceFile: File?,
        ) : Outcome()

        /** A new process was started for a separate-account profile. */
        data class Launched(
            override val profile: BossProfile,
            val workspaceFile: File?,
        ) : Outcome()
    }

    /** The runtime directory that holds [profileId]'s single-instance channel. */
    fun runtimeDirOf(profileId: String): File = File(BossDirectories.profileRoot(profileId), "run")

    /**
     * Whether [profile] is open: a separate-account profile's process is answering, or a
     * same-account profile has a window in this process.
     */
    fun isOpen(profile: BossProfile): Boolean =
        when (profile.auth) {
            ProfileAuthMode.SHARED -> WindowBrowserProfiles.windowsFor(profile.id).isNotEmpty()
            ProfileAuthMode.SEPARATE -> isRunning(profile.id)
        }

    /** Whether [profileId]'s own process is up and answering. */
    fun isRunning(profileId: String): Boolean = SingleInstanceManager.isInstanceRunningAt(runtimeDirOf(profileId))

    /**
     * Opens [profile] in a window of its own, showing [workspace] when given, and binds the Space
     * to the profile ([BossProfileStore.bindWorkspace]).
     */
    suspend fun open(
        profile: BossProfile,
        workspace: LayoutWorkspace? = null,
    ): Result<Outcome> =
        runCatching {
            when (profile.auth) {
                ProfileAuthMode.SHARED -> openSharedWindow(profile, workspace)
                ProfileAuthMode.SEPARATE -> openSeparateProcess(profile, workspace)
            }
        }.onFailure {
            logger.warn(LogCategory.SYSTEM, "Could not open BOSS profile", mapOf("profileId" to profile.id), error = it)
        }

    /**
     * A new window in THIS process, on the profile's own browser profile. The Space is the one in
     * this process's own store - same account, same Spaces - so nothing is copied.
     */
    private suspend fun openSharedWindow(
        profile: BossProfile,
        workspace: LayoutWorkspace?,
    ): Outcome {
        check(!BossDirectories.isProfile) {
            "'${profile.name}' shares the main BOSS account; open it from a main BOSS window"
        }
        var createdWindowId: String? = null
        val createWindow = {
            WindowManager.createNewWindow(browserProfileId = profile.id).id.also { createdWindowId = it }
        }
        if (workspace == null) {
            val windowId = withContext(Dispatchers.Main) { createWindow() }
            return Outcome.WindowOpened(profile, windowId)
        }
        val result = WorkspaceMcpToolProvider.openWorkspaceInNewWindow(workspace.id, createWindow)
        if (result.isError) {
            // The window is created before the Space is validated and applied; a refusal after
            // that must not leave an empty profile window behind.
            createdWindowId?.let { id -> withContext(Dispatchers.Main) { WindowManager.closeWindow(id) } }
            error(result.text)
        }
        withContext(Dispatchers.IO) { BossProfileStore.bindWorkspace(profile.id, workspace.id).getOrThrow() }
        logger.info(LogCategory.SYSTEM, "Opened a same-account BOSS profile window", mapOf("profileId" to profile.id))
        return Outcome.WindowOpened(profile, createdWindowId)
    }

    private suspend fun openSeparateProcess(
        profile: BossProfile,
        workspace: LayoutWorkspace?,
    ): Outcome {
        require(profile.id != BossDirectories.profileId) {
            "Profile '${profile.id}' is this window's own profile; open the Space here instead"
        }
        val workspaceFile = workspace?.let { installWorkspace(profile, it) }
        val mutex = launchLocks.computeIfAbsent(profile.id) { Mutex() }
        val outcome =
            mutex.withLock {
                if (awaitRunningIfStarting(profile.id)) {
                    forward(profile, workspaceFile)
                    Outcome.Forwarded(profile, workspaceFile)
                } else {
                    launch(profile, workspaceFile)
                    launchedAt[profile.id] = System.currentTimeMillis()
                    Outcome.Launched(profile, workspaceFile)
                }
            }
        // Bound only after the hand-off: a failed forward binds nothing. A launch is detached, so
        // a profile that never boots is not detected here and still gets the binding.
        if (workspace != null) {
            withContext(Dispatchers.IO) {
                BossProfileStore.bindWorkspace(profile.id, workspace.id).getOrThrow()
            }
        }
        return outcome
    }

    /**
     * True when the profile is answering, waiting first if this process launched it a moment
     * ago and it is still starting - a second open in that window must not start a second
     * process, which would only lose the single-instance race and forward its Space anyway.
     */
    private suspend fun awaitRunningIfStarting(profileId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (isRunning(profileId)) return@withContext true
            val started = launchedAt[profileId] ?: return@withContext false
            val deadline = started + STARTUP_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(STARTUP_POLL_MS)
                if (isRunning(profileId)) return@withContext true
            }
            false
        }

    private suspend fun forward(
        profile: BossProfile,
        workspaceFile: File?,
    ) {
        if (workspaceFile == null) return
        val delivered =
            withContext(Dispatchers.IO) {
                // EXTERNAL, deliberately: the receiving window then asks before running any
                // terminal startup commands the Space carries, exactly as for any other link.
                SingleInstanceManager.sendToInstanceAt(
                    runtimeDirOf(profile.id),
                    workspaceLinkFor(workspaceFile),
                    DeepLinkOrigin.EXTERNAL,
                )
            }
        check(delivered) { "Profile '${profile.id}' is running but did not accept the Space" }
    }

    /**
     * Copies [workspace] into a separate-account profile's own Space store the first time it is
     * opened there; the caller binds it once the hand-off succeeds. After that the profile's copy
     * is the profile's to change, and is never overwritten from here.
     */
    private suspend fun installWorkspace(
        profile: BossProfile,
        workspace: LayoutWorkspace,
    ): File =
        withContext(Dispatchers.IO) {
            val dir = BossProfileStore.workspacesDirOf(profile.id).apply { mkdirs() }
            val file = File(dir, WorkspaceFileManagerCommon.fileNameForId(workspace.id))
            if (!file.exists()) {
                file.atomicWriteText(WorkspaceSerializer.serialize(workspace))
            }
            file
        }

    /** Starts a separate-account profile's process. It signs in through the normal BOSS login. */
    private suspend fun launch(
        profile: BossProfile,
        workspaceFile: File?,
    ) {
        val args = listOfNotNull(workspaceFile?.let(::workspaceLinkFor))
        val command = ProfileLaunchCommand.build(profile.id, args)
        val consoleLog = File(File(profile.root, "logs").apply { mkdirs() }, "console.log").also(::rotateIfLarge)

        withContext(Dispatchers.IO) {
            val builder = ProfileLaunchCommand.detached(command)
            val env = builder.environment()
            INHERITED_ENV_TO_DROP.forEach(env::remove)
            env[BossDirectories.PROFILE_ENV] = profile.id
            // A packaged launcher takes no JVM flags, so a dev-mode parent's -Dboss.dev.mode would
            // be lost and the child would root under ~/.boss while its profile lives in ~/.boss_debug.
            if (BossDirectories.isDevMode) env["BOSS_DEV_MODE"] = "true"
            // A host log file named by the launcher's environment would otherwise be shared.
            if (!env["BOSS_LOG_FILE"].isNullOrBlank() && !env["BOSS_LOG_FILE"].equals("off", ignoreCase = true)) {
                env["BOSS_LOG_FILE"] = File(profile.root, "logs/boss.log").absolutePath
            }
            ProfileLaunchCommand.javaClasspath(command)?.let { env["CLASSPATH"] = it }
            builder.directory(File(System.getProperty("user.home")))
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(consoleLog))
            builder.redirectErrorStream(true)
            builder.start()
        }
        logger.info(
            LogCategory.SYSTEM,
            "Launched BOSS profile",
            mapOf("profileId" to profile.id, "withSpace" to (workspaceFile != null)),
        )
    }

    /** Keeps one previous `console.log`, so a profile relaunched for months cannot fill the disk. */
    private fun rotateIfLarge(log: File) {
        if (log.length() > MAX_CONSOLE_LOG_BYTES) {
            val previous = File(log.parentFile, "${log.name}.1")
            previous.delete()
            log.renameTo(previous)
        }
    }

    private const val MAX_CONSOLE_LOG_BYTES = 10L * 1024 * 1024

    private fun workspaceLinkFor(file: File): String =
        "boss://workspace?path=" + URLEncoder.encode(file.absolutePath, StandardCharsets.UTF_8)

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    private fun nullDevice(): File = File(if (isWindows) "NUL" else "/dev/null")
}

/**
 * The command line that starts another BOSS process from this one: the same native launcher for
 * a packaged install, or the same JVM, flags and main class for a development run.
 */
internal object ProfileLaunchCommand {
    private const val MAIN_CLASS = "ai.rever.boss.MainKt"

    /** JVM flags that describe THIS process and must not be repeated in another one. */
    private val DROPPED_JVM_FLAGS =
        listOf("-agentlib:jdwp", "-Xrunjdwp", "-D${BossDirectories.PROFILE_PROPERTY}=", "-Xdebug")

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    fun build(
        profileId: String,
        args: List<String>,
    ): List<String> {
        val launcher =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow { IllegalStateException("Cannot determine how this BOSS process was started") }
        return commandFor(launcher, ManagementFactory.getRuntimeMXBean().inputArguments, profileId, args)
    }

    /** [build] with the running process's launcher and JVM flags passed in, so it is testable. */
    internal fun commandFor(
        launcher: String,
        jvmFlags: List<String>,
        profileId: String,
        args: List<String>,
    ): List<String> =
        if (isJavaLauncher(launcher)) {
            val kept = jvmFlags.filterNot { flag -> DROPPED_JVM_FLAGS.any { flag.startsWith(it) } }
            listOf(launcher) + kept + "-D${BossDirectories.PROFILE_PROPERTY}=$profileId" + MAIN_CLASS + args
        } else {
            // jpackage launcher (macOS .app/Contents/MacOS/<App>, Windows .exe, Linux bin/<App>).
            listOf(launcher) + args
        }

    /**
     * The class path a development launch needs, passed as `CLASSPATH` rather than `-cp` so a
     * long one cannot exceed the Windows command-line limit. Null for a packaged launcher.
     */
    fun javaClasspath(command: List<String>): String? =
        if (command.isNotEmpty() && isJavaLauncher(command.first())) System.getProperty("java.class.path") else null

    /**
     * A process builder that leaves the new BOSS fully detached on macOS and Linux: `sh` starts
     * it in the background and exits, so it is re-parented to init and is not this process's
     * descendant (which the performance panel would otherwise count as part of this one).
     */
    fun detached(
        command: List<String>,
        windows: Boolean = isWindows,
    ): ProcessBuilder =
        if (windows) {
            ProcessBuilder(command)
        } else {
            ProcessBuilder(listOf("/bin/sh", "-c", "\"\$@\" &", "boss-profile") + command)
        }

    private val JAVA_LAUNCHER = Regex(""".*[/\\](java|javaw)(\.exe)?$""")

    internal fun isJavaLauncher(launcher: String): Boolean = JAVA_LAUNCHER.matches(launcher.lowercase())
}
