package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.InvokeCapabilityRequest
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.mastery.CapabilityInfo
import ai.rever.boss.mastery.CapabilityResolver
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Resolves plugin capabilities through the kernel this process registered with.
 *
 * The kernel is the only broker between children: it owns the process registry that
 * RegisterProcess and the spawner populate, while child processes cannot reach each other
 * directly. A resolver built on a locally-constructed ProcessRegistry in this JVM can never
 * see a registration, which is how every mastery execution used to fail with
 * "Process not found" while getAvailableCapabilities() returned nothing (#1061).
 */
class KernelCapabilityResolver(
    internal val kernelStub: KernelServiceGrpcKt.KernelServiceCoroutineStub,
) : CapabilityResolver {
    private val logger = LoggerFactory.getLogger(KernelCapabilityResolver::class.java)

    override suspend fun invoke(
        pluginId: String,
        action: String,
        input: Map<String, String>,
    ): Map<String, String> {
        val response =
            kernelStub.invokeCapability(
                InvokeCapabilityRequest
                    .newBuilder()
                    .setPluginId(pluginId)
                    .setAction(action)
                    .putAllInput(input)
                    .setTimeoutMs(30_000L)
                    .build(),
            )
        if (!response.success) {
            // The kernel states its own fail-closed causes ("Process not found: ..."); surfacing
            // them verbatim names the real reason instead of guessing at a local one.
            throw IllegalStateException(
                response.errorMessage.ifBlank { "Capability invocation failed: $pluginId/$action" },
            )
        }
        logger.debug("Invoked capability {}/{}", pluginId, action)
        return response.outputMap
    }

    /**
     * Lists what the kernel currently advertises. Bridges the suspend RPC synchronously, so a
     * caller must not already be on a dispatcher it cannot afford to park. The deadline keeps
     * a wedged kernel from pinning the calling thread forever.
     */
    override fun getAvailableCapabilities(): List<CapabilityInfo> {
        val response =
            runBlocking {
                kernelStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .listCapabilities(Empty.getDefaultInstance())
            }
        return response.capabilitiesList.map { capability ->
            CapabilityInfo(
                pluginId = capability.pluginId,
                action = capability.action,
                description = capability.description,
                inputSchemaJson = capability.inputSchemaJson,
                outputSchemaJson = capability.outputSchemaJson,
            )
        }
    }

    /**
     * Prove at startup that this kernel can mediate capabilities. An older or misconfigured
     * kernel answers UNIMPLEMENTED or PERMISSION_DENIED, and without this check the
     * orchestrator would start seemingly healthy and fail every execution with
     * "Process not found" at node time. Failing here is loud and immediate instead.
     */
    internal suspend fun verifyKernelMediation() {
        try {
            kernelStub.listCapabilities(Empty.getDefaultInstance())
        } catch (e: StatusException) {
            throw IllegalStateException(
                "Mastery orchestrator cannot resolve plugin capabilities through this kernel " +
                    "(status ${e.status.code}): every mastery execution would fail " +
                    "'Process not found' - refusing to start",
                e,
            )
        }
    }
}
