package ai.rever.boss.mastery

/**
 * Interface for resolving and invoking plugin capabilities.
 *
 * [MasteryExecutor] calls this to invoke individual nodes in a mastery workflow.
 * Implementations connect to plugin processes via gRPC IPC (using the Mastery service
 * capability invocation pattern from mastery.proto).
 */
interface CapabilityResolver {
    /**
     * Invoke a plugin capability and return its output.
     *
     * @param pluginId The plugin process ID (matches ProcessManifest.processId)
     * @param action   The capability action name (matches PluginCapability.action)
     * @param input    Key-value input parameters
     * @return Key-value output parameters
     * @throws Exception if invocation fails (after any retries handled by [MasteryExecutor])
     */
    suspend fun invoke(
        pluginId: String,
        action: String,
        input: Map<String, String>,
    ): Map<String, String>

    /** Returns all currently available capabilities across all registered plugins. */
    fun getAvailableCapabilities(): List<CapabilityInfo>
}

data class CapabilityInfo(
    val pluginId: String,
    val action: String,
    val description: String,
    val inputSchemaJson: String = "{}",
    val outputSchemaJson: String = "{}",
)
