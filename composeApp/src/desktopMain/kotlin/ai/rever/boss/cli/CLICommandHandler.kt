package ai.rever.boss.cli

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.services.URLHandlerService
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowManager
import kotlinx.coroutines.*
import java.io.File

/**
 * Handles CLI commands with queueing for app lifecycle coordination.
 *
 * Commands may arrive before the app UI is ready, so they are queued
 * and executed once WindowManager and other services are initialized.
 *
 * Thread Safety: All UI operations use Dispatchers.Main.
 */
class CLICommandHandler private constructor() {
    private val logger = BossLogger.forComponent("CLICommandHandler")

    private val initializationQueue = ReadinessQueue<CLICommand>()

    // Holds whole commands, not just the command text: the origin decides whether
    // the command may run unattended, and a queue that dropped it would turn a
    // cold-start request into an unattributed one.
    private val terminalReadinessQueue = ReadinessQueue<CLICommand.OpenTerminal>()

    // Whole commands for the same reason: a Space can carry terminal commands.
    private val workspaceReadinessQueue = ReadinessQueue<CLICommand.LoadWorkspace>()
    private val fileReadinessQueue = ReadinessQueue<String>()

    // Service references - set during initialization
    private var windowManager: WindowManager? = null
    private var getSplitViewState: (() -> SplitViewState?)? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun dispatchCommand(command: CLICommand) {
        if (initializationQueue.enqueueOrClaimForCaller(command)) {
            scope.launch {
                executeCommand(command)
            }
        } else {
            logger.debug(LogCategory.SYSTEM, "Queued command", mapOf("command" to command.toString()))
        }
    }

    /**
     * Command processor hook, primarily for testing.
     * Defaults to the real async dispatch. Swap around a test to observe
     * commands without requiring real services or coroutines.
     */
    var commandProcessor: (CLICommand) -> Unit = ::dispatchCommand

    fun processCommand(command: CLICommand) {
        commandProcessor(command)
    }

    companion object {
        @Volatile
        private var instance: CLICommandHandler? = null

        fun getInstance(): CLICommandHandler =
            instance ?: synchronized(this) {
                instance ?: CLICommandHandler().also { instance = it }
            }
    }

    /**
     * Register services once app is initialized.
     * Should be called from main.kt after app setup.
     */
    fun initialize(
        windowManager: WindowManager,
        getSplitViewState: () -> SplitViewState?,
    ) {
        this.windowManager = windowManager
        this.getSplitViewState = getSplitViewState
        val queuedCommands = initializationQueue.markReadyAndClaimQueued()

        logger.info(LogCategory.SYSTEM, "CLI initialized with services")

        // Execute queued commands
        executeQueuedCommands(queuedCommands)
    }

    /**
     * Queue a command for execution.
     * If app is ready, executes immediately.
     * Otherwise, queues for later execution.
     */
    fun queueCommand(command: CLICommand) {
        commandProcessor(command)
    }

    /**
     * Mark terminal handler as ready and process queued terminal events.
     * Should be called from BossApp.kt after TerminalEventBus listener is set up.
     */
    fun markTerminalHandlerReady() {
        val queuedCommands = terminalReadinessQueue.markReadyAndClaimQueued()
        logger.debug(LogCategory.SYSTEM, "Terminal handler marked as ready")

        // Process queued terminal events
        scope.launch {
            queuedCommands.forEach { queued ->
                try {
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Processing queued terminal command",
                        mapOf(
                            "hasCommand" to (queued.command != null),
                            "origin" to queued.origin.name,
                        ),
                    )
                    handleOpenTerminal(queued.command, queued.origin)
                } catch (e: Exception) {
                    logger.error(LogCategory.SYSTEM, "Failed to process queued terminal event", error = e)
                }
            }
        }
    }

    /**
     * Mark file handler as ready and process queued file events.
     * Should be called from BossApp.kt after FileEventBus listener is set up.
     */
    fun markFileHandlerReady() {
        val queuedFiles = fileReadinessQueue.markReadyAndClaimQueued()
        logger.debug(LogCategory.SYSTEM, "File handler marked as ready")

        // Process queued file events
        scope.launch {
            queuedFiles.forEach { filePath ->
                try {
                    logger.debug(LogCategory.SYSTEM, "Processing queued file", mapOf("path" to filePath))
                    handleOpenFile(filePath)
                } catch (e: Exception) {
                    logger.error(LogCategory.SYSTEM, "Failed to process queued file event", error = e)
                }
            }
        }
    }

    /**
     * Mark workspace handler as ready and process queued workspace loads.
     * Should be called from BossApp.kt after Last Session workspace is loaded.
     */
    fun markWorkspaceHandlerReady() {
        val queuedWorkspaces = workspaceReadinessQueue.markReadyAndClaimQueued()
        logger.debug(LogCategory.SYSTEM, "Workspace handler marked as ready")

        // Process queued workspace loads
        scope.launch {
            queuedWorkspaces.forEach { command ->
                try {
                    logger.debug(LogCategory.SYSTEM, "Processing queued workspace", mapOf("path" to command.configPath))
                    handleLoadWorkspace(command)
                } catch (e: Exception) {
                    logger.error(LogCategory.SYSTEM, "Failed to process queued workspace", error = e)
                }
            }
        }
    }

    private fun executeQueuedCommands(queuedCommands: List<CLICommand>) {
        scope.launch {
            for (command in queuedCommands) {
                executeCommand(command)
            }

            // Note: Workspace queue is now processed by markWorkspaceHandlerReady()
            // This ensures workspaces load AFTER Last Session, not during app initialization
        }
    }

    private suspend fun executeCommand(command: CLICommand) {
        try {
            logger.debug(LogCategory.SYSTEM, "Executing command", mapOf("command" to command.toString()))

            when (command) {
                is CLICommand.OpenUrl -> {
                    handleOpenUrl(command.url)
                }

                is CLICommand.LoadWorkspace -> {
                    handleLoadWorkspace(command)
                }

                is CLICommand.OpenFile -> {
                    if (fileReadinessQueue.enqueueOrClaimForCaller(command.filePath)) {
                        // File handler ready - execute immediately
                        logger.debug(LogCategory.SYSTEM, "File handler ready, executing immediately", mapOf("path" to command.filePath))
                        handleOpenFile(command.filePath)
                    } else {
                        // File handler not ready - queue for later (cold start)
                        logger.debug(LogCategory.SYSTEM, "File handler not ready, queueing file", mapOf("path" to command.filePath))
                    }
                }

                is CLICommand.OpenFolder -> {
                    handleOpenFolder(command.folderPath)
                }

                is CLICommand.OpenTerminal -> {
                    if (terminalReadinessQueue.enqueueOrClaimForCaller(command)) {
                        // Terminal handler ready - execute immediately
                        logger.debug(
                            LogCategory.SYSTEM,
                            "Terminal handler ready, executing immediately",
                            mapOf(
                                "hasCommand" to (command.command != null),
                                "origin" to command.origin.name,
                            ),
                        )
                        handleOpenTerminal(command.command, command.origin)
                    } else {
                        // Terminal handler not ready - queue for later (cold start)
                        logger.debug(
                            LogCategory.SYSTEM,
                            "Terminal handler not ready, queueing",
                            mapOf(
                                "hasCommand" to (command.command != null),
                                "origin" to command.origin.name,
                            ),
                        )
                    }
                }

                is CLICommand.SwitchWorkspace -> {
                    handleSwitchWorkspace(command)
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Error executing command", error = e)
        }
    }

    /**
     * Opens URL in Fluck browser tab.
     */
    private suspend fun handleOpenUrl(url: String) {
        // Normalize and validate URL (adds https:// if missing)
        val normalizedUrl = CLISecurityValidator.normalizeAndValidateUrl(url)
        if (normalizedUrl == null) {
            logger.warn(LogCategory.SYSTEM, "Invalid URL", mapOf("url" to LogSanitizer.describeUri(url)))
            return
        }

        withContext(Dispatchers.Main) {
            URLHandlerService.handleURL(normalizedUrl)
        }
    }

    /**
     * Loads workspace configuration from file.
     *
     * Emits workspace load event via WorkspaceEventBus for BossApp to handle.
     * This ensures workspace loading has access to splitViewState and workspaceManager.
     *
     * The event says whether the load needs the operator's confirmation, which is
     * decided by the command's origin exactly as a terminal command's is. Only the
     * window can act on it, because only the window parses the file and so only it
     * knows whether the Space carries any terminal commands at all.
     */
    private suspend fun handleLoadWorkspace(command: CLICommand.LoadWorkspace) {
        // Validate file exists
        val file = File(command.configPath).absoluteFile
        if (!file.exists()) {
            logger.warn(LogCategory.SYSTEM, "Workspace config not found", mapOf("path" to file.absolutePath))
            return
        }

        if (!file.canRead()) {
            logger.warn(LogCategory.SYSTEM, "Cannot read workspace config", mapOf("path" to file.absolutePath))
            return
        }

        // Validate path for security (prevent path traversal)
        if (!CLISecurityValidator.isValidPath(file.absolutePath)) {
            logger.warn(LogCategory.SYSTEM, "Invalid workspace path (security check failed)", mapOf("path" to file.absolutePath))
            return
        }

        // Queue workspace if handler not ready (cold start)
        // This ensures workspace loads AFTER Last Session, preventing tab destruction
        if (!workspaceReadinessQueue.enqueueOrClaimForCaller(command.copy(configPath = file.absolutePath))) {
            logger.debug(LogCategory.SYSTEM, "Workspace handler not ready, queueing", mapOf("path" to file.absolutePath))
            return
        }

        // Resolve the target window. Uses the registration/focus-gain-backed
        // lookup, not focusedWindowFlow alone — an MCP-driven or CLI caller
        // holds OS focus itself, so the flow can be null while a usable window
        // is plainly registered (same reason as the boss:// deep-link handlers).
        val focusedWindowId = WindowFocusManager.resolveActionableWindowId()
        if (focusedWindowId == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "No usable window registered, cannot load workspace",
                mapOf("path" to file.absolutePath),
            )
            return
        }

        // Emit workspace load event - BossApp will handle the actual loading
        // This is much simpler than trying to access splitViewState from CLI layer
        val requiresConfirmation = command.requiresConfirmation
        ai.rever.boss.components.events.WorkspaceEventBus
            .loadWorkspace(
                file.absolutePath,
                sourceWindowId = focusedWindowId,
                requiresConfirmation = requiresConfirmation,
            )
        logger.debug(
            LogCategory.SYSTEM,
            "Emitted workspace load event",
            mapOf(
                "path" to file.absolutePath,
                "windowId" to focusedWindowId,
                "origin" to command.origin.name,
                "requiresConfirmation" to requiresConfirmation,
            ),
        )
    }

    /**
     * Opens file in editor tab.
     *
     * Direct emit only - queueing is handled in executeCommand().
     * This is called from markFileHandlerReady() after handler is ready.
     */
    private suspend fun handleOpenFile(filePath: String) {
        val file = File(filePath).absoluteFile

        if (!file.exists()) {
            logger.warn(LogCategory.SYSTEM, "File not found", mapOf("path" to file.absolutePath))
            return
        }

        if (!file.isFile) {
            logger.warn(LogCategory.SYSTEM, "Not a file", mapOf("path" to file.absolutePath))
            return
        }

        if (!file.canRead()) {
            logger.warn(LogCategory.SYSTEM, "Cannot read file", mapOf("path" to file.absolutePath))
            return
        }

        // Security validation. isValidOpenTargetPath, not isValidPath: this file
        // is going to be read into an editor, never typed into a shell, and
        // isValidPath's shell-metacharacter rules rejected legal filenames
        // ("Q&A notes.md", anything under a directory with a `$` in it) so the
        // open was dropped with nothing shown. See its KDoc.
        if (!CLISecurityValidator.isValidOpenTargetPath(file.absolutePath)) {
            logger.warn(LogCategory.SYSTEM, "Invalid file path (security check failed)", mapOf("path" to file.absolutePath))
            return
        }

        // Resolve the target window (see handleLoadWorkspace for why this is
        // not focusedWindowFlow).
        val focusedWindowId = WindowFocusManager.resolveActionableWindowId()
        if (focusedWindowId == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "No usable window registered, cannot open file",
                mapOf("path" to file.absolutePath),
            )
            return
        }

        // Track file processing (prevents New Tab Dialog race condition)
        ai.rever.boss.services.FileHandlerService
            .incrementProcessing()

        // Emit file open event via FileEventBus
        // The active window's BossApp will listen and create the editor tab
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FileEventBus.openFile(file.absolutePath, sourceWindowId = focusedWindowId)
                logger.debug(
                    LogCategory.SYSTEM,
                    "Emitted file open event",
                    mapOf(
                        "path" to file.absolutePath,
                        "windowId" to focusedWindowId,
                    ),
                )

                // CRITICAL: Wait for file tab to actually be created before decrementing
                delay(500)
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to emit file event", error = e)
            } finally {
                // Always decrement, even on error
                ai.rever.boss.services.FileHandlerService
                    .decrementProcessing()
            }
        }
    }

    /**
     * Opens folder in codebase plugin.
     */
    private suspend fun handleOpenFolder(folderPath: String) {
        val folder = File(folderPath).absoluteFile

        if (!folder.exists()) {
            logger.warn(LogCategory.SYSTEM, "Folder not found", mapOf("path" to folder.absolutePath))
            return
        }

        if (!folder.isDirectory) {
            logger.warn(LogCategory.SYSTEM, "Not a directory", mapOf("path" to folder.absolutePath))
            return
        }

        if (!folder.canRead()) {
            logger.warn(LogCategory.SYSTEM, "Cannot read folder", mapOf("path" to folder.absolutePath))
            return
        }

        // Security validation
        if (!CLISecurityValidator.isValidPath(folder.absolutePath)) {
            logger.warn(LogCategory.SYSTEM, "Invalid folder path (security check failed)", mapOf("path" to folder.absolutePath))
            return
        }

        withContext(Dispatchers.Main) {
            // Resolve the target window (see handleLoadWorkspace for why this is
            // not focusedWindowFlow).
            val focusedWindowId = WindowFocusManager.resolveActionableWindowId()
            val windowProjectState =
                focusedWindowId?.let {
                    ai.rever.boss.window.WindowProjectStateRegistry
                        .get(it)
                }

            val project =
                Project(
                    name = folder.name.extractFileName(),
                    path = folder.absolutePath,
                    lastOpened = System.currentTimeMillis(),
                )

            if (windowProjectState != null) {
                windowProjectState.selectProject(project)
            } else {
                // Fall back to just updating recent projects if no window state available
                ai.rever.boss.components.plugin.panels.left_top.ProjectState
                    .updateRecentProjects(project)
            }
            logger.debug(LogCategory.SYSTEM, "Folder opened in codebase plugin", mapOf("path" to folder.absolutePath))
        }
    }

    /**
     * Opens terminal tab, optionally with command.
     *
     * Direct emit only - queueing is handled in executeCommand().
     * This is called from markTerminalHandlerReady() after handler is ready.
     *
     * A command only runs unattended when [origin] says the operator asked for
     * it themselves. Any other origin — above all a `boss://terminal?command=`
     * link, which the OS will accept from any program that can ask it to open a
     * URL — is marked for confirmation, and the window shows the operator the
     * exact command before a shell ever sees it. Opening a terminal with no
     * command is the same request either way and never prompts.
     */
    private suspend fun handleOpenTerminal(
        command: String?,
        origin: DeepLinkOrigin,
    ) {
        val disposition = terminalCommandDisposition(command, origin)
        if (disposition == TerminalCommandDisposition.REJECT) {
            logger.warn(
                LogCategory.SYSTEM,
                "Terminal command rejected before it could run",
                mapOf(
                    "origin" to origin.name,
                    "wellFormed" to (command != null && CLISecurityValidator.isValidCommand(command)),
                    "length" to (command?.length ?: 0),
                ),
            )
            return
        }
        val requiresConfirmation = disposition == TerminalCommandDisposition.CONFIRM

        // Resolve the target window (see handleLoadWorkspace for why this is
        // not focusedWindowFlow).
        val focusedWindowId = WindowFocusManager.resolveActionableWindowId()
        if (focusedWindowId == null) {
            logger.warn(LogCategory.SYSTEM, "No usable window registered, cannot open terminal")
            return
        }

        // Track terminal processing (prevents New Tab Dialog race condition)
        ai.rever.boss.services.TerminalHandlerService
            .incrementProcessing()

        // Emit terminal open event via TerminalEventBus
        // The active window's BossApp will listen and create the terminal tab
        CoroutineScope(Dispatchers.Main).launch {
            try {
                TerminalEventBus.openTerminal(
                    command,
                    sourceWindowId = focusedWindowId,
                    requiresConfirmation = requiresConfirmation,
                )
                logger.debug(
                    LogCategory.SYSTEM,
                    "Emitted terminal open event",
                    mapOf(
                        "hasCommand" to (command != null),
                        "windowId" to focusedWindowId,
                        "origin" to origin.name,
                        "requiresConfirmation" to requiresConfirmation,
                    ),
                )

                // CRITICAL: Wait for terminal tab to actually be created before decrementing
                delay(500)
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to emit terminal event", error = e)
            } finally {
                // Always decrement, even on error
                ai.rever.boss.services.TerminalHandlerService
                    .decrementProcessing()
            }
        }
    }

    /**
     * Switches active workspace tab.
     */
    private suspend fun handleSwitchWorkspace(command: CLICommand.SwitchWorkspace) {
        val focusedWindowId = WindowFocusManager.resolveActionableWindowId()
        if (focusedWindowId == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "No usable window registered, cannot switch workspace",
                mapOf("workspace" to command.workspaceName),
            )
            return
        }

        ai.rever.boss.components.events.WorkspaceEventBus.switchWorkspace(
            workspaceName = command.workspaceName,
            sourceWindowId = focusedWindowId,
        )
        logger.debug(
            LogCategory.SYSTEM,
            "Emitted workspace switch event",
            mapOf(
                "workspace" to command.workspaceName,
                "windowId" to focusedWindowId,
                "origin" to command.origin.name,
            ),
        )
    }
}

/**
 * Atomically transfers a value either to the caller once ready, or to the
 * readiness transition that claims the deferred FIFO queue.
 *
 * Callers must execute claimed values after this object has released its monitor.
 * FIFO applies within a deferred batch; a post-ready caller may run before that batch.
 */
internal class ReadinessQueue<T> {
    private val lock = Any()
    private val deferred = ArrayDeque<T>()
    private var ready = false

    /** Returns true only when this caller owns [value] for immediate execution. */
    fun enqueueOrClaimForCaller(value: T): Boolean =
        synchronized(lock) {
            if (ready) {
                true
            } else {
                deferred.addLast(value)
                false
            }
        }

    /** Marks this queue ready and transfers every deferred value to the caller in FIFO order. */
    fun markReadyAndClaimQueued(): List<T> =
        synchronized(lock) {
            ready = true
            buildList(deferred.size) {
                while (deferred.isNotEmpty()) {
                    add(deferred.removeFirst())
                }
            }
        }
}

/**
 * Longest command BOSS will put in front of the operator for confirmation. A
 * command it cannot show in full is not one anybody can meaningfully approve, so
 * a longer one from outside the operator's own invocation is dropped rather than
 * prompted. Commands the operator passed to `boss` themselves are never shown and
 * are bounded only by [CLISecurityValidator.isValidCommand].
 */
internal const val TERMINAL_CONFIRM_MAX_COMMAND_LENGTH = 512

/** What BOSS does with a `boss://terminal` request. */
internal enum class TerminalCommandDisposition {
    /** Open a terminal, typing the command if there is one. */
    RUN,

    /** Show the operator the command and open a terminal only if they confirm. */
    CONFIRM,

    /** Do nothing at all. */
    REJECT,
}

/**
 * Decides what happens to a terminal request, from the command's shape and who
 * asked for it.
 *
 * A request with no command just opens a terminal, which is the same request
 * whoever made it. A command runs unattended only when [origin] says the operator
 * ran `boss` themselves; otherwise it is put in front of them first, and dropped
 * outright if it is malformed or too long to display in full.
 */
internal fun terminalCommandDisposition(
    command: String?,
    origin: DeepLinkOrigin,
): TerminalCommandDisposition =
    when {
        command == null -> TerminalCommandDisposition.RUN
        !CLISecurityValidator.isValidCommand(command) -> TerminalCommandDisposition.REJECT
        origin.isOperatorInitiated -> TerminalCommandDisposition.RUN
        command.length > TERMINAL_CONFIRM_MAX_COMMAND_LENGTH -> TerminalCommandDisposition.REJECT
        else -> TerminalCommandDisposition.CONFIRM
    }

/**
 * Whether a Space this load opens must show its terminal commands to the operator before they
 * run. Decided by who asked, exactly as [terminalCommandDisposition] decides for one command: only
 * the operator's own `boss` invocation skips the prompt. Whether there is anything to show is the
 * window's to decide, since only it parses the file.
 */
internal val CLICommand.LoadWorkspace.requiresConfirmation: Boolean
    get() = !origin.isOperatorInitiated

/**
 * Sealed class representing CLI commands.
 */
sealed class CLICommand {
    data class OpenUrl(
        val url: String,
    ) : CLICommand()

    /**
     * @property origin who asked. Defaults to [DeepLinkOrigin.EXTERNAL], as
     *   [OpenTerminal]'s does, because a Space can carry terminal commands.
     */
    data class LoadWorkspace(
        val configPath: String,
        val origin: DeepLinkOrigin = DeepLinkOrigin.EXTERNAL,
    ) : CLICommand()

    data class OpenFile(
        val filePath: String,
    ) : CLICommand()

    data class OpenFolder(
        val folderPath: String,
    ) : CLICommand()

    /**
     * @property command the command to type into the terminal, or null to just
     *   open one.
     * @property origin who asked. Defaults to [DeepLinkOrigin.EXTERNAL] so a
     *   caller that does not say gets the cautious handling; see
     *   [CLICommandHandler.handleOpenTerminal].
     */
    data class OpenTerminal(
        val command: String?,
        val origin: DeepLinkOrigin = DeepLinkOrigin.EXTERNAL,
    ) : CLICommand()

    data class SwitchWorkspace(
        val workspaceName: String,
        val origin: DeepLinkOrigin = DeepLinkOrigin.OPERATOR_CLI,
    ) : CLICommand()
}
