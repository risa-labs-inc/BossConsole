package ai.rever.boss.mcp.update

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppUpdateMcpToolProviderTest {
    @Test
    fun `provider exposes status as read only and version bound installation`() =
        runTest {
            val backend =
                object : AppUpdateBackend {
                    override fun snapshot() = AppUpdateSnapshot("BossConsole", "1.0", "idle")

                    override suspend fun perform(
                        action: String,
                        version: String?,
                    ) = Unit
                }
            val tools = AppUpdateMcpToolProvider(AppUpdateCommands(backend, backgroundScope)).tools()
            assertEquals(4, tools.size)
            assertEquals(listOf("app_update_status"), tools.filter { it.readOnly }.map { it.name })
            val install = tools.single { it.name == "app_update_install" }
            assertTrue(install.handler.call(McpToolArgs(emptyMap(), "{}")).isError)
            assertTrue(install.inputSchema.contains("required"))
            tools.filterNot { it.readOnly }.forEach {
                assertTrue(McpMutatingToolCatalog.isMutating(it.name, true))
            }
            assertEquals(
                McpRiskLevel.HIGH,
                DefaultMcpRiskEvaluator().evaluateRisk("app_update_install", McpToolArgs(emptyMap(), "{}")).level,
            )
        }
}
