package ai.rever.boss.mcp

import ai.rever.boss.components.workspaces.WorkspacePortability
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

/**
 * Host MCP tool provider for making a saved Space shareable across machines
 * ([WorkspacePortability]).
 *
 * Both tools are pure transforms over the serialized Space JSON and touch no host state, so both
 * declare `readOnly = true` and the mutating gate leaves them at ALLOW:
 * - `workspace_make_portable` rewrites a Space's absolute project path to the `{projectPath}`
 *   placeholder, producing JSON safe to share (write it to a file, commit it, send it).
 * - `workspace_resolve_portable` binds portable JSON to a project path on this machine, producing
 *   a Space JSON with a fresh id. Saving or opening the result is done through the existing
 *   workspace tools (`create_workspace` / `open_workspace`) or the Space UI - keeping this surface
 *   a pure transform rather than a second write path.
 */
@Suppress("TooManyFunctions")
object WorkspacePortabilityMcpToolProvider : McpToolProvider {
    override val providerId: String = "boss-workspace-portability"

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createMakePortableTool(),
            createResolvePortableTool(),
        )

    private fun createMakePortableTool(): McpToolDefinition =
        McpToolDefinition(
            name = "workspace_make_portable",
            description =
                "Rewrite a saved Space's absolute project path to the {projectPath} placeholder so " +
                    "the JSON can be shared across machines. Input is a serialized Space; output is " +
                    "portable Space JSON.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "workspace": { "type": "string", "description": "Serialized Space JSON to make portable" }
                    },
                    "required": ["workspace"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleMakePortable(args) },
            readOnly = true,
        )

    private fun createResolvePortableTool(): McpToolDefinition =
        McpToolDefinition(
            name = "workspace_resolve_portable",
            description =
                "Bind portable Space JSON to a project path on this machine, resolving {projectPath} " +
                    "and minting a fresh id. Output is Space JSON; save/open it with create_workspace " +
                    "or open_workspace.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "workspace": { "type": "string", "description": "Portable Space JSON to resolve" },
                        "projectPath": { "type": "string", "description": "Absolute project path to bind it to" }
                    },
                    "required": ["workspace", "projectPath"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleResolvePortable(args) },
            readOnly = true,
        )

    @Suppress("ReturnCount")
    private fun handleMakePortable(args: McpToolArgs): McpToolResult {
        val workspaceJson = args.string("workspace")
        if (workspaceJson.isNullOrBlank()) {
            return McpToolResult("workspace is required", isError = true)
        }
        val workspace =
            runCatching { WorkspaceSerializer.deserialize(workspaceJson) }.getOrNull()
                ?: return McpToolResult("workspace is not valid Space JSON", isError = true)
        return McpToolResult(WorkspacePortability.toPortableJson(workspace))
    }

    @Suppress("ReturnCount")
    private fun handleResolvePortable(args: McpToolArgs): McpToolResult {
        val workspaceJson = args.string("workspace")
        val projectPath = args.string("projectPath")
        if (workspaceJson.isNullOrBlank()) {
            return McpToolResult("workspace is required", isError = true)
        }
        if (projectPath.isNullOrBlank()) {
            return McpToolResult("projectPath is required", isError = true)
        }
        val resolved =
            WorkspacePortability.fromPortableJson(workspaceJson, projectPath)
                ?: return McpToolResult("workspace is not valid Space JSON", isError = true)
        return McpToolResult(WorkspaceSerializer.serialize(resolved))
    }
}
