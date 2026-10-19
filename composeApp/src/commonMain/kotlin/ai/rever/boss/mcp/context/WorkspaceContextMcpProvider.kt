package ai.rever.boss.mcp.context

import ai.rever.boss.git.GitService
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.window.WindowProjectStateRegistry
import kotlinx.serialization.json.Json

/**
 * Host MCP tool provider exposing live workspace context (open tabs, active project roots,
 * and the focused editor file) to AI coding agents.
 *
 * ## Security and Disclosure Policy
 * Where [ai.rever.boss.mcp.IntrospectionMcpToolProvider] intentionally emits magnitudes only
 * (counts of tabs/terminals, deliberately refusing to disclose file paths or browser URLs),
 * this provider exists specifically to give operator-attached AI coding agents the context
 * required to assist with editing and navigation.
 *
 * To prevent ungated exposure of background workspace paths and cross-window URLs to untrusted
 * or unattended callers, every tool in this provider is protected by two distinct gates:
 * 1. **Permission Gate**: Requires the workspace.context permission ([CONTEXT_PERMISSION]).
 * 2. **Policy Gate**: Enrolled in [ai.rever.boss.mcp.McpMutatingToolCatalog.KNOWN_MUTATING_TOOLS]
 *    as a sensitive read, routing invocations through the approval-requiring ASK default
 *    (matching downloads_history_list).
 */
class WorkspaceContextMcpProvider(
    private val projectPathResolver: (windowId: String) -> String? = { windowId ->
        WindowProjectStateRegistry
            .get(windowId)
            ?.selectedProject
            ?.value
            ?.path
            ?.ifBlank { null }
            ?: GitService.getCurrentProjectPath()
    },
    private val activeWindowIdSupplier: () -> String? = { WindowFocusManager.resolveActionableWindowId() },
    private val globalProjectPathSupplier: () -> String? = { GitService.getCurrentProjectPath() },
    private val snapshotSupplier: () -> WorkspaceSnapshot = {
        WorkspaceSnapshotCollector.collect(
            projectPathResolver = projectPathResolver,
            activeWindowIdSupplier = activeWindowIdSupplier,
            globalProjectPathSupplier = globalProjectPathSupplier,
        )
    },
    private val activeEditorSupplier: () -> ActiveEditorFileSnapshot? = {
        WorkspaceSnapshotCollector.findActiveEditorFile(
            projectPathResolver = projectPathResolver,
            activeWindowIdSupplier = activeWindowIdSupplier,
        )
    },
) : McpToolProvider {
    override val providerId: String = PROVIDER_ID

    companion object {
        const val PROVIDER_ID: String = "boss-workspace-context"
        const val CONTEXT_PERMISSION: String = "workspace.context"
    }

    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = "get_workspace_context",
                description =
                    "Get full live workspace snapshot including active project root, all open tabs " +
                        "categorized by type, tab counts, and active editor file.",
                inputSchema = """{"type":"object","properties":{},"required":[]}""",
                readOnly = true,
                handler =
                    McpToolHandler {
                        val snapshot = snapshotSupplier()
                        McpToolResult(text = json.encodeToString(WorkspaceSnapshot.serializer(), snapshot))
                    },
            ).apply {
                requiredPermissions = listOf(CONTEXT_PERMISSION)
            },
            McpToolDefinition(
                name = "get_active_editor_file",
                description = "Get the file path and name currently focused in the editor panel.",
                inputSchema = """{"type":"object","properties":{},"required":[]}""",
                readOnly = true,
                handler =
                    McpToolHandler {
                        val activeFile = activeEditorSupplier()
                        val result =
                            if (activeFile != null) {
                                ActiveEditorFileResult(available = true, activeEditorFile = activeFile)
                            } else {
                                ActiveEditorFileResult(available = false, activeEditorFile = null)
                            }
                        McpToolResult(text = json.encodeToString(ActiveEditorFileResult.serializer(), result))
                    },
            ).apply {
                requiredPermissions = listOf(CONTEXT_PERMISSION)
            },
        )
}
