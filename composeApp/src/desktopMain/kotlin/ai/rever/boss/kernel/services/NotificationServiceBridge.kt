package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationDuration as ApiNotificationDuration
import ai.rever.boss.plugin.api.NotificationType as ApiNotificationType

class NotificationServiceBridge(
    private val provider: NotificationProvider,
) : NotificationServiceGrpcKt.NotificationServiceCoroutineImplBase() {
    override suspend fun showToast(request: ShowToastRequest): NotificationIdResponse {
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
        provider.dismiss(request.notificationId)
        return Empty.getDefaultInstance()
    }

    override suspend fun dismissAll(request: Empty): Empty {
        provider.dismissAll()
        return Empty.getDefaultInstance()
    }
}
