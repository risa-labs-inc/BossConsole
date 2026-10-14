package ai.rever.boss.mcp

import ai.rever.boss.components.workspaces.WorkspacePortability
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [WorkspacePortabilityMcpToolProvider], exercising each tool through its
 * registered handler. Both tools are pure transforms, so no singleton state is redirected.
 */
class WorkspacePortabilityMcpToolProviderTest {
    private val projectPath = "/Users/me/proj"

    private fun sampleJson(): String {
        val panel =
            PanelConfig(
                id = "main",
                tabs = listOf(TabConfig(type = "editor", title = "E", filePath = "/Users/me/proj/A.kt")),
            )
        val ws =
            LayoutWorkspace(
                id = "workspace-1",
                name = "S",
                description = "d",
                layout = SplitConfig.SinglePanel(panel),
                projectPath = projectPath,
            )
        return WorkspaceSerializer.serialize(ws)
    }

    private fun args(vararg pairs: Pair<String, Any>) = McpToolArgs(mapOf(*pairs), "{}")

    private suspend fun call(
        name: String,
        args: McpToolArgs,
    ): McpToolResult {
        val tool = WorkspacePortabilityMcpToolProvider.tools().firstOrNull { it.name == name }
        requireNotNull(tool) { "tool $name not found" }
        return tool.handler.call(args)
    }

    @Test
    fun `both tools are read-only`() {
        val readOnly = WorkspacePortabilityMcpToolProvider.tools().associate { it.name to it.readOnly }
        assertEquals(true, readOnly["workspace_make_portable"])
        assertEquals(true, readOnly["workspace_resolve_portable"])
    }

    @Test
    fun `make_portable removes the absolute path and resolve_portable binds a new one`() =
        runBlocking {
            val portable = call("workspace_make_portable", args("workspace" to sampleJson()))
            assertFalse(portable.isError)
            assertTrue(portable.text.contains(WorkspacePortability.PLACEHOLDER))
            assertFalse(portable.text.contains(projectPath))

            val resolveArgs = args("workspace" to portable.text, "projectPath" to "/opt/x")
            val resolved = call("workspace_resolve_portable", resolveArgs)
            assertFalse(resolved.isError)
            val ws = WorkspaceSerializer.deserialize(resolved.text)
            assertEquals("/opt/x", ws.projectPath)
            assertEquals(
                "/opt/x/A.kt",
                (ws.layout as SplitConfig.SinglePanel)
                    .panel.tabs
                    .first()
                    .filePath,
            )
        }

    @Test
    fun `make_portable rejects a missing or invalid workspace`() =
        runBlocking {
            assertTrue(call("workspace_make_portable", args()).isError)
            assertTrue(call("workspace_make_portable", args("workspace" to "{ nope")).isError)
        }

    @Test
    fun `resolve_portable requires both a workspace and a project path`() =
        runBlocking {
            assertTrue(call("workspace_resolve_portable", args("workspace" to sampleJson())).isError)
            assertTrue(call("workspace_resolve_portable", args("projectPath" to "/opt/x")).isError)
        }
}
