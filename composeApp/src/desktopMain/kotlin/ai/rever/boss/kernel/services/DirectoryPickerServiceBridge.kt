package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Kernel-side bridge for `DirectoryPickerService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505. pickDirectory drives the host's
 * native directory dialog in front of the operator, so a request with no credential to
 * attribute it to is refused rather than allowed to summon it.
 */
class DirectoryPickerServiceBridge(
    private val provider: DirectoryPickerProvider,
) : DirectoryPickerServiceGrpcKt.DirectoryPickerServiceCoroutineImplBase() {
    override suspend fun pickDirectory(request: Empty): DirectoryPickerResponse {
        authenticatedCallerOrRefuse("pickDirectory")
        val path =
            suspendCancellableCoroutine<String?> { cont ->
                provider.pickDirectory { result ->
                    cont.resume(result)
                }
            }
        return DirectoryPickerResponse
            .newBuilder()
            .setSelected(path != null)
            .setPath(path ?: "")
            .build()
    }

    /**
     * Unary RPCs only: the verified identity, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: refuseIdentity(rpc)

    private fun refuseIdentity(rpc: String): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private companion object {
        val logger = BossLogger.forComponent("DirectoryPickerServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
