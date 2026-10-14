package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import ai.rever.boss.plugin.api.NotificationDuration as ApiNotificationDuration
import ai.rever.boss.plugin.api.NotificationType as ApiNotificationType

/**
 * Kernel-side bridge for `NotificationService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505. A toast is a host-rendered surface
 * that carries caller-chosen text into the operator's field of view, and dismiss/dismissAll can
 * silence a toast another process raised, so a request with no credential to attribute it to is
 * refused rather than allowed to spoof or suppress UI notices with no audit trail.
 */
class NotificationServiceBridge(
    private val provider: NotificationProvider,
) : NotificationServiceGrpcKt.NotificationServiceCoroutineImplBase() {
    override suspend fun showToast(request: ShowToastRequest): NotificationIdResponse {
        authenticatedCallerOrRefuse("showToast")
        val type =
            when (request.type) {
                NotificationType.NOTIFICATION_TYPE_SUCCESS -> ApiNotificationType.SUCCESS
                NotificationType.NOTIFICATION_TYPE_WARNING -> ApiNotificationType.WARNING
                NotificationType.NOTIFICATION_TYPE_ERROR -> ApiNotificationType.ERROR
                else -> ApiNotificationType.INFO
            }
        val duration =
            when (request.duration) {
                NotificationDuration.NOTIFICATION_DURATION_LONG -> ApiNotificationDuration.LONG
                NotificationDuration.NOTIFICATION_DURATION_INDEFINITE -> ApiNotificationDuration.INDEFINITE
                else -> ApiNotificationDuration.SHORT
            }
        val id =
            provider.showToast(
                message = request.message,
                type = type,
                duration = duration,
                title = request.title.ifEmpty { null },
                actionLabel = request.actionLabel.ifEmpty { null },
            )
        return NotificationIdResponse.newBuilder().setNotificationId(id).build()
    }

    override suspend fun dismiss(request: NotificationIdRequest): Empty {
        authenticatedCallerOrRefuse("dismiss")
        provider.dismiss(request.notificationId)
        return Empty.getDefaultInstance()
    }

    override suspend fun dismissAll(request: Empty): Empty {
        authenticatedCallerOrRefuse("dismissAll")
        provider.dismissAll()
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
        val logger = BossLogger.forComponent("NotificationServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
