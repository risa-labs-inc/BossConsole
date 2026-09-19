package ai.rever.boss.mcp

import ai.rever.boss.cli.CLISecurityValidator
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.TabConfig
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.awaitTabTypes
import ai.rever.boss.components.workspaces.isSpaceSlot
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.dashboard.DashboardStatsManager
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.window.WindowProjectStateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Host MCP tool provider exposing workspace and terminal lifecycle operations.
 *
 * Enables AI agents and automation clients to bootstrap workspaces and terminals
 * from a cold start (zero active workspaces or terminals) without manual UI actions.
 *
 * Tools exposed:
 * - list_workspaces / workspace_list
 * - open_workspace / workspace_open
 * - create_workspace / workspace_create
 * - open_terminal / terminal_open
 * - close_workspace / workspace_close
 *
 * Every tool that mutates on-screen state or runs a command declares
 * `readOnly = false`, so the mutating gate's fail-closed OR classifies it as
 * mutating even though none of the names above is in the host's known-mutating
 * list. No tool declares `requiredPermissions`: the registry is a loopback-only
 * server for the local machine's own agents, and the documented default posture
 * there is that an undeclared tool is permitted (see `mcpToolPermitted`).
 * Declaring a permission nobody holds would gate the tools behind a wall that
 * the rest of the host surface does not have; the real confinement is the
 * mutating gate plus the per-tool validation below.
 */
// One cohesive MCP tool provider; handlers stay beside their tool definitions.
@Suppress("TooManyFunctions", "LargeClass")
object WorkspaceMcpToolProvider : McpToolProvider {
    private val logger = BossLogger.forComponent("WorkspaceMcpToolProvider")

    /** Panel id of the terminal panel the bootstrap Space builds. */
    const val BOOTSTRAP_PANEL_ID = "panel-open-workspace"

    /** Id prefix of disposable workspaces minted by this tool; only these may be file-deleted. */
    internal const val DISPOSABLE_ID_PREFIX = "workspace-disposable-"

    /**
     * Bootstrap Spaces this tool created, by window then by canonical project path, so a second
     * open of the same project re-enters the Space instead of minting a second one for the same
     * directory. The manager's windowWorkspaces map covers saved Spaces, but an unsaved bootstrap
     * Space is not in the manager's list, so the tool remembers its own (matching rules
     * consolidated from #799).
     */
    internal val createdSpaces = ConcurrentHashMap<String, ConcurrentHashMap<String, LayoutWorkspace>>()

    override val providerId: String = "boss-workspace"

    /** Test hook / platform hook to create a window when no windows exist. */
    internal var windowCreator: (() -> String)? = null

    /** Test hook to override workspace file manager. */
    internal var fileManagerProvider: (() -> WorkspaceFileManager)? = null

    /** Test hook to resolve split view state for a window. */
    internal var splitViewStateResolver: ((String) -> SplitViewState?)? = null

    /** Test hook / UI hook to directly open a terminal tab and return authoritative tab info. */
    internal var terminalTabOpener: ((windowId: String, command: String?, cwd: String?) -> TerminalTabInfo?)? = null

    /** Timeout in milliseconds when awaiting compose readiness on cold start. */
    internal var splitViewWaitTimeoutMs: Long = 5000L

    private fun getFileManager(): WorkspaceFileManager = fileManagerProvider?.invoke() ?: WorkspaceFileManager()

    @Suppress("ReturnCount")
    internal suspend fun awaitSplitViewState(
        windowId: String,
        timeoutMillis: Long = splitViewWaitTimeoutMs,
    ): SplitViewState? {
        splitViewStateResolver?.invoke(windowId)?.let { return it }
        SplitViewStateRegistry.getState(windowId)?.let { return it }
        return withTimeoutOrNull(timeoutMillis) {
            SplitViewStateRegistry.states
                .filter { it.containsKey(windowId) }
                .first()[windowId]
        }
    }

    sealed class TargetWindowResolution {
        data class Success(
            val windowId: String,
            val isColdStart: Boolean = false,
        ) : TargetWindowResolution()

        data class Failure(
            val errorMessage: String,
        ) : TargetWindowResolution()
    }

    @Suppress("ReturnCount")
    internal suspend fun resolveTargetWindow(requestedWindowId: String?): TargetWindowResolution {
        // 1. Explicit windowId
        if (!requestedWindowId.isNullOrBlank()) {
            val isRegistered =
                splitViewStateResolver?.invoke(requestedWindowId) != null ||
                    SplitViewStateRegistry.isRegistered(requestedWindowId)
            return if (isRegistered) {
                TargetWindowResolution.Success(requestedWindowId)
            } else {
                val openIds =
                    SplitViewStateRegistry
                        .getAllStates()
                        .keys
                        .joinToString(", ")
                        .ifEmpty { "(none)" }
                TargetWindowResolution.Failure(
                    "Target window '$requestedWindowId' is not registered or has been closed. " +
                        "Open windows: $openIds",
                )
            }
        }

        // 2. Check registered window states
        val registeredStates = SplitViewStateRegistry.getAllStates()
        if (registeredStates.isEmpty()) {
            // True cold start: zero windows exist. Window creation is permitted.
            val creator =
                windowCreator
                    ?: return TargetWindowResolution.Failure(
                        "No active windows exist and no window creator is configured",
                    )
            // Window creation mutates Compose window state, so it runs on Main like every
            // other mutation in this provider; the MCP handler itself is not confined there.
            val newId = withContext(Dispatchers.Main) { creator.invoke() }
            return TargetWindowResolution.Success(newId, isColdStart = true)
        }

        if (registeredStates.size == 1) {
            return TargetWindowResolution.Success(registeredStates.keys.first())
        }

        val openIds = registeredStates.keys.joinToString(", ")
        return TargetWindowResolution.Failure(
            "Multiple windows are open ($openIds). Explicit 'windowId' is required to prevent focus interference.",
        )
    }

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createListWorkspacesTool("list_workspaces"),
            createListWorkspacesTool("workspace_list"),
            createOpenWorkspaceTool("open_workspace"),
            createOpenWorkspaceTool("workspace_open"),
            createCreateWorkspaceTool("create_workspace"),
            createCreateWorkspaceTool("workspace_create"),
            createOpenTerminalTool("open_terminal"),
            createOpenTerminalTool("terminal_open"),
            createCloseWorkspaceTool("close_workspace"),
            createCloseWorkspaceTool("workspace_close"),
        )

    private fun createListWorkspacesTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "List all existing workspaces, their project paths, and whether they are active or running.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "windowId": { "type": "string", "description": "Optional window ID to query active status against" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleListWorkspaces(args) },
        )

    private fun createOpenWorkspaceTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Open a workspace in a window: either 'path' to open a project directory as a new " +
                    "Space with its first terminal (bootstrap; re-opening a running path re-enters " +
                    "the Space instead of duplicating it), or an existing workspace by " +
                    "'workspaceId' / 'workspacePath', optionally created via 'name' / 'projectPath' " +
                    "with 'createIfAbsent'. Saved workspaces with terminal startup commands must be opened " +
                    "through the UI or have those commands submitted explicitly through open_terminal.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "Absolute path of an existing project directory to open as a new Space with its first terminal. A leading ~ is expanded; relative paths are refused." },
                        "workspaceId": { "type": "string", "description": "ID of the workspace to open" },
                        "workspacePath": { "type": "string", "description": "Path to a workspace JSON file inside the workspaces directory" },
                        "name": { "type": "string", "description": "Name if creating workspace" },
                        "projectPath": { "type": "string", "description": "Project root directory" },
                        "windowId": { "type": "string", "description": "Target window ID" },
                        "createIfAbsent": { "type": "boolean", "description": "Create workspace if not found" },
                        "openTerminal": { "type": "boolean", "description": "Automatically open a terminal tab (workspace-id modes; the path bootstrap already includes its first terminal)" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleOpenWorkspace(args) },
            readOnly = false,
        )

    private fun createCreateWorkspaceTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Create and persist a new workspace configuration (optionally disposable) without activating it.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "name": { "type": "string", "description": "Name for the workspace" },
                        "projectPath": { "type": "string", "description": "Project root directory" },
                        "isDisposable": {
                            "type": "boolean",
                            "description": "If true, creates a unique disposable workspace"
                        },
                        "openTerminal": {
                            "type": "boolean",
                            "description": "Configure an initial terminal tab in layout"
                        }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleCreateWorkspace(args) },
            readOnly = false,
        )

    private fun createOpenTerminalTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "Open a terminal tab in a workspace/window without requiring an existing terminal tab.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "windowId": { "type": "string", "description": "Target window ID" },
                        "workspaceId": { "type": "string", "description": "Target workspace ID" },
                        "workingDirectory": { "type": "string", "description": "Working directory for the terminal" },
                        "command": { "type": "string", "description": "Initial command to run in the terminal" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleOpenTerminal(args) },
            readOnly = false,
        )

    private fun createCloseWorkspaceTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Stop a workspace running in a window (clearing its tabs), and delete the file of a " +
                    "disposable workspace this tool created.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "workspaceId": { "type": "string", "description": "ID of workspace to close" },
                        "windowId": { "type": "string", "description": "Target window ID" }
                    },
                    "required": ["workspaceId"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleCloseWorkspace(args) },
            readOnly = false,
        )

    // =========================================================================
    // Handlers
    // =========================================================================

    @Suppress("LongMethod")
    private suspend fun handleListWorkspaces(args: McpToolArgs): McpToolResult {
        val windowId = args.string("windowId")
        val targetWindowId =
            if (!windowId.isNullOrBlank()) {
                windowId
            } else {
                // Read-only: a listing must not create a window. With exactly one registered
                // window it is the only possible target; otherwise report none.
                SplitViewStateRegistry.getAllStates().keys.singleOrNull()
            }
        val splitViewState =
            targetWindowId?.let {
                splitViewStateResolver?.invoke(it) ?: SplitViewStateRegistry.getState(it)
            }
        val activeWorkspaceId = splitViewState?.currentWorkspaceId

        val allWorkspaces = mutableMapOf<String, LayoutWorkspace>()

        // 1. Predefined templates
        PredefinedWorkspaces.allWorkspaces.forEach { ws ->
            allWorkspaces[ws.id] = ws
        }

        // 2. Saved workspaces on disk
        val fileManager = getFileManager()
        try {
            val files = fileManager.listWorkspaces()
            for (fileInfo in files) {
                val loaded = fileManager.loadWorkspace(fileInfo.fileName)
                if (loaded != null) {
                    allWorkspaces[loaded.id] = loaded
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.WORKSPACE, "Error listing workspace files", error = e)
        }

        // 3. Live workspaces across windows
        val allStates = SplitViewStateRegistry.getAllStates()
        val runningWorkspaceIds = allStates.values.mapNotNull { it.currentWorkspaceId }.toSet()

        val jsonArray =
            buildJsonArray {
                allWorkspaces.values.forEach { ws ->
                    val isActive = ws.id == activeWorkspaceId
                    val isRunning = runningWorkspaceIds.contains(ws.id)
                    val isTemplate = ws.id in PredefinedWorkspaces.allIds

                    add(
                        buildJsonObject {
                            put("id", ws.id)
                            put("name", ws.name)
                            if (ws.projectPath != null) {
                                put("projectPath", ws.projectPath)
                            }
                            put("description", ws.description)
                            put("isActive", isActive)
                            put("isRunning", isRunning)
                            put("isTemplate", isTemplate)
                        },
                    )
                }
            }

        val response =
            buildJsonObject {
                put("success", true)
                if (targetWindowId != null) {
                    put("activeWindowId", targetWindowId)
                }
                if (activeWorkspaceId != null) {
                    put("activeWorkspaceId", activeWorkspaceId)
                }
                put("workspaces", jsonArray)
            }

        return McpToolResult(response.toString())
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun handleOpenWorkspace(args: McpToolArgs): McpToolResult {
        val workspaceId = args.string("workspaceId")
        val workspacePath = args.string("workspacePath")
        val name = args.string("name")
        val projectPath = args.string("projectPath")
        if (workspaceId != null && !isSafeWorkspaceId(workspaceId)) {
            return McpToolResult("Invalid workspaceId: expected an identifier, not a path", isError = true)
        }
        val requestedWindowId = args.string("windowId")
        val createIfAbsent = args.boolean("createIfAbsent") ?: false
        val openTerminal = args.boolean("openTerminal") ?: false

        // workspacePath is READ (the file is deserialized), so it gets the open-target
        // validator: isValidPath would also refuse `..`, `$`, `&`, `;`, `|` and backtick,
        // which are ordinary filename characters.
        if (!workspacePath.isNullOrBlank() && !CLISecurityValidator.isValidOpenTargetPath(workspacePath)) {
            return McpToolResult("Invalid workspace path (security check failed)", isError = true)
        }
        // That check is the editor-open contract - any file the user picks. A Space file goes
        // further: it is parsed and APPLIED as the window's live layout, so the reachable set
        // is the workspace store, the same directory the workspaceId mode loads from and this
        // app itself writes. A workspacePath that resolves outside it - `..` traversal, an
        // absolute path elsewhere, a symlink pointing out - is refused before the file is
        // probed, read, or parsed. Fails closed (#896).
        var canonicalWorkspacePath: String? = null
        if (!workspacePath.isNullOrBlank()) {
            val containment =
                checkWorkspacePathContainment(
                    rawPath = workspacePath,
                    workspaceDirectory = getFileManager().getDefaultWorkspaceDirectory(),
                )
            if (containment.canonicalPath == null) {
                return McpToolResult(containment.error ?: "Invalid workspace path", isError = true)
            }
            canonicalWorkspacePath = containment.canonicalPath
        }
        // projectPath ends up as a terminal working directory, the same destination the
        // `path` mode serves, so it gets the same gate: absolute, security-checked, and an
        // existing directory.
        var canonicalProjectPath: String? = null
        if (!projectPath.isNullOrBlank()) {
            val projectCheck = checkProjectPath(projectPath)
            if (projectCheck.canonicalPath == null) {
                return McpToolResult(projectCheck.error ?: "Invalid project path", isError = true)
            }
            canonicalProjectPath = projectCheck.canonicalPath
        }

        val path = args.string("path")
        if (!path.isNullOrBlank()) {
            val otherSelectors =
                listOfNotNull(
                    workspaceId?.takeIf { it.isNotBlank() },
                    workspacePath?.takeIf { it.isNotBlank() },
                    name?.takeIf { it.isNotBlank() },
                )
            if (otherSelectors.isNotEmpty()) {
                return McpToolResult(
                    "Specify 'path' alone (bootstrap a project directory) or one of 'workspaceId' / " +
                        "'workspacePath' / 'name' (open or create a saved workspace), not both.",
                    isError = true,
                )
            }
            return openWorkspaceByPath(path, requestedWindowId)
        }

        val targetResolution = resolveTargetWindow(requestedWindowId)
        val targetWindowId =
            when (targetResolution) {
                is TargetWindowResolution.Success -> targetResolution.windowId
                is TargetWindowResolution.Failure -> return McpToolResult(targetResolution.errorMessage, isError = true)
            }

        val splitViewState = awaitSplitViewState(targetWindowId)

        // Locate or create workspace
        var workspace: LayoutWorkspace? = null
        var isShippedTemplate = false

        if (canonicalWorkspacePath != null) {
            val file = File(canonicalWorkspacePath)
            if (file.exists() && file.canRead()) {
                val content = withContext(Dispatchers.IO) { file.readText() }
                workspace = runCatching { WorkspaceSerializer.deserialize(content) }.getOrNull()
            } else if (!createIfAbsent) {
                return McpToolResult("Workspace file not found: $workspacePath", isError = true)
            }
        }

        if (workspace == null && !workspaceId.isNullOrBlank()) {
            // Check predefined templates
            workspace = PredefinedWorkspaces.allWorkspaces.firstOrNull { it.id == workspaceId }
            isShippedTemplate = workspace != null

            // Check saved workspaces
            if (workspace == null) {
                val fileManager = getFileManager()
                val fileName =
                    if (workspaceId.endsWith(".json")) {
                        workspaceId
                    } else {
                        WorkspaceFileManagerCommon.fileNameForId(workspaceId)
                    }
                workspace = fileManager.loadWorkspace(fileName)
            }
        }

        if (workspace == null) {
            if (createIfAbsent || !name.isNullOrBlank()) {
                val newId = workspaceId?.takeIf { it.isNotBlank() } ?: LayoutWorkspace.generateId()
                // Slot ids (the shipped layouts and the last-session autosave record) are
                // identities the watcher and startup restore key on; a file carrying one is
                // the legacy shape the merge cleans up, and it is silently dropped next launch.
                if (isSpaceSlot(newId)) {
                    return McpToolResult(
                        "'$newId' is a reserved slot (a shipped layout or the last-session " +
                            "autosave record), not a workspace id. Pass a different workspaceId.",
                        isError = true,
                    )
                }
                val wsName = name?.takeIf { it.isNotBlank() } ?: "Workspace $newId"
                val rootPath = canonicalProjectPath ?: DefaultWorkingDirectory.nominalPath()
                workspace = createDefaultWorkspace(newId, wsName, rootPath, openTerminal = openTerminal)
                // Persist
                getFileManager().saveWorkspace(workspace)
            } else {
                return McpToolResult(
                    "Workspace '$workspaceId' not found. Specify createIfAbsent=true to create it.",
                    isError = true,
                )
            }
        }

        // Persisted commands were not visible in this MCP invocation's approval arguments.
        // Require a separate open_terminal call so its command receives normal risk review.
        if (!isShippedTemplate && workspace.layout.hasInitialCommands()) {
            return McpToolResult(
                "Workspace contains terminal startup commands. Open it through the workspace UI, " +
                    "or remove the startup commands and invoke open_terminal with each command explicitly.",
                isError = true,
            )
        }

        // Idempotency: Reopening an existing workspace does not duplicate it or disturb unrelated windows
        if (splitViewState != null && splitViewState.currentWorkspaceId == workspace.id) {
            logger.debug(
                LogCategory.WORKSPACE,
                "Workspace already active in target window",
                mapOf("workspaceId" to workspace.id, "windowId" to targetWindowId),
            )
            return McpToolResult(
                buildJsonObject {
                    put("success", true)
                    put("workspaceId", workspace.id)
                    put("workspaceName", workspace.name)
                    put("projectPath", workspace.projectPath)
                    put("windowId", targetWindowId)
                    put("alreadyActive", true)
                }.toString(),
            )
        }

        // Apply through the same door the Space switcher uses: preserve the outgoing Space,
        // go through workspaceManager.loadWorkspace (the one door - it is what updates the
        // theme, currentWorkspace and windowWorkspaces), then apply. The window's
        // WorkspaceEventBus collector re-applies every load event aimed at it, so the
        // provider - which is itself the actor here - must not emit one; doing both would
        // apply the layout twice and tear down what the first apply just built.
        if (splitViewState != null) {
            switchWindowToSpace(
                splitViewState,
                WindowProjectStateRegistry.getOrCreate(targetWindowId),
                workspace,
            )
        }

        var terminalInfo: JsonObject? = null
        if (openTerminal) {
            terminalInfo = doOpenTerminal(targetWindowId, workspace.id, workspace.projectPath, command = null)
        }

        val resultObj =
            buildJsonObject {
                put("success", true)
                put("workspaceId", workspace.id)
                put("workspaceName", workspace.name)
                put("projectPath", workspace.projectPath)
                put("windowId", targetWindowId)
                if (terminalInfo != null) {
                    put("terminal", terminalInfo)
                }
            }

        return McpToolResult(resultObj.toString())
    }

    /**
     * Path-based bootstrap mode of open_workspace (consolidated from #799): open [rawPath] as a
     * Space in a window, creating its first terminal panel for the panel-scoped terminal tools
     * (`run_in_panel` and friends). Re-opening a path that is already running re-enters the
     * Space instead of minting a duplicate (see [matchExistingSpace]).
     */
    @Suppress("ReturnCount")
    private suspend fun openWorkspaceByPath(
        rawPath: String,
        requestedWindowId: String?,
    ): McpToolResult {
        val pathCheck = checkProjectPath(rawPath)
        val projectPath =
            pathCheck.canonicalPath
                ?: return McpToolResult(pathCheck.error ?: "Invalid path: $rawPath", isError = true)

        val targetResolution = resolveTargetWindow(requestedWindowId)
        val targetWindowId =
            when (targetResolution) {
                is TargetWindowResolution.Success -> targetResolution.windowId
                is TargetWindowResolution.Failure -> return McpToolResult(targetResolution.errorMessage, isError = true)
            }
        val splitViewState =
            awaitSplitViewState(targetWindowId)
                ?: return McpToolResult(
                    "Window '$targetWindowId' did not register its UI state in time; retry.",
                    isError = true,
                )
        // The remembered map is process-lifetime storage; a closed window's Spaces can never
        // be re-entered, so prune entries for windows that no longer exist before growing it.
        pruneClosedWindows()
        val runningIds = workspaceManager.windowWorkspaces.value[targetWindowId].orEmpty()
        val (space, reused) = resolveBootstrapSpace(targetWindowId, projectPath, runningIds)

        // Fast path: the window already shows this Space, so the live terminal is left alone.
        if (splitViewState.currentWorkspaceId == space.id) {
            return McpToolResult(
                buildPathResult(
                    reused = true,
                    windowId = targetWindowId,
                    state = splitViewState,
                    space = space,
                    projectPath = projectPath,
                ),
            )
        }

        // applyWorkspace awaits the tab types this layout needs (terminal among them), so the
        // check below is a verification of that wait, not a race against plugin registration.
        switchWindowToSpace(splitViewState, WindowProjectStateRegistry.getOrCreate(targetWindowId), space)

        if (!splitViewState.tabRegistry.isRegistered(TerminalTabType.typeId)) {
            return McpToolResult(
                "The Space is open, but the terminal tab type is not registered, so terminal tools have " +
                    "no panel to attach to. Check that the terminal plugin is installed and enabled, then retry.",
                isError = true,
            )
        }

        return McpToolResult(
            buildPathResult(
                reused = reused,
                windowId = targetWindowId,
                state = splitViewState,
                space = space,
                projectPath = projectPath,
            ),
        )
    }

    /**
     * The Space [projectPath] should run in [windowId]: an existing one worth re-entering (see
     * [matchExistingSpace]), or a fresh bootstrap Space remembered for future re-entry. Returns
     * the Space and whether it was re-entered rather than created.
     */
    private fun resolveBootstrapSpace(
        windowId: String,
        projectPath: String,
        runningIds: Set<String>,
    ): Pair<LayoutWorkspace, Boolean> {
        val existing =
            matchExistingSpace(
                remembered = createdSpaces[windowId]?.get(projectPath),
                savedSpaces = workspaceManager.workspaces.value,
                runningIdsInWindow = runningIds,
                projectPath = projectPath,
            )
        return Pair(
            existing ?: buildBootstrapSpace(projectPath).also { fresh ->
                createdSpaces.getOrPut(windowId) { ConcurrentHashMap() }[projectPath] = fresh
            },
            existing != null,
        )
    }

    /**
     * Preserve, load, apply: the same three steps the Space switcher takes, so re-entering a
     * previously running Space restores its preserved tree when the window holds one.
     */
    private suspend fun switchWindowToSpace(
        splitViewState: SplitViewState,
        windowProjectState: WindowProjectState,
        space: LayoutWorkspace,
    ) {
        withContext(Dispatchers.Main) {
            val currentWorkspace = workspaceManager.currentWorkspace.value
            if (currentWorkspace != null && currentWorkspace.id.isNotEmpty()) {
                splitViewState.preserveCurrentState(currentWorkspace.id, currentWorkspace.name)
            }
            workspaceManager.loadWorkspace(space)
            applyWorkspace(space, splitViewState, windowProjectState, restoreProject = true)
        }
    }

    /**
     * Drop the remembered bootstrap Spaces of windows that no longer exist. A window is alive
     * if the resolver sees it (tests, future UI hooks) or the registry knows it; the check is
     * the same resolution `resolveTargetWindow` uses for an explicit id.
     */
    private fun pruneClosedWindows() {
        createdSpaces.keys.toList().forEach { windowId ->
            val alive =
                splitViewStateResolver?.invoke(windowId) != null ||
                    SplitViewStateRegistry.isRegistered(windowId)
            if (!alive) {
                createdSpaces.remove(windowId)
            }
        }
    }

    /**
     * The JSON reply for path mode: status plus the ids a caller needs to aim tools at what
     * was opened.
     *
     * The panel id is read back off the LIVE tree, not the saved layout: applyWorkspace throws
     * the saved panel ids away and builds the layout into the panel it finds at "main", so the
     * saved id (BOOTSTRAP_PANEL_ID) does not exist on screen - returning it would hand a
     * caller an id `run_in_panel` cannot address.
     */
    private suspend fun buildPathResult(
        reused: Boolean,
        windowId: String,
        state: SplitViewState,
        space: LayoutWorkspace,
        projectPath: String,
    ): String {
        val livePanelId =
            withContext(Dispatchers.Main) { state.activePanelIdForWorkspace(space.id) } ?: "main"
        return buildJsonObject {
            put("success", true)
            put("status", if (reused) "reused" else "opened")
            put("workspaceId", space.id)
            put("workspaceName", space.name)
            put("projectPath", projectPath)
            put("windowId", windowId)
            put("panelId", livePanelId)
        }.toString()
    }

    @Suppress("ReturnCount")
    private suspend fun handleCreateWorkspace(args: McpToolArgs): McpToolResult {
        val name = args.string("name")
        val projectPath = args.string("projectPath")
        val isDisposable = args.boolean("isDisposable") ?: false
        val openTerminal = args.boolean("openTerminal") ?: false

        // Same gate open_workspace uses for a project path: absolute, security-checked, and an
        // existing directory - the value becomes a terminal working directory.
        var canonicalProjectPath: String? = null
        if (!projectPath.isNullOrBlank()) {
            val projectCheck = checkProjectPath(projectPath)
            if (projectCheck.canonicalPath == null) {
                return McpToolResult(projectCheck.error ?: "Invalid project path", isError = true)
            }
            canonicalProjectPath = projectCheck.canonicalPath
        }

        val id =
            if (isDisposable) {
                "$DISPOSABLE_ID_PREFIX${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}"
            } else {
                LayoutWorkspace.generateId()
            }

        val wsName =
            name?.takeIf { it.isNotBlank() }
                ?: if (isDisposable) "Disposable Workspace" else "New Workspace"

        val rootPath = canonicalProjectPath ?: DefaultWorkingDirectory.nominalPath()
        val workspace = createDefaultWorkspace(id, wsName, rootPath, openTerminal = openTerminal)

        // Persist through the provider's file manager - the same door the close path deletes
        // through, so a created Space and its deletion always agree on the file. The write is
        // awaited, so the returned filePath exists when this call returns.
        val filePath = getFileManager().saveWorkspace(workspace)
        if (filePath == null) {
            return McpToolResult(
                "Failed to persist the new workspace '$id'; nothing was saved.",
                isError = true,
            )
        }

        // The file is on disk, but the picker reads the manager's in-memory list - register the
        // Space there too. List-only on purpose: a second write through the manager's own file
        // manager could land in a different directory than this one.
        workspaceManager.registerWorkspace(workspace)

        val resultObj =
            buildJsonObject {
                put("success", true)
                put("workspaceId", id)
                put("workspaceName", wsName)
                put("projectPath", rootPath)
                put("filePath", filePath)
                put("isDisposable", isDisposable)
            }

        return McpToolResult(resultObj.toString())
    }

    /**
     * Opens a terminal tab and, when given, starts [command] in it.
     *
     * Confirmation model, stated because it differs from the deep-link door: `boss://terminal?command=`
     * is gated by `DeepLinkOrigin` (an EXTERNAL link is shown to the operator first), while an
     * MCP invocation is gated by the mutating gate this tool trips by name and by its
     * `readOnly = false` declaration - ASK by default, with the approval dialog the operator's
     * confirmation. A persisted "Always Allow" on `open_terminal` therefore runs later
     * invocations unconfirmed, so the grant is as strong as an unconfirmed deep link; the
     * command still passes [CLISecurityValidator.isValidCommand] (shape only) and the risk
     * evaluator (HIGH, CRITICAL for destructive patterns) on every call.
     */
    @Suppress("ReturnCount")
    private suspend fun handleOpenTerminal(args: McpToolArgs): McpToolResult {
        val requestedWindowId = args.string("windowId")
        val workspaceId = args.string("workspaceId")
        val workingDirectory = args.string("workingDirectory")
        val command = args.string("command")

        if (!command.isNullOrBlank() && !CLISecurityValidator.isValidCommand(command)) {
            return McpToolResult("Invalid command format (security check failed)", isError = true)
        }

        // Validate and canonicalize an explicit cwd without resolving it against the host cwd.
        var canonicalWorkingDirectory: String? = null
        if (!workingDirectory.isNullOrBlank()) {
            if (!CLISecurityValidator.isValidPath(workingDirectory)) {
                return McpToolResult(
                    "Invalid working directory path (security check failed)",
                    isError = true,
                )
            }
            val check = checkProjectPath(workingDirectory)
            if (check.canonicalPath == null) {
                return McpToolResult(check.error ?: "Invalid working directory", isError = true)
            }
            canonicalWorkingDirectory = check.canonicalPath
        }

        val targetResolution = resolveTargetWindow(requestedWindowId)
        val targetWindowId =
            when (targetResolution) {
                is TargetWindowResolution.Success -> {
                    targetResolution.windowId
                }

                is TargetWindowResolution.Failure -> {
                    return McpToolResult(targetResolution.errorMessage, isError = true)
                }
            }

        val terminalInfo =
            doOpenTerminal(targetWindowId, workspaceId, canonicalWorkingDirectory, command)
                ?: return McpToolResult(
                    "Failed to open terminal in window $targetWindowId",
                    isError = true,
                )

        return McpToolResult(terminalInfo.toString())
    }

    /**
     * Opens a terminal tab in [windowId] and returns the ids of the tab it mounted.
     *
     * The provider is itself the actor, so it opens the tab directly and does NOT also emit a
     * TerminalEventBus open event: every window runs a collector on that bus (see
     * BossAppEventBusEffects) that opens a terminal for each event aimed at it, so emitting
     * here would open a second tab and run the command twice. The bus stays the door for
     * EXTERNAL requesters (deep links, CLI); it is not the door for a tool that already did
     * the work. The session stat that collector recorded on the way is recorded here instead.
     *
     * The terminal tab type is awaited the same way applyWorkspace does: at a cold start -
     * exactly the situation this tool exists for - the terminal plugin may not have registered
     * its factory yet, and openTerminalInActivePanelNow drops a tab whose type has no factory.
     */
    @Suppress("ReturnCount")
    private suspend fun doOpenTerminal(
        windowId: String,
        workspaceId: String?,
        workingDirectory: String?,
        command: String?,
    ): JsonObject? {
        val effectiveCwd = workingDirectory ?: DefaultWorkingDirectory.nominalPath()

        val mountedTab =
            terminalTabOpener?.invoke(windowId, command, effectiveCwd) ?: run {
                val splitViewState = awaitSplitViewState(windowId) ?: return null
                withContext(Dispatchers.Main) {
                    splitViewState.tabRegistry.awaitTabTypes(setOf(TerminalTabType.typeId))
                    splitViewState.openTerminalInActivePanelNow(command, effectiveCwd)
                }
            } ?: return null

        DashboardStatsManager.recordTerminalSession()

        val tabId = mountedTab.id
        // openTerminalInActivePanelNow mints ids as "terminal-<timestamp>" (the only path this
        // tool uses in production); the terminal's addressing keys on the part after the prefix.
        val terminalId = tabId.removePrefix("terminal-")

        return buildJsonObject {
            put("success", true)
            put("tabId", tabId)
            put("terminalId", terminalId)
            put("windowId", windowId)
            if (workspaceId != null) {
                put("workspaceId", workspaceId)
            }
            put("workingDirectory", effectiveCwd)
            if (command != null) {
                put("command", command)
            }
            put("openedDirectly", true)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleCloseWorkspace(args: McpToolArgs): McpToolResult {
        val workspaceId = args.string("workspaceId")
        if (workspaceId.isNullOrBlank()) {
            return McpToolResult("workspaceId is required", isError = true)
        }

        if (!isSafeWorkspaceId(workspaceId)) {
            return McpToolResult("Invalid workspaceId: expected an identifier, not a path", isError = true)
        }
        val requestedWindowId = args.string("windowId")
        val targetWindowId =
            if (!requestedWindowId.isNullOrBlank()) {
                val targetResolution = resolveTargetWindow(requestedWindowId)
                when (targetResolution) {
                    is TargetWindowResolution.Success -> {
                        targetResolution.windowId
                    }

                    is TargetWindowResolution.Failure -> {
                        return McpToolResult(targetResolution.errorMessage, isError = true)
                    }
                }
            } else {
                // Read-only targeting, the same rule list_workspaces uses: closing a workspace
                // must never mint a window. With exactly one registered window it is the only
                // possible target; with none or several there is nothing safe to close in.
                SplitViewStateRegistry.getAllStates().keys.singleOrNull()
            }

        // Stop the Space where it is running: clears its tabs and drops any preserved copy,
        // the same effect closing it in the Space list has.
        var releasedHere = false
        if (targetWindowId != null) {
            val splitViewState = awaitSplitViewState(targetWindowId)
            if (splitViewState != null) {
                releasedHere = withContext(Dispatchers.Main) { splitViewState.closeWorkspace(workspaceId) }
            }
        }

        // Only delete the file of a disposable workspace this tool minted (prefix, not
        // substring): a user's saved Space whose name merely mentions "disposable" is not ours.
        var fileDeleted = false
        if (workspaceId.startsWith(DISPOSABLE_ID_PREFIX)) {
            val fileName =
                if (workspaceId.endsWith(".json")) {
                    workspaceId
                } else {
                    WorkspaceFileManagerCommon.fileNameForId(workspaceId)
                }
            fileDeleted = getFileManager().deleteWorkspace(fileName)
        }

        // Saying "success" when neither happened leaves the agent unable to tell "closed"
        // from "that id does not exist anywhere".
        if (!releasedHere && !fileDeleted) {
            return McpToolResult(
                "Workspace '$workspaceId' is not running in window '${targetWindowId ?: "(none)"}' " +
                    "and has no disposable file to delete; nothing was closed.",
                isError = true,
            )
        }

        val response =
            buildJsonObject {
                put("success", true)
                put("workspaceId", workspaceId)
                if (targetWindowId != null) {
                    put("windowId", targetWindowId)
                }
                put("releasedHere", releasedHere)
                put("fileDeleted", fileDeleted)
            }

        return McpToolResult(response.toString())
    }

    private fun createDefaultWorkspace(
        id: String,
        name: String,
        projectPath: String,
        openTerminal: Boolean,
    ): LayoutWorkspace {
        val tabs =
            if (openTerminal) {
                listOf(
                    TabConfig(
                        type = "terminal",
                        title = "Terminal",
                        workingDirectory = projectPath,
                    ),
                )
            } else {
                emptyList()
            }

        return LayoutWorkspace(
            id = id,
            name = name,
            description = "Workspace $name",
            layout =
                SinglePanel(
                    PanelConfig(
                        id = "panel-$id-1",
                        tabs = tabs,
                    ),
                ),
            projectPath = projectPath,
        )
    }
}

/**
 * Expands a leading `~` to the user's home directory, the way a shell would, so a path an
 * agent copy-pasted from a terminal works unchanged. Anything else passes through as-is.
 */
internal fun expandTilde(
    path: String,
    home: String? = System.getProperty("user.home"),
): String =
    when {
        path == "~" -> home ?: path
        path.startsWith("~/") -> home?.let { it + path.substring(1) } ?: path
        else -> path
    }

private val pathLog = BossLogger.forComponent("WorkspaceMcpToolProvider")

/**
 * The canonical absolute form of [path], or null when it is not an existing directory.
 * Every failure (missing, a file, unreadable, security-restricted) means the same thing
 * to the caller: report a clear error.
 */
@Suppress("TooGenericExceptionCaught")
internal fun canonicalizeOrNull(path: String): String? =
    try {
        val dir = File(path)
        if (dir.isDirectory) dir.canonicalPath else null
    } catch (t: Throwable) {
        pathLog.warn(LogCategory.WORKSPACE, "Cannot canonicalize project path: $path", error = t)
        null
    }

/**
 * The outcome of validating the caller's project path for open_workspace path mode: the
 * canonical directory to open ([canonicalPath]), or the user-facing reason it was refused
 * ([error]); at most one of the two is set.
 */
private data class ProjectPathCheck(
    val canonicalPath: String?,
    val error: String?,
)

private suspend fun checkProjectPath(rawPath: String): ProjectPathCheck {
    val expandedPath = expandTilde(rawPath)
    // Same gate the boss://folder deep link runs before opening a project folder: a connected
    // MCP client is no more trusted than a web page, so both surfaces share one definition of
    // an acceptable project path, failing closed.
    val rejection =
        when {
            !File(expandedPath).isAbsolute -> {
                "Path must be absolute (got '$rawPath'): a relative path would resolve against the " +
                    "BOSS process's working directory, not the caller's."
            }

            !CLISecurityValidator.isValidPath(expandedPath) -> {
                "Refusing to open '$rawPath': the path contains characters the boss:// folder deep " +
                    "link rejects for the same operation (`..`, or shell metacharacters like `;`, `&`, " +
                    "`|`, `$` and a backtick). Pass a plain absolute path to the project directory instead."
            }

            else -> {
                null
            }
        }
    if (rejection != null) {
        return ProjectPathCheck(null, rejection)
    }
    val canonical = withContext(Dispatchers.IO) { canonicalizeOrNull(expandedPath) }
    return ProjectPathCheck(canonical, "Path is not an existing directory: $rawPath".takeIf { canonical == null })
}

/**
 * The outcome of containing a `workspacePath` open_workspace argument to the workspace store:
 * [canonicalPath] is the path the call may go on to read - the caller's path, canonicalised,
 * so the file checked is the file read - or [error] is the user-facing refusal. At most one of
 * the two is set, and every failure mode (a path the filesystem cannot represent, traversal,
 * an absolute path elsewhere, a symlink out of the store, the store directory itself) is a
 * refusal: the gate fails closed.
 */
internal data class WorkspacePathCheck(
    val canonicalPath: String?,
    val error: String?,
)

/**
 * Resolves [path] to its real on-disk location, symlinks included. File.canonicalFile resolves
 * links on Linux and macOS but NOT on Windows, where it only normalises spelling and leaves
 * reparse points (symlinks, junctions) alone - so a link inside the store pointing out slipped
 * containment precisely on Windows CI. Path.toRealPath resolves links on every OS (realpath on
 * Unix, GetFinalPathNameByHandle on Windows) and canonicalises 8.3 short names and separators.
 * A path that does not exist yet cannot be real-pathed: its containment (a Space file about to
 * be created via createIfAbsent, a `..` chain over a not-yet-created directory) is decided by
 * where it would land, so it falls back to lexical canonicalisation. Fails closed: an
 * unparseable or otherwise unresolvable path returns null and the caller refuses.
 */
private fun realPathOrNull(path: String): File? =
    try {
        File(path).toPath().toRealPath().toFile()
    } catch (_: java.nio.file.NoSuchFileException) {
        try {
            File(path).canonicalFile
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: java.nio.file.InvalidPathException) {
        null
    } catch (_: SecurityException) {
        null
    }

/**
 * Containment for the workspacePath mode of open_workspace (#896): the file the mode names is
 * not merely opened, it is parsed and applied as the window's live Space layout, so the
 * reachable set is the workspace store [workspaceDirectory] - the same directory the
 * workspaceId mode loads from and this app itself writes - not any readable file on disk.
 *
 * Both sides are resolved to their real on-disk location first, so `..` that stays inside the
 * store is legal while `..` that escapes is not, and a symlink inside the store pointing out
 * resolves outside and is refused - [realPathOrNull] follows symlinks on every OS, unlike
 * File.canonicalFile on Windows. [rawPath] need not exist: containment decides only whether the
 * call may go on to probe and read it.
 */
internal suspend fun checkWorkspacePathContainment(
    rawPath: String,
    workspaceDirectory: String,
): WorkspacePathCheck {
    val resolved =
        withContext(Dispatchers.IO) {
            val storeRoot = realPathOrNull(workspaceDirectory)
            val requested = realPathOrNull(rawPath)
            if (storeRoot == null || requested == null) null else Pair(storeRoot, requested)
        }
    val (storeRoot, requested) =
        resolved ?: return WorkspacePathCheck(null, "Invalid workspace path (security check failed)")
    // Path.startsWith is component-wise: a sibling named to share the store's prefix is not
    // inside it, and the store directory itself is not a Space file inside the store.
    val contained = requested != storeRoot && requested.toPath().startsWith(storeRoot.toPath())
    return if (contained) {
        WorkspacePathCheck(requested.path, null)
    } else {
        WorkspacePathCheck(
            null,
            "Refusing to open '$rawPath' as a Space: it resolves to " +
                "${requested.path}, which is not a file inside the workspaces directory " +
                "(${storeRoot.path}) - the only directory open_workspace loads and applies " +
                "Space layouts from. Copy the file there and open it by its path or workspaceId.",
        )
    }
}

/**
 * The bootstrap Space open_workspace opens for a project: one panel, one terminal tab pointed
 * at the project, named for it, so it reads naturally in the Space picker if the user saves it
 * (consolidated from #799).
 */
internal fun buildBootstrapSpace(canonicalPath: String): LayoutWorkspace {
    val projectName = canonicalPath.trimEnd('/').extractFileName().ifEmpty { "Project" }
    return LayoutWorkspace(
        id = LayoutWorkspace.generateId(),
        name = projectName,
        description = "Bootstrap Space opened by the open_workspace MCP tool.",
        layout =
            SinglePanel(
                PanelConfig(
                    id = WorkspaceMcpToolProvider.BOOTSTRAP_PANEL_ID,
                    tabs =
                        listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Terminal",
                                workingDirectory = canonicalPath,
                            ),
                        ),
                ),
            ),
        timestamp = Clock.System.now().toEpochMilliseconds(),
        projectPath = canonicalPath,
    )
}

/**
 * The Space to re-enter for [projectPath], if there is one worth reusing rather than building a
 * fresh bootstrap Space (matching rules consolidated from #799):
 *
 * 1. a Space this tool already created for the path, when it is running in the window;
 * 2. a saved Space for the path that is running in the window, the same thing from the user's
 *    own list;
 * 3. any other saved Space for the path, applied to this window exactly the way picking it in
 *    the Space switcher would apply it - the switcher itself does not block a Space that is
 *    already running in ANOTHER window (WorkspaceButton only marks which Spaces run where, it
 *    never vetoes), so this rule deliberately matches it. Consequence, accepted as in the UI:
 *    one Space id can be running in two windows until one of them switches away;
 * 4. a Space this tool created earlier even though it is no longer running - reusing the object
 *    keeps its id stable instead of minting a second Space for the same directory.
 */
internal fun matchExistingSpace(
    remembered: LayoutWorkspace?,
    savedSpaces: List<LayoutWorkspace>,
    runningIdsInWindow: Set<String>,
    projectPath: String,
): LayoutWorkspace? =
    remembered?.takeIf { it.id in runningIdsInWindow }
        ?: savedSpaces.firstOrNull { it.id in runningIdsInWindow && it.projectPath == projectPath }
        ?: savedSpaces.firstOrNull { it.projectPath == projectPath }
        ?: remembered

/** IDs are names in the workspace store, never caller-selected filesystem paths. */
internal fun isSafeWorkspaceId(id: String): Boolean =
    id.isNotBlank() && id != "." && ".." !in id && id.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }

internal fun SplitConfig.hasInitialCommands(): Boolean =
    when (this) {
        is SplitConfig.SinglePanel -> panel.tabs.any { !it.initialCommand.isNullOrBlank() }
        is SplitConfig.VerticalSplit -> left.hasInitialCommands() || right.hasInitialCommands()
        is SplitConfig.HorizontalSplit -> top.hasInitialCommands() || bottom.hasInitialCommands()
    }
