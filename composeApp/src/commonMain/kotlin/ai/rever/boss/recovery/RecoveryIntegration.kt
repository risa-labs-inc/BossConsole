package ai.rever.boss.recovery

import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.recovery.mcp.RecoveryMcpToolProvider
import ai.rever.boss.recovery.runtime.MissionRecoveryCoordinator

/**
 * Integration bridge connecting the Workspace Recovery Engine to BOSS core services.
 */
object RecoveryIntegration {
    val coordinator: MissionRecoveryCoordinator by lazy { MissionRecoveryCoordinator() }
    val provider: RecoveryMcpToolProvider by lazy { RecoveryMcpToolProvider(coordinator) }

    /**
     * Registers the recovery MCP tool provider into the process-wide [McpToolRegistryImpl].
     */
    fun register() {
        McpToolRegistryImpl.registerProvider(provider)
    }

    /**
     * Unregisters the recovery MCP tool provider from [McpToolRegistryImpl].
     */
    fun unregister() {
        McpToolRegistryImpl.unregisterProvider(provider.providerId)
    }
}
