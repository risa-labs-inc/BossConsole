package ai.rever.boss.utils

import ai.rever.boss.cli.CLISecurityValidator
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

private const val BOSS_SCHEME = "boss://"

/**
 * The `boss://` hosts routed by [DeepLinkHandler.processDeepLink].
 *
 * **Every** host here ultimately acts on a window, and all of them resolve it
 * with [WindowFocusManager.resolveActionableWindowId]; the flag records only
 * *where* that happens.
 *
 * @property host the exact host that selects this route. Matching is never
 *   prefix-based: a future `boss://plugins` or `boss://filesystem` must fall
 *   through to the auth/other flow instead of being mis-parsed by the shorter
 *   `plugin`/`file` route.
 * @property resolvesWindowAtDispatch true when the handler is handed a window
 *   resolved once by [DeepLinkHandler.processDeepLink], which is what stops
 *   `plugin`, `folder` and `split` from drifting apart again. False for hosts
 *   that queue a CLI command instead: those resolve the window further
 *   downstream — `url` in `URLHandlerService.handleURLInternal`, the rest in
 *   `CLICommandHandler` — through the same lookup, because a resolution that
 *   happened at dispatch would be stale by the time the queue drains (a queued
 *   command can wait for a cold start to finish).
 */
internal enum class DeepLinkHost(
    val host: String,
    val resolvesWindowAtDispatch: Boolean,
) {
    URL("url", resolvesWindowAtDispatch = false),
    WORKSPACE("workspace", resolvesWindowAtDispatch = false),
    FILE("file", resolvesWindowAtDispatch = false),
    TERMINAL("terminal", resolvesWindowAtDispatch = false),
    FOLDER("folder", resolvesWindowAtDispatch = true),
    PLUGIN("plugin", resolvesWindowAtDispatch = true),
    SPLIT("split", resolvesWindowAtDispatch = true),
}

/**
 * Extracts the host of a `boss://` URI, or null when [uri] is not a boss link
 * or names no host. The host ends at the first `/`, `?` or `#`, and is
 * lower-cased because URI hosts are case-insensitive.
 */
internal fun deepLinkHostOf(uri: String): String? {
    if (!uri.startsWith(BOSS_SCHEME, ignoreCase = true)) return null
    val remainder = uri.substring(BOSS_SCHEME.length)
    val hostEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val host = if (hostEnd >= 0) remainder.substring(0, hostEnd) else remainder
    return host.lowercase().ifEmpty { null }
}

private val deepLinkHostsByName: Map<String, DeepLinkHost> = DeepLinkHost.entries.associateBy { it.host }

/**
 * Resolves the route for [uri] by exact host, or null when no route claims it
 * (auth callbacks and any host BOSS does not handle yet).
 */
internal fun routedDeepLinkHost(uri: String): DeepLinkHost? = deepLinkHostOf(uri)?.let { deepLinkHostsByName[it] }

/**
 * The `boss://file` link that opens [path].
 *
 * Every way the OS can ask BOSS to open a file - the macOS open-file
 * AppleEvent, a path in `argv` on Windows and Linux, a path forwarded to an
 * already-running instance - is turned into this one link, so all of them share
 * the single validated, window-resolving, cold-start-queueing path that
 * `boss://file` already had. The alternative was three more callers of
 * `FileEventBus` each having to remember the `FileHandlerService` counter that
 * stops the New Tab dialog destroying the tab being created.
 *
 * `URLEncoder` encodes a space as `+` and [DeepLinkHandler]'s decode side uses
 * `URLDecoder`, which turns `+` back into a space, so the round trip is exact.
 */
internal fun fileDeepLinkFor(path: String): String = BOSS_SCHEME + "file?path=" + URLEncoder.encode(path, "UTF-8")

/**
 * The `boss://folder` link that opens [path] in the codebase panel.
 *
 * The counterpart of [fileDeepLinkFor] for a directory. Dropping a project folder
 * on the app, or double-clicking one with BOSS as its handler, previously produced
 * no link at all because the argv scan recognised only regular files.
 */
internal fun folderDeepLinkFor(path: String): String = BOSS_SCHEME + "folder?path=" + URLEncoder.encode(path, "UTF-8")

/**
 * The window a [host]'s handler should act on: resolved through
 * [resolveWindowId] for hosts that resolve at dispatch, null for hosts that
 * resolve downstream when their queued command runs.
 */
internal fun targetWindowIdFor(
    host: DeepLinkHost,
    resolveWindowId: () -> String?,
): String? = if (host.resolvesWindowAtDispatch) resolveWindowId() else null

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
        setupOpenFileHandler()
    }

    /**
     * Registers the OS "open these files with BOSS" handler.
     *
     * Nothing registered one before, so BOSS declared `CFBundleDocumentTypes` in
     * its Info.plist, appeared in Finder's Open With menu, launched when a file
     * was double-clicked - and then did nothing at all with the file. macOS
     * delivers a file open as an AppleEvent that reaches the JDK's
     * `_AppEventHandler` and, with no handler set, is discarded once the queue
     * is drained.
     *
     * Registered for macOS and the default branch, not Windows: Windows never
     * sends this event and passes paths in `argv` instead, which `main.kt`
     * handles. Calling it there is harmless but would claim support BOSS does
     * not get.
     */
    private fun setupOpenFileHandler() {
        if (isWindows || !Desktop.isDesktopSupported()) return
        try {
            Desktop.getDesktop().setOpenFileHandler { event ->
                val files = event.files ?: emptyList()
                logger.info(
                    LogCategory.FILE,
                    "Received open-file request from the OS",
                    mapOf("count" to files.size),
                )
                // One deep link per file, because that is what the rest of the
                // pipeline speaks: `boss://file` opens exactly one path, and
                // selecting several files in Finder and hitting Enter is a
                // single event carrying all of them.
                files.forEach { file ->
                    processDeepLink(fileDeepLinkFor(file.absolutePath), DeepLinkOrigin.EXTERNAL)
                }
            }
            logger.info(LogCategory.SYSTEM, "OS open-file handler registered successfully")
        } catch (e: UnsupportedOperationException) {
            // Documented outcome on a platform whose Desktop has no APP_OPEN_FILE
            // action. Not an error: it means files arrive some other way (argv).
            logger.debug(
                LogCategory.SYSTEM,
                "Desktop.setOpenFileHandler not supported on this platform",
                mapOf("reason" to (e.message ?: "unsupported")),
            )
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to set up OS open-file handler", error = e)
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
                        // Handle boss:// deep links for auth. The flow carries
                        // OS-delivered links only, and its collectors re-enter
                        // through processDeepLink(uri), which is
                        // DeepLinkOrigin.EXTERNAL — the correct origin for
                        // everything the OS hands over.
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
            // Called unconditionally: registerProtocol() is idempotent and inspects the
            // actual shell\open\command value, while isProtocolRegistered() only reports
            // root-key presence. Gating on the latter meant a partial registration (root key
            // present, command value missing — performRegistration does four independent
            // `reg add`s and tolerates failures) was reported as "already registered" and
            // never repaired, leaving boss:// broken for that user on every launch. Costs one
            // extra `reg query` per Windows start in the already-correct case, bounded at 5s.
            WindowsProtocolHandler.registerProtocol()

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
            } catch (e: UnsupportedOperationException) {
                // Documented outcome on Linux: the JDK's X11 Desktop peer supports no
                // APP_OPEN_URI action (see processCommandLineArgs's KDoc), so this always
                // throws there. Not an error - argv already delivers these links, both at
                // cold start and forwarded to a running instance over the single-instance
                // channel - but logging it at ERROR read as a broken feature on every Linux
                // launch (BossConsole#410). Same pattern setupOpenFileHandler already uses
                // for the equivalent APP_OPEN_FILE gap.
                logger.debug(
                    LogCategory.SYSTEM,
                    "Desktop.setOpenURIHandler not supported on this platform",
                    mapOf("reason" to (e.message ?: "unsupported")),
                )
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to set up deep link handler", error = e)
            }
        }
    }

    /**
     * Processes links and file paths the OS asked BOSS to open through `argv`.
     *
     * **No longer Windows-only, and that gate was a real gap.** It ran under
     * `if (isWindows)` because Windows delivers a protocol URL in `argv`. So does
     * Linux: the generated `boss.desktop` uses `Exec=<app> %u`, and the JDK's X11
     * Desktop peer supports no `APP_OPEN_URI` action, so `setOpenURIHandler` is
     * never called there. Between the two, a Linux user who set BOSS as their
     * default browser had every cold-start link silently dropped - argv held it,
     * nothing read argv, and the URI handler that would have read it does not
     * exist on that platform.
     *
     * macOS is unaffected either way: it delivers URLs and files as AppleEvents,
     * so in practice these args hold neither, and running this costs one scan of
     * an argv with nothing in it to find.
     *
     * Subsumes `WindowsProtocolHandler.extractDeepLinkFromArgs`, which was
     * `args.firstOrNull { it.startsWith("boss://") }` - the same answer
     * [OsOpenArguments] gives for that case, plus http/https and file paths, and
     * all of them rather than the first.
     */
    fun processCommandLineArgs(args: Array<String>) {
        OsOpenArguments.deepLinksFrom(args).forEach { link ->
            logger.info(
                LogCategory.SYSTEM,
                "Received deep link from command line",
                mapOf("uri" to LogSanitizer.maskUriParams(link)),
            )
            // A link in this process's argv is how a registered protocol handler
            // or a file association delivers something somebody asked the OS to
            // open, so it is external regardless of who launched the process.
            processDeepLink(link, DeepLinkOrigin.EXTERNAL)
        }
    }

    /**
     * Processes a link of unstated origin, which is therefore
     * [DeepLinkOrigin.EXTERNAL]. This is the multiplatform entry point and the
     * one the OS-delivered [deepLinkFlow] re-enters through; callers that know
     * the request came from the operator use the overload below.
     */
    actual fun processDeepLink(uri: String) {
        processDeepLink(uri, DeepLinkOrigin.EXTERNAL)
    }

    /**
     * Processes a link whose [origin] the caller can vouch for.
     *
     * [origin] reaches the handlers that need it (currently `boss://terminal`)
     * because no later stage can tell an operator's request apart from one some
     * other program asked the OS to open.
     *
     * @return a [Deferred] resolving to whether the link was actually acted on,
     *   for the one route that can answer that question today
     *   ([DeepLinkHost.PLUGIN] with an `action`) — null for every other route,
     *   which stays fire-and-forget exactly as before. A caller with no use for
     *   the verdict (the OS-delivered flow, the CLI) can ignore the return value
     *   unchanged; [SingleInstanceManager] is the one caller that awaits it, so a
     *   forwarded action link no longer reports success it never delivered.
     */
    fun processDeepLink(
        uri: String,
        origin: DeepLinkOrigin,
    ): Deferred<Boolean>? {
        logger.info(
            LogCategory.SYSTEM,
            "Processing deep link",
            mapOf("uri" to LogSanitizer.maskUriParams(uri), "origin" to origin.name),
        )

        // Routes match the whole host, never a prefix, so an unknown longer host
        // (boss://plugins, boss://filesystem) reaches the default flow instead of
        // being silently mis-parsed by a shorter route.
        val host = routedDeepLinkHost(uri)
        if (host == null) {
            // Default: emit to flow for auth/other handlers. Warn as well: the
            // flow conflates an identical value and BossAppWithAuth clears it
            // immediately after routing, so a link no route claims would
            // otherwise disappear without a trace.
            logger.warn(
                LogCategory.SYSTEM,
                "Deep link host is not routed, passing to the auth/other flow",
                mapOf("uri" to LogSanitizer.maskUriParams(uri)),
            )
            _deepLinkFlow.value = uri
            return null
        }

        // Single resolution point for every host that acts on a window here.
        // Uses the registration/focus-gain-backed lookup, not focusedWindowFlow
        // alone — an MCP-driven or CLI caller typically has OS focus itself (not
        // BOSS), so focusedWindowFlow can still be null even though a usable
        // window is plainly registered. Resolving here instead of inside each
        // handler is what keeps the window-targeting links from diverging again.
        // Safe on this thread: resolveActionableWindowId reads volatile state.
        return dispatch(host, uri, targetWindowIdFor(host) { WindowFocusManager.resolveActionableWindowId() }, origin)
    }

    /**
     * Routes a matched host to its handler. Handlers never resolve the target
     * window themselves — they receive whatever [processDeepLink] resolved, which
     * is null only for hosts that do not target a window.
     */
    private fun dispatch(
        host: DeepLinkHost,
        uri: String,
        targetWindowId: String?,
        origin: DeepLinkOrigin,
    ): Deferred<Boolean>? {
        when (host) {
            DeepLinkHost.URL -> handleUrlLink(uri)
            DeepLinkHost.WORKSPACE -> handleWorkspaceLink(uri)
            DeepLinkHost.FILE -> handleFileLink(uri)
            DeepLinkHost.TERMINAL -> handleTerminalLink(uri, origin)
            DeepLinkHost.FOLDER -> handleFolderLink(uri, targetWindowId)
            DeepLinkHost.PLUGIN -> return handlePluginLink(uri, targetWindowId)
            DeepLinkHost.SPLIT -> handleSplitLink(uri, targetWindowId)
        }
        return null
    }

    actual fun clearDeepLink() {
        _deepLinkFlow.value = null
    }

    /**
     * Handle boss://terminal deep links
     * Examples:
     *   boss://terminal
     *   boss://terminal?command=ls%20-la
     *
     * [origin] travels with the command all the way to
     * [ai.rever.boss.cli.CLICommandHandler], which runs a command outright only
     * for [DeepLinkOrigin.OPERATOR_CLI] and asks the operator to confirm the
     * exact text otherwise. Opening a terminal with no command needs no such
     * distinction and behaves the same either way.
     */
    private fun handleTerminalLink(
        uri: String,
        origin: DeepLinkOrigin,
    ) {
        logger.debug(LogCategory.TERMINAL, "Handling terminal link", mapOf("origin" to origin.name))

        val params = parseQueryParams(uri)
        val command = params["command"]?.urlDecode()

        // Create a CLI command and queue it
        val cliCommand =
            ai.rever.boss.cli.CLICommand
                .OpenTerminal(command, origin)
        ai.rever.boss.cli.CLICommandHandler
            .getInstance()
            .queueCommand(cliCommand)

        logger.info(
            LogCategory.TERMINAL,
            "Terminal command queued",
            mapOf("hasCommand" to (command != null), "origin" to origin.name),
        )
    }

    /**
     * Handle boss://folder deep links
     * Examples:
     *   boss://folder?path=/Users/name/project
     *   boss://folder?path=/path&name=MyProject
     *
     * [targetWindowId] is already resolved by [processDeepLink]. The path is
     * validated on [Dispatchers.IO] and the state update runs on the UI thread.
     */
    private fun handleFolderLink(
        uri: String,
        targetWindowId: String?,
    ) {
        logger.debug(LogCategory.FILE, "Handling folder link")

        val params = parseQueryParams(uri)
        val path = params["path"]?.urlDecode()

        if (path == null) {
            logger.warn(LogCategory.FILE, "Missing 'path' parameter in folder deep link")
            return
        }

        scope.launch {
            // Never stat on the UI thread (docs/THREADING.md): the path is
            // untrusted external input and a stale network mount makes
            // exists()/isDirectory block for seconds.
            val folder = withContext(Dispatchers.IO) { resolveFolder(path) } ?: return@launch

            val name = (params["name"] ?: folder.name).extractFileName()
            val project =
                Project(
                    name = name,
                    path = folder.absolutePath,
                    lastOpened = System.currentTimeMillis(),
                )

            withContext(Dispatchers.Main) {
                // Update project state (use per-window state if available)
                val windowProjectState =
                    targetWindowId?.let {
                        ai.rever.boss.window.WindowProjectStateRegistry
                            .get(it)
                    }

                if (windowProjectState != null) {
                    windowProjectState.selectProject(project)
                } else {
                    // Fall back to just updating recent projects if no window state available
                    ProjectState.updateRecentProjects(project)
                }
                logger.info(LogCategory.FILE, "Folder opened in codebase", mapOf("path" to folder.absolutePath))

                // Emit panel open event to show the codebase panel
                if (targetWindowId == null) {
                    logger.warn(LogCategory.UI, "No usable window registered, cannot open codebase panel")
                } else {
                    PanelEventBus.openPanel(PanelIds.CODEBASE, sourceWindowId = targetWindowId)
                    logger.debug(
                        LogCategory.UI,
                        "Emitted codebase panel open event",
                        mapOf("windowId" to targetWindowId),
                    )
                }
            }
        }
    }

    /**
     * Validates a folder deep link's path off the UI thread, returning null (and
     * logging why) when it must not be opened. Applies the same
     * [CLISecurityValidator.isValidPath] check as the CLI's folder command —
     * `boss://` is registered system-wide, so any web page can reach this.
     */
    private fun resolveFolder(path: String): File? {
        val folder = File(path).absoluteFile

        val rejection =
            when {
                !CLISecurityValidator.isValidPath(folder.absolutePath) -> "Invalid folder path (security check failed)"
                !folder.exists() -> "Folder does not exist"
                !folder.isDirectory -> "Path is not a directory"
                else -> null
            }

        if (rejection != null) {
            logger.warn(LogCategory.FILE, rejection, mapOf("path" to folder.absolutePath))
            return null
        }

        return folder
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
     *
     * [targetWindowId] is already resolved by [processDeepLink]; the panel event
     * and the action dispatch are emitted on the UI thread.
     *
     * @return for an action link, a [Deferred] resolving to
     *   [ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl.dispatch]'s
     *   own verdict (false for an unregistered handler id, a handler that
     *   declines the action, or one that throws — that function never lets an
     *   exception escape). Null for a panel-open link, which stays fire-and-forget. An action
     *   without a usable id is rejected with a false verdict.
     */
    private fun handlePluginLink(
        uri: String,
        targetWindowId: String?,
    ): Deferred<Boolean>? {
        logger.debug(LogCategory.UI, "Handling plugin link")

        val params = parseQueryParams(uri)
        val panelIdStr = params["id"]?.urlDecode()

        if (panelIdStr.isNullOrBlank()) {
            logger.warn(LogCategory.UI, "Missing 'id' parameter in plugin deep link")
            return if (params.containsKey("action")) CompletableDeferred(false) else null
        }

        // Action links dispatch to the plugin's DeepLinkActionHandler and do
        // NOT fall through to opening a panel — the two are distinct verbs
        // sharing the `plugin` scheme. Unhandled actions just log (registry
        // warns); external input, so handlers own validation.
        val action = params["action"]?.urlDecode()
        return if (action != null) {
            dispatchPluginAction(panelIdStr, action, params)
        } else {
            openPluginPanel(panelIdStr, targetWindowId)
            null
        }
    }

    /** Runs a `boss://plugin?id=…&action=…` link's action and hands back its real outcome. */
    private fun dispatchPluginAction(
        handlerId: String,
        action: String,
        params: Map<String, String>,
    ): Deferred<Boolean> {
        val actionParams =
            params
                .filterKeys { it != "id" && it != "action" }
                .mapValues { (_, value) -> value.urlDecode() }
        return scope.async(Dispatchers.Main) {
            ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl
                .dispatch(handlerId, action, actionParams)
        }
    }

    /** Opens a `boss://plugin?id=…` link's panel. Fire-and-forget: nothing awaits this today. */
    private fun openPluginPanel(
        panelIdStr: String,
        targetWindowId: String?,
    ) {
        if (targetWindowId == null) {
            logger.warn(
                LogCategory.UI,
                "No usable window registered, cannot open panel",
                mapOf("panelId" to panelIdStr),
            )
            return
        }

        scope.launch(Dispatchers.Main) {
            // Create PanelId with panelId string
            // The event handler in BossApp will look it up in the registry
            val panelId =
                PanelId(
                    panelId = panelIdStr,
                    defaultOrder = 0, // Will be ignored, registry has real value
                    pluginId = "ai.rever.boss", // Default plugin
                )
            PanelEventBus.openPanel(panelId, sourceWindowId = targetWindowId)
            logger.info(
                LogCategory.UI,
                "Emitted panel open event",
                mapOf("panelId" to panelIdStr, "windowId" to targetWindowId),
            )
        }
    }

    /**
     * Handle boss://split deep links — split BossConsole's main window.
     * Examples:
     *   boss://split                       (defaults to vertical)
     *   boss://split?orientation=vertical
     *   boss://split?orientation=horizontal
     *
     * [targetWindowId] is already resolved by [processDeepLink]; the split event
     * is triggered on the UI thread.
     */
    private fun handleSplitLink(
        uri: String,
        targetWindowId: String?,
    ) {
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

        if (targetWindowId == null) {
            logger.warn(LogCategory.UI, "No usable window registered, cannot split")
            return
        }

        scope.launch(Dispatchers.Main) {
            if (horizontal) {
                MenuActionsHandler.triggerSplitHorizontally(targetWindowId)
            } else {
                MenuActionsHandler.triggerSplitVertically(targetWindowId)
            }
            logger.info(
                LogCategory.UI,
                "Emitted split event",
                mapOf("windowId" to targetWindowId, "horizontal" to horizontal.toString()),
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
