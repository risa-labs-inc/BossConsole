package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpToolRegistryImpl

/** The process-wide plugin-pack service: one effects wiring, one job registry, one MCP provider. */
object PluginPacks {
    private val effects: PluginPackEffects by lazy { DesktopPluginPackEffects() }
    private val jobs: PluginPackJobs by lazy { PluginPackJobs(PluginPackApplier(effects)) }

    /** Contribute `pack_plan`, `pack_apply` and `pack_status` to the host MCP registry. */
    fun registerMcpTools() {
        McpToolRegistryImpl.registerProvider(PluginPackMcpToolProvider(effects, jobs))
    }
}
