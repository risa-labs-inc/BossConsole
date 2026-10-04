package ai.rever.boss.mcp.context

import ai.rever.boss.testsupport.repoRoot
import kotlin.test.Test
import kotlin.test.assertTrue

class WorkspaceContextRegistrationDriftTest {
    @Test
    fun `the workspace context provider is registered in McpToolRegistryImpl`() {
        val source =
            repoRoot()
                .resolve("composeApp/src/commonMain/kotlin/ai/rever/boss/mcp/McpToolRegistryImpl.kt")
                .readText()

        assertTrue(
            "registerProvider(WorkspaceContextMcpProvider())" in source,
            "WorkspaceContextMcpProvider is no longer registered in McpToolRegistryImpl - " +
                "its tools would vanish while every unit test still passed",
        )
    }
}
