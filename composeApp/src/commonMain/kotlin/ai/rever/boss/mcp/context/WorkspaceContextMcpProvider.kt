package ai.rever.boss.mcp.context

import ai.rever.boss.git.GitService
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.Json

class WorkspaceContextMcpProvider(
    private val projectPathSupplier: () -> String? = { GitService.getCurrentProjectPath() },
    private val snapshotSupplier: () -> WorkspaceSnapshot = {
        WorkspaceSnapshotCollector.collect(projectPathSupplier = projectPathSupplier)
    },
    private val activeEditorSupplier: () -> ActiveEditorFileSnapshot? = { snapshotSupplier().activeEditorFile },
) : McpToolProvider {
    override val providerId: String = "boss-workspace-context"

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
                requiresAdmin = false
                requiredPermissions = emptyList()
            },
            McpToolDefinition(
                name = "get_active_editor_file",
                description = "Get the file path and name currently focused in the editor panel.",
                inputSchema = """{"type":"object","properties":{},"required":[]}""",
                readOnly = true,
                handler =
                    McpToolHandler {
                        val activeFile = activeEditorSupplier()
                        if (activeFile != null) {
                            McpToolResult(text = json.encodeToString(ActiveEditorFileSnapshot.serializer(), activeFile))
                        } else {
                            McpToolResult(text = """{"activeEditorFile":null}""")
                        }
                    },
            ).apply {
                requiresAdmin = false
                requiredPermissions = emptyList()
            },
        )
}
