package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * IPC proxy implementation of NotificationProvider.
 */
class NotificationProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : NotificationProvider {

    private val stub = NotificationServiceGrpcKt.NotificationServiceCoroutineStub(channel)

    override fun showToast(
        message: String,
        type: NotificationType,
        duration: NotificationDuration,
        title: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ): String = try {
        runBlocking {
            val resp = stub.showToast(
                ShowToastRequest.newBuilder()
                    .setMessage(message)
                    .setType(type.toProto())
                    .setDuration(duration.toProto())
                    .setTitle(title ?: "")
                    .setActionLabel(actionLabel ?: "")
                    .build()
            )
            resp.notificationId
        }
    } catch (_: Exception) { "" }

    override fun dismiss(notificationId: String) {
        scope.launch {
            try {
                stub.dismiss(NotificationIdRequest.newBuilder().setNotificationId(notificationId).build())
            } catch (_: Exception) {}
        }
    }

    override fun dismissAll() {
        scope.launch {
            try {
                stub.dismissAll(ai.rever.boss.ipc.proto.Empty.getDefaultInstance())
            } catch (_: Exception) {}
        }
    }

    private fun NotificationType.toProto(): ai.rever.boss.ipc.proto.services.NotificationType = when (this) {
        NotificationType.INFO -> ai.rever.boss.ipc.proto.services.NotificationType.NOTIFICATION_TYPE_INFO
        NotificationType.SUCCESS -> ai.rever.boss.ipc.proto.services.NotificationType.NOTIFICATION_TYPE_SUCCESS
        NotificationType.WARNING -> ai.rever.boss.ipc.proto.services.NotificationType.NOTIFICATION_TYPE_WARNING
        NotificationType.ERROR -> ai.rever.boss.ipc.proto.services.NotificationType.NOTIFICATION_TYPE_ERROR
    }

    private fun NotificationDuration.toProto(): ai.rever.boss.ipc.proto.services.NotificationDuration = when (this) {
        NotificationDuration.SHORT -> ai.rever.boss.ipc.proto.services.NotificationDuration.NOTIFICATION_DURATION_SHORT
        NotificationDuration.LONG -> ai.rever.boss.ipc.proto.services.NotificationDuration.NOTIFICATION_DURATION_LONG
        NotificationDuration.INDEFINITE -> ai.rever.boss.ipc.proto.services.NotificationDuration.NOTIFICATION_DURATION_INDEFINITE
    }
}
