package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.CapabilityServiceGrpcKt
import ai.rever.boss.ipc.proto.InvokeCapabilityRequest
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.process.ProcessRegistry
import org.slf4j.LoggerFactory

class ProcessRegistryCapabilityResolver(
    private val processRegistry: ProcessRegistry,
) : CapabilityResolver {
    private val logger = LoggerFactory.getLogger(ProcessRegistryCapabilityResolver::class.java)

    override suspend fun invoke(
        pluginId: String,
        action: String,
        input: Map<String, String>,
    ): Map<String, String> {
        val process =
            processRegistry.getProcess(pluginId)
                ?: throw IllegalStateException("Process not found: $pluginId")
        val ipcClient =
            process.ipcClient
                ?: throw IllegalStateException("No IPC client for process: $pluginId")

        val stub = CapabilityServiceGrpcKt.CapabilityServiceCoroutineStub(ipcClient.channel)
        val response =
            stub.invokeCapability(
                InvokeCapabilityRequest
                    .newBuilder()
                    .setPluginId(pluginId)
                    .setAction(action)
                    .putAllInput(input)
                    .setTimeoutMs(30_000L)
                    .build(),
            )

        if (!response.success) {
            throw RuntimeException("Capability invocation failed: ${response.errorMessage}")
        }

        logger.debug("Invoked capability {}/{}: {} -> {}", pluginId, action, input.keys, response.outputMap.keys)
        return response.outputMap
    }

    override fun getAvailableCapabilities(): List<CapabilityInfo> =
        processRegistry.getAllProcesses().flatMap { process ->
            val manifest = processRegistry.getManifest(process.config.processId)
            manifest?.capabilitiesList?.map { cap ->
                CapabilityInfo(
                    pluginId = process.config.processId,
                    action = cap.action,
                    description = cap.description,
                    inputSchemaJson = cap.inputSchemaJson,
                    outputSchemaJson = cap.outputSchemaJson,
                )
            } ?: emptyList()
        }
}
