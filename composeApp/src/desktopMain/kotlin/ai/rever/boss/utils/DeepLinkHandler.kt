package ai.rever.boss.utils

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.services.URLHandlerService
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLDecoder

actual object DeepLinkHandler {
    private val _deepLinkFlow = MutableStateFlow<String?>(null)
    actual val deepLinkFlow: StateFlow<String?> = _deepLinkFlow

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val logger = BossLogger.forComponent("DeepLinkHandler")

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

    init {
        setupPlatformHandler()
    }

    private fun setupPlatformHandler() {
        when {
            isMacOS -> setupMacOSHandler()
            isWindows -> setupWindowsHandler()
            else -> setupDefaultHandler()
        }
    }

    private fun setupMacOSHandler() {
        // macOS uses Desktop.setOpenURIHandler which works well
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().setOpenURIHandler { event ->
                    val uri = event.uri.toString()
                    logger.info(LogCategory.SYSTEM, "Received deep link (macOS)", mapOf("uri" to LogSanitizer.maskUriParams(uri)))

                    // Handle http/https URLs for default browser functionality
                    if (uri.startsWith("http://") || uri.startsWith("https://")) {
                        logger.debug(LogCategory.BROWSER, "Handling as HTTP(S) URL")
                        URLHandlerService.handleURL(uri)
                    } else {
                        // Handle boss:// deep links for auth
                        _deepLinkFlow.value = uri
                    }
                }
                logger.info(LogCategory.SYSTEM, "macOS deep link handler registered successfully")
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to set up macOS deep link handler", error = e)
            }
        }
    }

    private fun setupWindowsHandler() {
        // Windows requires registry setup and command line argument handling
        try {
            // Register protocol if not already registered
            if (!WindowsProtocolHandler.isProtocolRegistered()) {
                logger.info(LogCategory.SYSTEM, "Registering Windows protocol handler")
                WindowsProtocolHandler.registerProtocol()
            } else {
                logger.debug(LogCategory.SYSTEM, "Windows protocol handler already registered")
            }

            // On Windows, deep links come through command line args when the app is already running
            // For new instances, we need to check args in main()
            if (Desktop.isDesktopSupported()) {
                // This might not work on all Windows versions, but try it
                try {
                    Desktop.getDesktop().setOpenURIHandler { event ->
                        val uri = event.uri.toString()
                        logger.info(
                            LogCategory.SYSTEM,
                            "Received deep link (Windows via Desktop)",
                            mapOf("uri" to LogSanitizer.maskUriParams(uri)),
                        )

                        // Handle http/https URLs for default browser functionality
                        if (uri.startsWith("http://") || uri.startsWith("https://")) {
                            logger.debug(LogCategory.BROWSER, "Handling as HTTP(S) URL")
                            URLHandlerService.handleURL(uri)
                        } else {
                            // Handle boss:// deep links for auth
                            _deepLinkFlow.value = uri
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Desktop.setOpenURIHandler not supported on Windows", error = e)
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to set up Windows deep link handler", error = e)
        }
    }

    private fun setupDefaultHandler() {
        // Linux and other platforms
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().setOpenURIHandler { event ->
                    val uri = event.uri.toString()
                    logger.info(LogCategory.SYSTEM, "Received deep link", mapOf("uri" to LogSanitizer.maskUriParams(uri)))

                    // Handle http/https URLs for default browser functionality
                    if (uri.startsWith("http://") || uri.startsWith("https://")) {
                        logger.debug(LogCategory.BROWSER, "Handling as HTTP(S) URL")
                        URLHandlerService.handleURL(uri)
                    } else {
                        // Handle boss:// deep links for auth
                        _deepLinkFlow.value = uri
                    }
                }
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to set up deep link handler", error = e)
            }
        }
    }

    /**
     * Process command line arguments for deep links (needed for Windows)
     */
    fun processCommandLineArgs(args: Array<String>) {
        if (isWindows) {
            WindowsProtocolHandler.extractDeepLinkFromArgs(args)?.let { url ->
                logger.info(LogCategory.SYSTEM, "Received deep link from command line", mapOf("uri" to LogSanitizer.maskUriParams(url)))
                processDeepLink(url)
            }
        }
    }

    actual fun processDeepLink(uri: String) {
        logger.info(LogCategory.SYSTEM, "Processing deep link", mapOf("uri" to LogSanitizer.maskUriParams(uri)))

        when {
            uri.startsWith("boss://url") -> {
                handleUrlLink(uri)
            }

            uri.startsWith("boss://workspace") -> {
                handleWorkspaceLink(uri)
            }

            uri.startsWith("boss://file") -> {
                handleFileLink(uri)
            }

            uri.startsWith("boss://terminal") -> {
                handleTerminalLink(uri)
            }

            uri.startsWith("boss://folder") -> {
                handleFolderLink(uri)
            }

            uri.startsWith("boss://plugin") -> {
                handlePluginLink(uri)
            }

            uri.startsWith("boss://split") -> {
                handleSplitLink(uri)
            }

            else -> {
                // Default: emit to flow for auth/other handlers
                _deepLinkFlow.value = uri
            }
        }
    }

    actual fun clearDeepLink() {
        _deepLinkFlow.value = null
    }

    /**
     * Handle boss://terminal deep links
     * Examples:
     *   boss://terminal
     *   boss://terminal?command=ls%20-la
     */
    private fun handleTerminalLink(uri: String) {
        logger.debug(LogCategory.TERMINAL, "Handling terminal link")

        val params = parseQueryParams(uri)
        val command = params["command"]?.urlDecode()

        // Create a CLI command and queue it
        val cliCommand =
            ai.rever.boss.cli.CLICommand
                .OpenTerminal(command)
        ai.rever.boss.cli.CLICommandHandler
            .getInstance()
            .queueCommand(cliCommand)

        logger.info(LogCategory.TERMINAL, "Terminal command queued", mapOf("hasCommand" to (command != null)))
    }

    /**
     * Handle boss://folder deep links
     * Examples:
     *   boss://folder?path=/Users/name/project
     *   boss://folder?path=/path&name=MyProject
     */
    private fun handleFolderLink(uri: String) {
        logger.debug(LogCategory.FILE, "Handling folder link")

        val params = parseQueryParams(uri)
        val path = params["path"]?.urlDecode()

        if (path == null) {
            logger.warn(LogCategory.FILE, "Missing 'path' parameter in folder deep link")
            return
        }

        val folder = File(path).absoluteFile

        if (!folder.exists()) {
            logger.warn(LogCategory.FILE, "Folder does not exist", mapOf("path" to folder.absolutePath))
            return
        }

        if (!folder.isDirectory) {
            logger.warn(LogCategory.FILE, "Path is not a directory", mapOf("path" to folder.absolutePath))
            return
        }

        val name = (params["name"] ?: folder.name).extractFileName()

        // Update project state (use per-window state if available)
        scope.launch(Dispatchers.Main) {
            val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
            val windowProjectState =
                focusedWindowId?.let {
                    ai.rever.boss.window.WindowProjectStateRegistry
                        .get(it)
                }

            val project =
                Project(
                    name = name,
                    path = folder.absolutePath,
                    lastOpened = System.currentTimeMillis(),
                )

            if (windowProjectState != null) {
                windowProjectState.selectProject(project)
            } else {
                // Fall back to just updating recent projects if no window state available
                ProjectState.updateRecentProjects(project)
            }
            logger.info(LogCategory.FILE, "Folder opened in codebase", mapOf("path" to folder.absolutePath))

            // Emit panel open event to show the codebase panel
            if (focusedWindowId == null) {
                logger.warn(LogCategory.UI, "No window focused, cannot open codebase panel")
                return@launch
            }
            PanelEventBus.openPanel(PanelIds.CODEBASE, sourceWindowId = focusedWindowId)
            logger.debug(LogCategory.UI, "Emitted codebase panel open event", mapOf("windowId" to focusedWindowId))
        }
    }

    /**
     * Handle boss://plugin deep links
     * Opens any panel by its panel ID, or — with an `action` parameter —
     * routes to the plugin's registered DeepLinkActionHandler.
     * Examples:
     *   boss://plugin?id=bookmarks
     *   boss://plugin?id=terminal
     *   boss://plugin?id=secret-manager
     *   boss://plugin?id=my.plugin&action=sync&scope=all
     */
    private fun handlePluginLink(uri: String) {
        logger.debug(LogCategory.UI, "Handling plugin link")

        val params = parseQueryParams(uri)
        val panelIdStr = params["id"]?.urlDecode()

        if (panelIdStr == null) {
            logger.warn(LogCategory.UI, "Missing 'id' parameter in plugin deep link")
            return
        }

        // Action links dispatch to the plugin's DeepLinkActionHandler and do
        // NOT fall through to opening a panel — the two are distinct verbs
        // sharing the `plugin` scheme. Unhandled actions just log (registry
        // warns); external input, so handlers own validation.
        val action = params["action"]?.urlDecode()
        if (action != null) {
            val actionParams =
                params
                    .filterKeys { it != "id" && it != "action" }
                    .mapValues { (_, value) -> value.urlDecode() }
            scope.launch(Dispatchers.Main) {
                ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl
                    .dispatch(panelIdStr, action, actionParams)
            }
            return
        }

        // Emit panel open event
        scope.launch(Dispatchers.Main) {
            // Create PanelId with panelId string
            // The event handler in BossApp will look it up in the registry
            val panelId =
                PanelId(
                    panelId = panelIdStr,
                    defaultOrder = 0, // Will be ignored, registry has real value
                    pluginId = "ai.rever.boss", // Default plugin
                )

            val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
            if (focusedWindowId == null) {
                logger.warn(LogCategory.UI, "No window focused, cannot open panel", mapOf("panelId" to panelIdStr))
                return@launch
            }
            PanelEventBus.openPanel(panelId, sourceWindowId = focusedWindowId)
            logger.info(LogCategory.UI, "Emitted panel open event", mapOf("panelId" to panelIdStr, "windowId" to focusedWindowId))
        }
    }

    /**
     * Handle boss://split deep links — split BossConsole's main window.
     * Examples:
     *   boss://split                       (defaults to vertical)
     *   boss://split?orientation=vertical
     *   boss://split?orientation=horizontal
     */
    private fun handleSplitLink(uri: String) {
        logger.debug(LogCategory.UI, "Handling split link")

        val params = parseQueryParams(uri)
        val requested = params["orientation"]?.urlDecode()?.lowercase()
        val horizontal =
            when (requested) {
                null, "vertical" -> {
                    false
                }

                "horizontal" -> {
                    true
                }

                else -> {
                    logger.warn(
                        LogCategory.UI,
                        "Unknown split orientation, defaulting to vertical",
                        mapOf("orientation" to requested),
                    )
                    false
                }
            }

        scope.launch(Dispatchers.Main) {
            // Use the registration/focus-gain-backed lookup, not focusedWindowFlow
            // alone — an MCP-driven caller typically has OS focus itself (not
            // BOSS), so focusedWindowFlow can still be null even though a usable
            // window is plainly registered.
            val focusedWindowId = WindowFocusManager.resolveActionableWindowId()
            if (focusedWindowId == null) {
                logger.warn(LogCategory.UI, "No window focused, cannot split")
                return@launch
            }

            if (horizontal) {
                MenuActionsHandler.triggerSplitHorizontally(focusedWindowId)
            } else {
                MenuActionsHandler.triggerSplitVertically(focusedWindowId)
            }
            logger.info(
                LogCategory.UI,
                "Emitted split event",
                mapOf("windowId" to focusedWindowId, "horizontal" to horizontal.toString()),
            )
        }
    }

    /**
     * Handle boss://url deep links
     * Examples:
     *   boss://url?url=https%3A%2F%2Fexample.com
     */
    private fun handleUrlLink(uri: String) {
        logger.debug(LogCategory.BROWSER, "Handling URL link")

        val params = parseQueryParams(uri)
        val url = params["url"]?.urlDecode()

        if (url == null) {
            logger.warn(LogCategory.BROWSER, "Missing 'url' parameter in URL deep link")
            return
        }

        // Queue command via CLI handler
        val cliCommand =
            ai.rever.boss.cli.CLICommand
                .OpenUrl(url)
        ai.rever.boss.cli.CLICommandHandler
            .getInstance()
            .queueCommand(cliCommand)

        logger.info(LogCategory.BROWSER, "URL command queued", mapOf("url" to url))
    }

    /**
     * Handle boss://workspace deep links
     * Examples:
     *   boss://workspace?path=/path/to/workspace.json
     */
    private fun handleWorkspaceLink(uri: String) {
        logger.debug(LogCategory.WORKSPACE, "Handling workspace link")

        val params = parseQueryParams(uri)
        val path = params["path"]?.urlDecode()

        if (path == null) {
            logger.warn(LogCategory.WORKSPACE, "Missing 'path' parameter in workspace deep link")
            return
        }

        // Queue command via CLI handler
        val cliCommand =
            ai.rever.boss.cli.CLICommand
                .LoadWorkspace(path)
        ai.rever.boss.cli.CLICommandHandler
            .getInstance()
            .queueCommand(cliCommand)

        logger.info(LogCategory.WORKSPACE, "Workspace command queued", mapOf("path" to path))
    }

    /**
     * Handle boss://file deep links
     * Examples:
     *   boss://file?path=/path/to/file.kt
     */
    private fun handleFileLink(uri: String) {
        logger.debug(LogCategory.FILE, "Handling file link")

        val params = parseQueryParams(uri)
        val path = params["path"]?.urlDecode()

        if (path == null) {
            logger.warn(LogCategory.FILE, "Missing 'path' parameter in file deep link")
            return
        }

        // Queue command via CLI handler
        val cliCommand =
            ai.rever.boss.cli.CLICommand
                .OpenFile(path)
        ai.rever.boss.cli.CLICommandHandler
            .getInstance()
            .queueCommand(cliCommand)

        logger.info(LogCategory.FILE, "File command queued", mapOf("path" to path))
    }

    /**
     * Parse query parameters from URL
     * Example: boss://terminal?command=ls&title=test -> {command: "ls", title: "test"}
     */
    private fun parseQueryParams(uri: String): Map<String, String> {
        val query = uri.substringAfter("?", "")
        if (query.isEmpty() || query == uri) return emptyMap()

        return query
            .split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
    }

    /**
     * URL decode a string
     */
    private fun String.urlDecode(): String =
        try {
            URLDecoder.decode(this, "UTF-8")
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error decoding URL", error = e)
            this
        }

    actual fun extractVerificationToken(uri: String): String? {
        // Extract token from URLs like: boss://auth/verify#access_token=xxx or boss://auth/verify?token=xxx
        return try {
            val url = URI(uri)

            // First try URL fragment (after #) - this is what Supabase sends
            val fragment = url.fragment
            if (fragment != null) {
                val params =
                    fragment.split("&").associate {
                        val parts = it.split("=", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }
                // Return access_token from Supabase success redirect
                params["access_token"]?.let { return it }
            }

            // Fallback: try query parameters (after ?) for manual token input
            val query = url.query
            if (query != null) {
                val params =
                    query.split("&").associate {
                        val parts = it.split("=", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }
                return params["token"]
            }

            null
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Error extracting verification token", error = e)
            null
        }
    }

    actual fun extractVerificationType(uri: String): String? {
        // Extract type from URLs like: boss://auth/verify#access_token=xxx&type=recovery
        return try {
            val url = URI(uri)

            // First try URL fragment (after #) - this is what Supabase sends
            val fragment = url.fragment
            if (fragment != null) {
                val params =
                    fragment.split("&").associate {
                        val parts = it.split("=", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }
                params["type"]?.let { return it }
            }

            // Fallback: try query parameters (after ?)
            val query = url.query
            if (query != null) {
                val params =
                    query.split("&").associate {
                        val parts = it.split("=", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }
                return params["type"]
            }

            null
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Error extracting verification type", error = e)
            null
        }
    }
}
