package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException

/**
 * Kernel-side bridge for `PanelEventService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505. openPanel/closePanel rearrange the
 * operator's live panel layout in a chosen window, so a request with no credential to attribute
 * it to is refused rather than allowed to drive that layout with no audit trail.
 */
class PanelEventServiceBridge(
    private val provider: PanelEventProvider,
) : PanelEventServiceGrpcKt.PanelEventServiceCoroutineImplBase() {
    override suspend fun closePanel(request: ClosePanelRequest): Empty {
        authenticatedCallerOrRefuse("closePanel")
        val panelId =
            PanelId(
                panelId = request.panelId,
                pluginId = request.pluginId,
                defaultOrder = request.defaultOrder,
            )
        provider.closePanel(panelId, request.windowId)
        return Empty.getDefaultInstance()
    }

    override suspend fun openPanel(request: OpenPanelRequest): Empty {
        authenticatedCallerOrRefuse("openPanel")
        val panelId =
            PanelId(
                panelId = request.panelId,
                pluginId = request.pluginId,
                defaultOrder = request.defaultOrder,
            )
        provider.openPanel(panelId, request.windowId)
        return Empty.getDefaultInstance()
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
        val logger = BossLogger.forComponent("PanelEventServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
