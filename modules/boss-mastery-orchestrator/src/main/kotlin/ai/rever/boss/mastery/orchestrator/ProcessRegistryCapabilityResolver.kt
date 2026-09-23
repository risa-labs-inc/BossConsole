package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.CapabilityServiceGrpcKt
import ai.rever.boss.ipc.proto.InvokeCapabilityRequest
import ai.rever.boss.ipc.proto.InvokeCapabilityResponse
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessRegistry
import org.slf4j.LoggerFactory

/**
 * Dispatch boundary between a mastery node and the plugin process it names.
 *
 * Every invoke is admitted by [ProcessRegistry.admitCapabilityDispatch] first: the node's
 * (pluginId, action) is re-checked at dispatch time against the owning plugin's live
 * registration, so an action the plugin never advertised - including one borrowed from
 * another plugin's namespace - and a plugin that has crashed or been stopped since
 * registration are both refused before any dial. Registration-time knowledge alone is
 * never authority at this boundary.
 *
 * @param dial transport seam for the admitted dispatch; the default dials the process's
 * own IPC channel. Injected by tests to record what the boundary lets through.
 */
class ProcessRegistryCapabilityResolver(
    private val processRegistry: ProcessRegistry,
    private val dial: suspend (ManagedProcess, InvokeCapabilityRequest) -> InvokeCapabilityResponse =
        ::dialAdmittedCapability,
) : CapabilityResolver {
    private val logger = LoggerFactory.getLogger(ProcessRegistryCapabilityResolver::class.java)

    override suspend fun invoke(
        pluginId: String,
        action: String,
        input: Map<String, String>,
    ): Map<String, String> {
        val process = processRegistry.admitCapabilityDispatch(pluginId, action)

        val response =
            dial(
                process,
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

/**
 * Default transport for an admitted dispatch: dial the process's own IPC channel. Reached
 * only after admission, which is why a missing client here is a transport failure of a
 * granted call, not a capability-scope refusal.
 */
private suspend fun dialAdmittedCapability(
    process: ManagedProcess,
    request: InvokeCapabilityRequest,
): InvokeCapabilityResponse {
    val ipcClient =
        process.ipcClient
            ?: error("No IPC client for process: ${request.pluginId}")

    val stub = CapabilityServiceGrpcKt.CapabilityServiceCoroutineStub(ipcClient.channel)
    return stub.invokeCapability(request)
}
