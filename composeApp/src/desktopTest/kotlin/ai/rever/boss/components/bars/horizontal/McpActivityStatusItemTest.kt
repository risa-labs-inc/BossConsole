package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.mcp.McpToolRegistryCore
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first two cases pin the pure render rule. The rest drive
 * [mcpActivityStatusShowsFor] — the status item's registry-facing derivation —
 * through a real [McpToolRegistryCore], so the binding contract "registered tools
 * means `allTools`, not the exposed set" is executed rather than implied (review on
 * #1380: the pure tests alone still passed if the composable bound the wrong flow).
 */
class McpActivityStatusItemTest {
    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun echoTool(name: String) =
        McpToolDefinition(
            name = name,
            description = "test tool $name",
            handler = McpToolHandler { McpToolResult("ok:$name") },
            readOnly = true,
        )

    @Test
    fun `registered tools keep the status item visible when every tool is blocked`() {
        assertTrue(mcpActivityStatusShouldRender(false, true, false, false))
    }

    @Test
    fun `empty registry and activity keep the status item hidden`() {
        assertFalse(mcpActivityStatusShouldRender(false, false, false, false))
    }

    @Test
    fun `allTools keeps the item visible through a real registry even when every tool is blocked`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("git_status")))
        core.setToolEnabled("git_status", enabled = false)

        // The exposed set is empty; the item must still render from allTools, or the
        // operator could never reach the surface that explains the blocking.
        assertTrue(core.tools.value.isEmpty())
        assertTrue(mcpActivityStatusShowsFor(core.allTools.value, false, false, false))
    }

    @Test
    fun `an emptied registry hides the item when nothing else is showing`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("git_status")))
        core.unregisterProvider("p1")

        assertFalse(mcpActivityStatusShowsFor(core.allTools.value, false, false, false))
    }
}
