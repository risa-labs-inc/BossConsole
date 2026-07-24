package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the host side of the Tool Evolver runtime contract ([EvolverContract]):
 * the "Open Evolver" panel-menu item dispatches
 * `invoke(EvolverContract.OPEN_TOOL, {"plugin_id": …})` built with
 * kotlinx-serialization, and the registry must deliver the plugin id to the
 * tool's handler intact.
 */
class EvolverContractTest {
    private fun openToolProvider(onPluginId: (String?) -> Unit) =
        object : McpToolProvider {
            override val providerId = "tool-evolver-test"

            override fun tools() =
                listOf(
                    McpToolDefinition(
                        name = EvolverContract.OPEN_TOOL,
                        description = "test double of the evolver's open tool",
                        handler =
                            McpToolHandler { args ->
                                val id = args.string(EvolverContract.ARG_PLUGIN_ID)
                                onPluginId(id)
                                if (id == null) {
                                    McpToolResult("Missing plugin_id", isError = true)
                                } else {
                                    McpToolResult("opened:$id")
                                }
                            },
                    ),
                )
        }

    /** Exactly the argument string SidePanel builds for the menu item. */
    private fun sidePanelArgs(pluginId: String): String = buildJsonObject { put(EvolverContract.ARG_PLUGIN_ID, pluginId) }.toString()

    @Test
    fun `open evolver dispatch round-trips the plugin id to the handler`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            var received: String? = null
            core.registerProvider(openToolProvider { received = it })

            val pluginId = "ai.rever.boss.plugin.dynamic.bookmarks"
            val result = core.invoke(EvolverContract.OPEN_TOOL, sidePanelArgs(pluginId))

            assertFalse(result.isError, "expected success, got: ${result.text}")
            assertEquals(pluginId, received)
            assertEquals("opened:$pluginId", result.text)
        }

    @Test
    fun `menu gating predicate matches the registered tool name`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(openToolProvider { })

        // Same predicate SidePanel uses to decide whether to render the item.
        assertTrue(core.tools.value.any { it.definition.name == EvolverContract.OPEN_TOOL })
    }

    @Test
    fun `hostile characters in the id survive json building without corrupting the arg map`() =
        runBlocking {
            // Plugin ids are constrained to reverse-DNS chars elsewhere, but this call
            // path must not depend on that invariant: quotes/backslashes must arrive
            // verbatim rather than producing malformed JSON that parses to no args.
            val core = McpToolRegistryCore(disabledFile = null)
            var received: String? = null
            core.registerProvider(openToolProvider { received = it })

            val hostile = "we\"ird\\plug\$in"
            val result = core.invoke(EvolverContract.OPEN_TOOL, sidePanelArgs(hostile))

            assertFalse(result.isError, "expected success, got: ${result.text}")
            assertEquals(hostile, received)
        }
}
