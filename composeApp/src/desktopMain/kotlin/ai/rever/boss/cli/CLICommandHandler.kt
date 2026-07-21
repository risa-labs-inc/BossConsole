package ai.rever.boss.cli

import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowManager
import ai.rever.boss.services.URLHandlerService
import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.window_panel.SplitViewState
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

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

    private val commandQueue = ConcurrentLinkedQueue<CLICommand>()
    private val terminalQueue = ConcurrentLinkedQueue<String>()  // Use empty string as sentinel for null
    private val workspaceQueue = ConcurrentLinkedQueue<String>()
    private val fileQueue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isTerminalHandlerReady = false

    @Volatile
    private var isFileHandlerReady = false

    @Volatile
    private var isWorkspaceHandlerReady = false

    // Service references - set during initialization
    private var windowManager: WindowManager? = null
    private var getSplitViewState: (() -> SplitViewState?)? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        @Volatile
        private var instance: CLICommandHandler? = null

        fun getInstance(): CLICommandHandler {
            return instance ?: synchronized(this) {
                instance ?: CLICommandHandler().also { instance = it }
            }
        }
    }

    /**
     * Register services once app is initialized.
     * Should be called from main.kt after app setup.
     */
    fun initialize(
        windowManager: WindowManager,
        getSplitViewState: () -> SplitViewState?
    ) {
        this.windowManager = windowManager
        this.getSplitViewState = getSplitViewState
        this.isInitialized = true

        logger.info(LogCategory.SYSTEM, "CLI initialized with services")

        // Execute queued commands
        executeQueuedCommands()
    }

    /**
     * Queue a command for execution.
     * If app is ready, executes immediately.
     * Otherwise, queues for later execution.
     */
    fun queueCommand(command: CLICommand) {
        if (isInitialized) {
            scope.launch {
                executeCommand(command)
            }
        } else {
            commandQueue.offer(command)
            logger.debug(LogCategory.SYSTEM, "Queued command", mapOf("command" to command.toString()))
        }
    }

    /**
     * Mark terminal handler as ready and process queued terminal events.
     * Should be called from BossApp.kt after TerminalEventBus listener is set up.
     */
    fun markTerminalHandlerReady() {
        isTerminalHandlerReady = true
        logger.debug(LogCategory.SYSTEM, "Terminal handler marked as ready")

        // Process queued terminal events
        scope.launch {
            while (terminalQueue.isNotEmpty()) {
                val command = terminalQueue.poll()
                if (command != null) {
                    // Convert empty string sentinel back to null
                    val actualCommand = if (command.isEmpty()) null else command
                    logger.debug(LogCategory.SYSTEM, "Processing queued terminal command", mapOf(
                        "hasCommand" to (actualCommand != null)
                    ))
                    try {
                        handleOpenTerminal(actualCommand)
                    } catch (e: Exception) {
                        logger.error(LogCategory.SYSTEM, "Failed to process queued terminal event", error = e)
                    }
                }
            }
        }
    }

    /**
     * Mark file handler as ready and process queued file events.
     * Should be called from BossApp.kt after FileEventBus listener is set up.
     */
    fun markFileHandlerReady() {
        isFileHandlerReady = true
        logger.debug(LogCategory.SYSTEM, "File handler marked as ready")

        // Process queued file events
        scope.launch {
            while (fileQueue.isNotEmpty()) {
                val filePath = fileQueue.poll()
                if (filePath != null) {
                    logger.debug(LogCategory.SYSTEM, "Processing queued file", mapOf("path" to filePath))
                    try {
                        handleOpenFile(filePath)
                    } catch (e: Exception) {
                        logger.error(LogCategory.SYSTEM, "Failed to process queued file event", error = e)
                    }
                }
            }
        }
    }

    /**
     * Mark workspace handler as ready and process queued workspace loads.
     * Should be called from BossApp.kt after Last Session workspace is loaded.
     */
    fun markWorkspaceHandlerReady() {
        isWorkspaceHandlerReady = true
        logger.debug(LogCategory.SYSTEM, "Workspace handler marked as ready")

        // Process queued workspace loads
        scope.launch {
            while (workspaceQueue.isNotEmpty()) {
                val configPath = workspaceQueue.poll()
                if (configPath != null) {
                    logger.debug(LogCategory.SYSTEM, "Processing queued workspace", mapOf("path" to configPath))
                    try {
                        handleLoadWorkspace(configPath)
                    } catch (e: Exception) {
                        logger.error(LogCategory.SYSTEM, "Failed to process queued workspace", error = e)
                    }
                }
            }
        }
    }

    private fun executeQueuedCommands() {
        scope.launch {
            // Process command queue
            while (commandQueue.isNotEmpty()) {
                val command = commandQueue.poll()
                if (command != null) {
                    executeCommand(command)
                }
            }

            // Note: Workspace queue is now processed by markWorkspaceHandlerReady()
            // This ensures workspaces load AFTER Last Session, not during app initialization
        }
    }

    private suspend fun executeCommand(command: CLICommand) {
        try {
            logger.debug(LogCategory.SYSTEM, "Executing command", mapOf("command" to command.toString()))

            when (command) {
                is CLICommand.OpenUrl -> handleOpenUrl(command.url)
                is CLICommand.LoadWorkspace -> handleLoadWorkspace(command.configPath)
                is CLICommand.OpenFile -> {
                    if (isFileHandlerReady) {
                        // File handler ready - execute immediately
                        logger.debug(LogCategory.SYSTEM, "File handler ready, executing immediately", mapOf("path" to command.filePath))
                        handleOpenFile(command.filePath)
                    } else {
                        // File handler not ready - queue for later (cold start)
                        logger.debug(LogCategory.SYSTEM, "File handler not ready, queueing file", mapOf("path" to command.filePath))
                        fileQueue.add(command.filePath)
                    }
                }
                is CLICommand.OpenFolder -> handleOpenFolder(command.folderPath)
                is CLICommand.OpenTerminal -> {
                    val queuedCommand = command.command ?: ""  // Use empty string as sentinel for null

                    if (isTerminalHandlerReady) {
                        // Terminal handler ready - execute immediately
                        logger.debug(LogCategory.SYSTEM, "Terminal handler ready, executing immediately", mapOf(
                            "hasCommand" to queuedCommand.isNotEmpty()
                        ))
                        val actualCommand = if (queuedCommand.isEmpty()) null else queuedCommand
                        handleOpenTerminal(actualCommand)
                    } else {
                        // Terminal handler not ready - queue for later (cold start)
                        logger.debug(LogCategory.SYSTEM, "Terminal handler not ready, queueing", mapOf(
                            "hasCommand" to queuedCommand.isNotEmpty()
                        ))
                        terminalQueue.add(queuedCommand)
                    }
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
            logger.warn(LogCategory.SYSTEM, "Invalid URL", mapOf("url" to url))
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
     */
    private suspend fun handleLoadWorkspace(configPath: String) {
        // Validate file exists
        val file = File(configPath).absoluteFile
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
        if (!isWorkspaceHandlerReady) {
            logger.debug(LogCategory.SYSTEM, "Workspace handler not ready, queueing", mapOf("path" to file.absolutePath))
            workspaceQueue.add(file.absolutePath)
            return
        }

        // Get focused window ID for multi-window support
        val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
        if (focusedWindowId == null) {
            logger.warn(LogCategory.SYSTEM, "No window focused, cannot load workspace", mapOf("path" to file.absolutePath))
            return
        }

        // Emit workspace load event - BossApp will handle the actual loading
        // This is much simpler than trying to access splitViewState from CLI layer
        ai.rever.boss.components.events.WorkspaceEventBus.loadWorkspace(file.absolutePath, sourceWindowId = focusedWindowId)
        logger.debug(LogCategory.SYSTEM, "Emitted workspace load event", mapOf(
            "path" to file.absolutePath,
            "windowId" to focusedWindowId
        ))
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

        // Security validation
        if (!CLISecurityValidator.isValidPath(file.absolutePath)) {
            logger.warn(LogCategory.SYSTEM, "Invalid file path (security check failed)", mapOf("path" to file.absolutePath))
            return
        }

        // Get focused window ID for multi-window support
        val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
        if (focusedWindowId == null) {
            logger.warn(LogCategory.SYSTEM, "No window focused, cannot open file", mapOf("path" to file.absolutePath))
            return
        }

        // Track file processing (prevents New Tab Dialog race condition)
        ai.rever.boss.services.FileHandlerService.incrementProcessing()

        // Emit file open event via FileEventBus
        // The active window's BossApp will listen and create the editor tab
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FileEventBus.openFile(file.absolutePath, sourceWindowId = focusedWindowId)
                logger.debug(LogCategory.SYSTEM, "Emitted file open event", mapOf(
                    "path" to file.absolutePath,
                    "windowId" to focusedWindowId
                ))

                // CRITICAL: Wait for file tab to actually be created before decrementing
                delay(500)
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to emit file event", error = e)
            } finally {
                // Always decrement, even on error
                ai.rever.boss.services.FileHandlerService.decrementProcessing()
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
            // Get focused window for multi-window support
            val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
            val windowProjectState = focusedWindowId?.let { ai.rever.boss.window.WindowProjectStateRegistry.get(it) }

            val project = Project(
                name = folder.name.extractFileName(),
                path = folder.absolutePath,
                lastOpened = System.currentTimeMillis()
            )

            if (windowProjectState != null) {
                windowProjectState.selectProject(project)
            } else {
                // Fall back to just updating recent projects if no window state available
                ai.rever.boss.components.plugin.panels.left_top.ProjectState.updateRecentProjects(project)
            }
            logger.debug(LogCategory.SYSTEM, "Folder opened in codebase plugin", mapOf("path" to folder.absolutePath))
        }
    }

    /**
     * Opens terminal tab, optionally with command.
     *
     * Direct emit only - queueing is handled in executeCommand().
     * This is called from markTerminalHandlerReady() after handler is ready.
     */
    private suspend fun handleOpenTerminal(command: String?) {
        // Validate command for security if provided
        if (command != null && !CLISecurityValidator.isValidCommand(command)) {
            logger.warn(LogCategory.SYSTEM, "Invalid terminal command (security check failed)")
            return
        }

        // Get focused window ID for multi-window support
        val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
        if (focusedWindowId == null) {
            logger.warn(LogCategory.SYSTEM, "No window focused, cannot open terminal")
            return
        }

        // Track terminal processing (prevents New Tab Dialog race condition)
        ai.rever.boss.services.TerminalHandlerService.incrementProcessing()

        // Emit terminal open event via TerminalEventBus
        // The active window's BossApp will listen and create the terminal tab
        CoroutineScope(Dispatchers.Main).launch {
            try {
                TerminalEventBus.openTerminal(command, sourceWindowId = focusedWindowId)
                logger.debug(LogCategory.SYSTEM, "Emitted terminal open event", mapOf(
                    "hasCommand" to (command != null),
                    "windowId" to focusedWindowId
                ))

                // CRITICAL: Wait for terminal tab to actually be created before decrementing
                delay(500)
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to emit terminal event", error = e)
            } finally {
                // Always decrement, even on error
                ai.rever.boss.services.TerminalHandlerService.decrementProcessing()
            }
        }
    }
}

/**
 * Sealed class representing CLI commands.
 */
sealed class CLICommand {
    data class OpenUrl(val url: String) : CLICommand()
    data class LoadWorkspace(val configPath: String) : CLICommand()
    data class OpenFile(val filePath: String) : CLICommand()
    data class OpenFolder(val folderPath: String) : CLICommand()
    data class OpenTerminal(val command: String?) : CLICommand()
}
