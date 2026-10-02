package ai.rever.boss.mcp

import ai.rever.boss.cli.CLISecurityValidator
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.window_panel.TabPaths
import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LAST_SESSION_SET_FILE
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.SPACE_THEMES_FILE
import ai.rever.boss.components.workspaces.TabConfig
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.awaitTabTypes
import ai.rever.boss.components.workspaces.isSpaceSlot
import ai.rever.boss.components.workspaces.reservedWorkspaceStoreFileName
import ai.rever.boss.components.workspaces.withStableId
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
import kotlinx.serialization.json.JsonObject
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
 * - list_workspaces
 * - open_workspace
 * - create_workspace
 * - open_terminal
 * - close_workspace
 *
 * The reversed legacy names (workspace_list, workspace_open, workspace_create,
 * terminal_open, workspace_close) remain invocable as invoke-only aliases via
 * [toolAliases] but are not advertised in list_tools, the bridge mirror, or
 * search - advertising both spellings paid a second name + description +
 * schema per action on every listing.
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
object WorkspaceMcpToolProvider :
    McpToolProvider,
    McpToolAliasProvider,
    McpStoredCommandSource {
    private val logger = BossLogger.forComponent("WorkspaceMcpToolProvider")

    /** The one tool whose calls can run stored commands; see [storedCommandsFor]. */
    private const val OPEN_WORKSPACE_TOOL = "open_workspace"

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

    /**
     * Bound for awaiting a window THIS call just created. Composing a fresh window and
     * registering its [SplitViewState] takes far longer than [splitViewWaitTimeoutMs], which
     * is tuned for windows that are already open - and cold-start automation is exactly the
     * case these tools exist for, so it gets its own, longer wait.
     */
    internal var coldStartWindowWaitTimeoutMs: Long = 30_000L

    internal fun splitViewWaitTimeoutFor(isColdStart: Boolean): Long =
        if (isColdStart) coldStartWindowWaitTimeoutMs else splitViewWaitTimeoutMs

    private fun getFileManager(): WorkspaceFileManager = fileManagerProvider?.invoke() ?: WorkspaceFileManager()

    /**
     * A workspace `open_workspace` would apply for these selectors, and whether it is one of the
     * shipped templates (whose startup commands are BOSS's own, not a file's). Shared by the
     * handler and by [storedCommandsFor], so the commands the operator approves are read from the
     * same place, by the same rule, as the ones that will run.
     */
    private class LocatedWorkspace(
        val workspace: LayoutWorkspace,
        val shipped: Boolean,
    )

    @Suppress("ReturnCount") // Ordered lookups: an explicit file, then a shipped template, then the store.
    private suspend fun locateStoredWorkspace(
        workspaceId: String?,
        canonicalWorkspacePath: String?,
    ): LocatedWorkspace? {
        if (canonicalWorkspacePath != null) {
            val file = File(canonicalWorkspacePath)
            if (file.exists() && file.canRead()) {
                val content = withContext(Dispatchers.IO) { file.readText() }
                // withStableId for the same reason the handler does it: agent-authored JSON
                // commonly carries no id, and a blank one flows into the applier.
                runCatching { WorkspaceSerializer.deserialize(content) }
                    .getOrNull()
                    ?.withStableId()
                    ?.let { return LocatedWorkspace(it, shipped = false) }
            }
        }
        if (workspaceId.isNullOrBlank()) return null
        PredefinedWorkspaces.allWorkspaces
            .firstOrNull { it.id == workspaceId }
            ?.let { return LocatedWorkspace(it, shipped = true) }
        return getFileManager()
            .loadWorkspace(workspaceFileNameFor(workspaceId))
            ?.withStableId()
            ?.let { LocatedWorkspace(it, shipped = false) }
    }

    /**
     * The terminal startup commands `open_workspace` would type for these arguments, for the
     * approval dialog. Empty for every call the handler will refuse on its own (a bad id, a
     * path mode call, a missing file), for a shipped template, and for a Space without commands;
     * reads only, creates nothing.
     */
    @Suppress("ReturnCount") // Each early return is a call the handler refuses on its own, and reads nothing.
    override suspend fun storedCommandsFor(
        toolName: String,
        args: McpToolArgs,
    ): List<String> {
        // The registry resolves an alias (workspace_open) to this canonical name before asking.
        if (toolName != OPEN_WORKSPACE_TOOL) return emptyList()
        val workspaceId = args.string("workspaceId")
        val workspacePath = args.string("workspacePath")
        if (!args.string("path").isNullOrBlank()) return emptyList()
        if (workspaceId != null && !isSafeWorkspaceId(workspaceId)) return emptyList()
        // The handler's own containment check (#896), applied here too: the preview must never
        // read a file the call would be refused for, and the two must agree on which file it is.
        val canonicalWorkspacePath =
            if (workspacePath.isNullOrBlank()) {
                null
            } else {
                checkWorkspacePathContainment(
                    rawPath = workspacePath,
                    workspaceDirectory = getFileManager().getDefaultWorkspaceDirectory(),
                ).canonicalPath ?: return emptyList()
            }
        val located = locateStoredWorkspace(workspaceId, canonicalWorkspacePath) ?: return emptyList()
        if (located.shipped) return emptyList()
        return located.workspace.layout.initialCommands()
    }

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

    override val toolAliases: Map<String, String> =
        mapOf(
            "workspace_list" to "list_workspaces",
            "workspace_open" to OPEN_WORKSPACE_TOOL,
            "workspace_create" to "create_workspace",
            "terminal_open" to "open_terminal",
            "workspace_close" to "close_workspace",
        )

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createListWorkspacesTool("list_workspaces"),
            createOpenWorkspaceTool(OPEN_WORKSPACE_TOOL),
            createCreateWorkspaceTool("create_workspace"),
            createOpenTerminalTool("open_terminal"),
            createCloseWorkspaceTool("close_workspace"),
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
                    "with 'createIfAbsent'. A saved workspace with terminal startup commands always asks the " +
                    "operator, who is shown the commands; they run only if that approval is given.",
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

        val (targetWindowId, targetIsColdStart) =
            when (val resolution = resolveTargetWindow(requestedWindowId)) {
                is TargetWindowResolution.Success -> {
                    resolution.windowId to resolution.isColdStart
                }

                is TargetWindowResolution.Failure -> {
                    return McpToolResult(resolution.errorMessage, isError = true)
                }
            }

        // Locate or create workspace
        var persisted = false

        // A path that passed containment but names nothing readable is "not found", not a
        // security refusal, and it is decided before the store lookup so a stale path cannot
        // silently fall through to whatever a workspaceId names.
        if (canonicalWorkspacePath != null && !createIfAbsent) {
            val file = File(canonicalWorkspacePath)
            if (!file.exists() || !file.canRead()) {
                return McpToolResult("Workspace file not found: $workspacePath", isError = true)
            }
        }
        val located = locateStoredWorkspace(workspaceId, canonicalWorkspacePath)
        var workspace: LayoutWorkspace? = located?.workspace
        val isShippedTemplate = located?.shipped == true

        if (workspace == null) {
            if (createIfAbsent || !name.isNullOrBlank()) {
                // The same `.json` the read path strips (see workspaceFileNameFor): an agent that
                // echoes a file name back from a listing as the id must create the Space the next
                // open will find. Without this the id kept its suffix, saveWorkspace derived
                // `<id>.json` from it, and `foo.json` was written to `foo.json.json` while every
                // later read looked in `foo.json` - not found, and a second createIfAbsent silently
                // replaced the first layout.
                val newId =
                    workspaceId?.removeSuffix(".json")?.takeIf { it.isNotBlank() }
                        ?: LayoutWorkspace.generateId()
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
                // And the reserved store files the id resolves onto BY NAME - the document
                // records (Space_Themes, Last_Session_Set) and the single-Space session
                // record's spellings - through the one gate shared with the import path
                // (#926, #964, #1643). Fail-closed before anything is created or applied.
                refusalForReservedStoreFile(newId)?.let { return McpToolResult(it, isError = true) }
                val wsName = name?.takeIf { it.isNotBlank() } ?: "Workspace $newId"
                val rootPath = canonicalProjectPath ?: DefaultWorkingDirectory.nominalPath()
                workspace = createDefaultWorkspace(newId, wsName, rootPath, openTerminal = openTerminal)
                // Persist
                getFileManager().saveWorkspace(workspace)
                persisted = true
            } else {
                return McpToolResult(
                    "Workspace '$workspaceId' not found. Specify createIfAbsent=true to create it.",
                    isError = true,
                )
            }
        }

        // Persisted commands are not in this invocation's arguments, so the only way they run is
        // if the registry showed them to the operator and handed the approved list back (see
        // McpStoredCommandSource). The list must match what the file says now, command for
        // command and in order: a Space edited between the prompt and this point is not the one approved.
        val stored = if (isShippedTemplate) emptyList() else workspace.layout.initialCommands()
        if (stored.isNotEmpty()) {
            val approved = args.approvedStoredCommands()
            if (approved == null) {
                return McpToolResult(
                    "Workspace contains terminal startup commands that were not approved for this call. " +
                        "Retry so the current commands can be reviewed.",
                    isError = true,
                )
            }
            // In order: order is semantic for a shell (`... > f` then `cat f`), so a Space whose
            // commands were only reordered between the prompt and here is not the one approved.
            if (approved != stored) {
                return McpToolResult(
                    "Workspace '${workspace.id}' changed between approval and opening: its startup commands are " +
                        "no longer the ones the operator approved. Retry so the current commands can be reviewed.",
                    isError = true,
                )
            }
        }

        // Awaited only once there is a workspace to open, so a wrong id is reported at once rather
        // than after the UI-state wait. A window that never registers is an error, as it is in
        // path mode: nothing below can run without it, and a "success" that applied nothing would
        // send the agent on to open_terminal in a window that shows no Space.
        val splitViewState =
            awaitSplitViewState(targetWindowId, splitViewWaitTimeoutFor(targetIsColdStart))
                ?: return McpToolResult(
                    "Window '$targetWindowId' did not register its UI state in time, so workspace " +
                        "'${workspace.id}' was not opened; retry." +
                        if (persisted) " The new workspace file was saved." else "",
                    isError = true,
                )

        // Idempotency: Reopening an existing workspace does not duplicate it or disturb unrelated windows
        if (splitViewState.currentWorkspaceId == workspace.id) {
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
        if (
            !switchWindowToSpace(
                splitViewState,
                WindowProjectStateRegistry.getOrCreate(targetWindowId),
                workspace,
            )
        ) {
            return McpToolResult(
                "Workspace '${workspace.name}' could not be applied - none of its tabs can be " +
                    "built. The plugin that provides its tab types may have been removed; the " +
                    "window was left on whatever it was already showing.",
                isError = true,
            )
        }

        var terminalInfo: JsonObject? = null
        if (openTerminal) {
            val outcome = doOpenTerminal(targetWindowId, workspace.id, workspace.projectPath, command = null)
            if (outcome is TerminalOpenOutcome.Opened) {
                terminalInfo = outcome.info
            } else {
                return McpToolResult(
                    "Workspace '${workspace.id}' is open in window '$targetWindowId', but the terminal " +
                        "tab it asked for could not be opened; call open_terminal to retry.",
                    isError = true,
                )
            }
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
     * The path mode's refusal of a saved Space that carries startup commands (#920).
     *
     * A saved Space's terminal `initialCommand`s are arbitrary shell lines stored in its
     * layout, and they are not visible in this invocation's approval arguments. The id mode
     * shows them to the operator through [storedCommandsFor] and runs them only on that
     * approval; the path mode re-enters the same saved Spaces (see matchExistingSpace) without
     * that preview, so it keeps refusing them outright rather than typing unseen commands into a
     * shell. Shipped templates are exempt, exactly as in the id mode handler: their commands are
     * BOSS's own, not a file's.
     *
     * The operator keeps the doors the message names: the workspace UI loads a command-carrying
     * Space behind its confirmation prompt (see spaceLoadDisposition), and open_terminal types
     * one command an invocation the risk gate has actually seen.
     */
    private fun initialCommandsRefusal(
        space: LayoutWorkspace,
        isShippedTemplate: Boolean,
    ): McpToolResult? {
        if (isShippedTemplate || space.layout.initialCommands().isEmpty()) {
            return null
        }
        return McpToolResult(
            "Workspace contains terminal startup commands. Open it through the workspace UI, " +
                "or remove the startup commands and invoke open_terminal with each command explicitly.",
            isError = true,
        )
    }

    /**
     * Path-based bootstrap mode of open_workspace (consolidated from #799): open [rawPath] as a
     * Space in a window, creating its first terminal panel for the panel-scoped terminal tools
     * (`run_in_panel` and friends). Re-opening a path that is already running re-enters the
     * Space instead of minting a duplicate (see [matchExistingSpace]).
     */
    @Suppress("ReturnCount", "LongMethod") // Keep target-window and Space apply decisions in one path.
    private suspend fun openWorkspaceByPath(
        rawPath: String,
        requestedWindowId: String?,
    ): McpToolResult {
        val pathCheck = checkProjectPath(rawPath)
        val projectPath =
            pathCheck.canonicalPath
                ?: return McpToolResult(pathCheck.error ?: "Invalid path: $rawPath", isError = true)

        val (targetWindowId, targetIsColdStart) =
            when (val resolution = resolveTargetWindow(requestedWindowId)) {
                is TargetWindowResolution.Success -> {
                    resolution.windowId to resolution.isColdStart
                }

                is TargetWindowResolution.Failure -> {
                    return McpToolResult(resolution.errorMessage, isError = true)
                }
            }
        val splitViewState =
            awaitSplitViewState(targetWindowId, splitViewWaitTimeoutFor(targetIsColdStart))
                ?: return McpToolResult(
                    "Window '$targetWindowId' did not register its UI state in time; retry.",
                    isError = true,
                )
        // The remembered map is process-lifetime storage; a closed window's Spaces can never
        // be re-entered, so prune entries for windows that no longer exist before growing it.
        pruneClosedWindows()
        val runningIds = workspaceManager.windowWorkspaces.value[targetWindowId].orEmpty()
        val (space, reused) = resolveBootstrapSpace(targetWindowId, projectPath, runningIds)

        // Path mode re-enters the SAME saved Spaces the id mode resolves above -
        // matchExistingSpace's rule 3 applies any saved Space for this project path exactly as
        // picking it in the Space switcher would. The id mode shows that Space's stored terminal
        // commands in the approval prompt and runs them only on that approval
        // (McpStoredCommandSource); path mode has no such preview, so it keeps refusing a Space
        // that carries any, or a caller could have them typed into a shell by reaching for a path
        // instead of an id (#920). The refusal sits before the reuse fast path because entering
        // the Space through this mode at all is what is refused, not only the apply.
        initialCommandsRefusal(space, space.id in PredefinedWorkspaces.allIds)?.let { return it }

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
        if (
            !switchWindowToSpace(splitViewState, WindowProjectStateRegistry.getOrCreate(targetWindowId), space)
        ) {
            return McpToolResult(
                "The Space for '$projectPath' could not be applied - none of its tabs can be " +
                    "built. The plugin that provides its tab types may have been removed; the " +
                    "window was left on whatever it was already showing.",
                isError = true,
            )
        }

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
     * Preserve, apply, load: the same three steps the Space switcher takes, so re-entering a
     * previously running Space restores its preserved tree when the window holds one.
     *
     * @return false when the apply was refused - the window is left showing whatever it showed
     *   before, and the manager is left pointing at it too, rather than at a Space that was
     *   never applied.
     */
    private suspend fun switchWindowToSpace(
        splitViewState: SplitViewState,
        windowProjectState: WindowProjectState,
        space: LayoutWorkspace,
    ): Boolean =
        withContext(Dispatchers.Main) {
            val currentWorkspace = workspaceManager.currentWorkspace.value
            val leavingId = currentWorkspace?.id?.takeIf { it.isNotEmpty() }
            if (leavingId != null) {
                splitViewState.preserveCurrentState(leavingId, currentWorkspace?.name.orEmpty())
            }
            if (applyWorkspace(space, splitViewState, windowProjectState, restoreProject = true)) {
                workspaceManager.loadWorkspace(space)
                true
            } else {
                if (leavingId != null) {
                    splitViewState.restorePreservedState(leavingId)
                    splitViewState.discardPreservedState(leavingId)
                }
                false
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

        // The minted ids cannot spell a reserved store file today, but the guard is fail-closed
        // on every persist route this tool has, not only the caller-chosen one (#926).
        refusalForReservedStoreFile(id)?.let { return McpToolResult(it, isError = true) }

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

        val (targetWindowId, targetIsColdStart) =
            when (val resolution = resolveTargetWindow(requestedWindowId)) {
                is TargetWindowResolution.Success -> {
                    resolution.windowId to resolution.isColdStart
                }

                is TargetWindowResolution.Failure -> {
                    return McpToolResult(resolution.errorMessage, isError = true)
                }
            }

        return doOpenTerminal(
            targetWindowId,
            workspaceId,
            canonicalWorkingDirectory,
            command,
            splitViewWaitTimeoutFor(targetIsColdStart),
        ).toResult()
    }

    /**
     * The tool's reply: a mounted tab's info, or an error that says which kind of no it was.
     * A slow register is retryable and says so; a mount failure is not, and must not wear the
     * same message - "Failed" alone used to send agents away from a window that was simply
     * still starting.
     */
    private fun TerminalOpenOutcome.toResult(): McpToolResult =
        when (this) {
            is TerminalOpenOutcome.Opened -> {
                McpToolResult(info.toString())
            }

            is TerminalOpenOutcome.WindowNotReady -> {
                McpToolResult(
                    "Timed out after ${waitedMs}ms waiting for window '$windowId' " +
                        "to become ready; the window may still be starting - retry the request.",
                    isError = true,
                )
            }

            is TerminalOpenOutcome.TabOpenFailed -> {
                McpToolResult(
                    "Failed to open terminal in window $windowId",
                    isError = true,
                )
            }
        }

    /** Result of mounting a terminal tab, so a caller can say WHY an open did not happen. */
    internal sealed class TerminalOpenOutcome {
        data class Opened(
            val info: JsonObject,
        ) : TerminalOpenOutcome()

        /** The window's UI state never registered inside [waitedMs] - a slow start, retryable. */
        data class WindowNotReady(
            val windowId: String,
            val waitedMs: Long,
        ) : TerminalOpenOutcome()

        /** The window was ready but no terminal tab could be mounted. */
        data class TabOpenFailed(
            val windowId: String,
        ) : TerminalOpenOutcome()
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
        stateWaitTimeoutMs: Long = splitViewWaitTimeoutMs,
    ): TerminalOpenOutcome {
        val effectiveCwd = workingDirectory ?: DefaultWorkingDirectory.nominalPath()

        val mountedTab =
            terminalTabOpener?.invoke(windowId, command, effectiveCwd) ?: run {
                val splitViewState =
                    awaitSplitViewState(windowId, stateWaitTimeoutMs)
                        ?: return TerminalOpenOutcome.WindowNotReady(windowId, stateWaitTimeoutMs)
                withContext(Dispatchers.Main) {
                    splitViewState.tabRegistry.awaitTabTypes(setOf(TerminalTabType.typeId))
                    splitViewState.openTerminalInActivePanelNow(command, effectiveCwd)
                }
            } ?: return TerminalOpenOutcome.TabOpenFailed(windowId)

        DashboardStatsManager.recordTerminalSession()

        val tabId = mountedTab.id
        // openTerminalInActivePanelNow mints ids as "terminal-<millis>-<entropy>" (the only path this
        // tool uses in production); the terminal's addressing keys on the part after the prefix.
        val terminalId = tabId.removePrefix("terminal-")

        return TerminalOpenOutcome.Opened(
            buildJsonObject {
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
            },
        )
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
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
                // must never mint a window. Exactly one registered window is the only possible
                // target; with none there is nothing to close in, though the disposable-file
                // delete below can still run.
                val openWindowIds = SplitViewStateRegistry.getAllStates().keys
                ambiguousWindowError(workspaceId, openWindowIds)?.let { return it }
                openWindowIds.singleOrNull()
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
            fileDeleted = getFileManager().deleteWorkspace(workspaceFileNameFor(workspaceId))
        }

        // Saying "success" when neither happened leaves the agent unable to tell "closed"
        // from "that id does not exist anywhere". Zero registered windows is said plainly so
        // the agent knows there is no windowId it could pass - "(none)" alone read like a
        // missing target.
        if (!releasedHere && !fileDeleted) {
            val where = targetWindowId?.let { "in window '$it'" } ?: "in any window (none are open)"
            return McpToolResult(
                "Workspace '$workspaceId' is not running $where " +
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

    /**
     * The actionable refusal for a `close_workspace` call that named no `windowId` while
     * several windows are open. Ambiguity used to collapse to a null target and surface only
     * as a generic "nothing was closed", which an agent cannot act on - the error names the
     * candidates so the caller can retry with one, and nothing has been changed when it fires.
     * Returns null when zero or one window is open, where targeting is unambiguous.
     */
    private fun ambiguousWindowError(
        workspaceId: String,
        openWindowIds: Set<String>,
    ): McpToolResult? {
        if (openWindowIds.size <= 1) return null
        return McpToolResult(
            "Multiple windows are open (${openWindowIds.joinToString(", ")}); " +
                "pass 'windowId' to choose which window to close '$workspaceId' in.",
            isError = true,
        )
    }

    /**
     * The user-facing refusal when a workspace id would persist onto one of the workspace
     * store's reserved record files (see
     * [WorkspaceFileManagerCommon.reservedRecordFileNames]), or null when the id is safe.
     *
     * The workspace MCP tools are the one door where a caller-chosen *id* becomes a file: every
     * save route here derives the file from the id ([WorkspaceFileManagerCommon.fileNameForId]),
     * so an id that resolves to `Space_Themes.json`, `Last_Session_Set.json` or one of the
     * single-Space session record's spellings does not save a Space, it overwrites the host's
     * own store: every Space's theme assignment goes with the first, the next launch's session
     * restore with the second, and the crash-recovery record with the third (#926).
     *
     * The one gate is [reservedWorkspaceStoreFileName], shared with the import path's
     * `withImportableId` (#964, #1643): it strips the caller's own `.json` suffix because the
     * load path treats a suffixed id as that file name, derives the name with the SAME
     * sanitiser the save uses, and compares case-insensitively because APFS and NTFS fold
     * case. `WorkspaceManager` skips the document records when it scans the directory and
     * still loads the real session record; this mirrors the write side of that boundary,
     * fail-closed and BEFORE anything is created or applied, so nothing is left half-made.
     * Path-shaped spellings never get this far - [isSafeWorkspaceId] refuses them first.
     */
    internal fun refusalForReservedStoreFile(id: String): String? {
        val reserved = reservedWorkspaceStoreFileName(id) ?: return null
        return "'$id' cannot be a workspace id: it resolves to the reserved store file " +
            "'$reserved', which BOSS keeps for its own records (Space themes, the session " +
            "records). Pass a different workspaceId."
    }

    /**
     * The file a caller-supplied workspace id lives in. An id is a name, never a path: the
     * `.json` suffix an agent may echo back from a listing is accepted, but the name is then
     * derived through [WorkspaceFileManagerCommon.fileNameForId] exactly as it was when the file
     * was written, so a separator or `..` in the id cannot select a file outside the workspace
     * directory.
     *
     * Confinement itself no longer rests here: [isSafeWorkspaceId] refuses a path-shaped id at the
     * top of both handlers, and [WorkspaceFileManagerCommon.isBareFileName] refuses a path-shaped
     * name at the file manager. This is the layer in between, and what it is for is that the name
     * read is the name written: the create path strips the same suffix when it mints an id.
     */
    internal fun workspaceFileNameFor(workspaceId: String): String =
        WorkspaceFileManagerCommon.fileNameForId(workspaceId.removeSuffix(".json"))

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
    // Enforces system path bounds in addition to the boss://folder deep link gate: an MCP
    // client must not open restricted operating system directories, relative paths (which
    // would resolve against the BOSS process working directory), or paths rejected by isValidPath,
    // making the MCP surface strictly stricter.
    val rejection =
        when {
            CLISecurityValidator.isRestrictedSystemPath(expandedPath) -> {
                "Refusing to open '$rawPath': target path is a restricted system directory."
            }

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
    val isRestrictedCanonical = canonical != null && CLISecurityValidator.isRestrictedSystemPath(canonical)
    return if (isRestrictedCanonical) {
        ProjectPathCheck(
            null,
            "Refusing to open '$rawPath': canonical path '$canonical' is a restricted system directory.",
        )
    } else {
        ProjectPathCheck(canonical, "Path is not an existing directory: $rawPath".takeIf { canonical == null })
    }
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
        ?: savedSpaces.firstOrNull { it.id in runningIdsInWindow && matchesProjectPath(it, projectPath) }
        ?: savedSpaces.firstOrNull { matchesProjectPath(it, projectPath) }
        ?: remembered

/**
 * Path identity for the saved-Space lookup, not string identity. The request arrives
 * canonicalized (see [WorkspaceMcpToolProvider.checkProjectPath]) while a saved Space
 * stores whatever spelling its save flow used, and the two legitimately differ for the
 * same directory: on Windows the canonical form is `C:\dir` while a Space saved through
 * any other surface - or on another OS, in a synced workspace file - commonly spells it
 * `C:/dir`. Raw equality then missed the Space, silently minting a duplicate and, because
 * the #920 startup-command refusal rides on this lookup, bypassing that refusal for the
 * same directory spelled differently. [TabPaths.pathsMatch] is the house's definition of
 * same-file, already trusted for "is this file already open in a tab?".
 */
internal fun matchesProjectPath(
    space: LayoutWorkspace,
    projectPath: String,
): Boolean = space.projectPath?.let { TabPaths.pathsMatch(it, projectPath) } == true

/** IDs are names in the workspace store, never caller-selected filesystem paths. */
internal fun isSafeWorkspaceId(id: String): Boolean =
    id.isNotBlank() && id != "." && ".." !in id && id.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }

/** Every non-blank terminal `initialCommand` in this layout, in tab order. */
internal fun SplitConfig.initialCommands(): List<String> =
    when (this) {
        is SplitConfig.SinglePanel -> panel.tabs.mapNotNull { it.initialCommand?.takeIf { c -> c.isNotBlank() } }
        is SplitConfig.VerticalSplit -> left.initialCommands() + right.initialCommands()
        is SplitConfig.HorizontalSplit -> top.initialCommands() + bottom.initialCommands()
    }
