package ai.rever.boss.mcp.update

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

internal class AppUpdateMcpToolProvider(
    private val commands: AppUpdateCommands = sharedCommands,
) : McpToolProvider {
    override val providerId: String = "boss-app-updates"

    override fun tools(): List<McpToolDefinition> =
        listOf(
            tool("status", "Report BossConsole update state, progress, version and restart requirement."),
            tool("check", "Check for a BossConsole update; poll app_update_status."),
            tool("download", "Download the available BossConsole update; poll app_update_status."),
            tool("install", "Install staged version; may prompt, quit/relaunch BossConsole and disconnect MCP."),
        )

    private fun tool(
        action: String,
        description: String,
    ): McpToolDefinition =
        McpToolDefinition(
            name = "app_update_$action",
            description = description,
            inputSchema = if (action == "install") INSTALL_SCHEMA else EMPTY_SCHEMA,
            readOnly = action == "status",
            handler =
                McpToolHandler { args ->
                    val reply =
                        if (action == "status") {
                            commands.status()
                        } else {
                            commands.start(action, args.string("version"))
                        }
                    McpToolResult(reply.payload.toString(), isError = reply.isError)
                },
        )

    private companion object {
        val sharedCommands by lazy { AppUpdateCommands(ManagedAppUpdateBackend()) }

        const val EMPTY_SCHEMA = """{"type":"object","properties":{},"additionalProperties":false}"""
        val INSTALL_SCHEMA =
            """
            {"type":"object","properties":{"version":{"type":"string",
            "description":"Exact install_version returned by app_update_status"}},
            "required":["version"],"additionalProperties":false}
            """.trimIndent()
    }
}
