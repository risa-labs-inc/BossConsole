package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.sandbox.notification.ToastAction
import ai.rever.boss.plugin.sandbox.notification.ToastController
import ai.rever.boss.plugin.sandbox.notification.ToastDuration
import ai.rever.boss.plugin.sandbox.notification.ToastMessage
import ai.rever.boss.plugin.sandbox.notification.ToastType
import java.util.UUID

/**
 * Desktop implementation of NotificationProvider factory.
 */
actual fun createNotificationProvider(toastController: ToastController): NotificationProvider {
    return NotificationProviderImpl(toastController)
}

/**
 * Desktop implementation of NotificationProvider that delegates to the existing toast system.
 */
class NotificationProviderImpl(
    private val toastController: ToastController
) : NotificationProvider {

    override fun showToast(
        message: String,
        type: NotificationType,
        duration: NotificationDuration,
        title: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?
    ): String {
        val notificationId = UUID.randomUUID().toString()

        val toastMessage = ToastMessage(
            id = notificationId,
            type = type.toToastType(),
            title = title ?: type.defaultTitle(),
            message = message,
            action = if (actionLabel != null && onAction != null) {
                ToastAction(actionLabel, onAction)
            } else null,
            duration = duration.toToastDuration()
        )

        toastController.show(toastMessage)
        return notificationId
    }

    override fun dismiss(notificationId: String) {
        toastController.dismiss(notificationId)
    }

    override fun dismissAll() {
        toastController.dismissAll()
    }

    private fun NotificationType.toToastType(): ToastType = when (this) {
        NotificationType.INFO -> ToastType.INFO
        NotificationType.SUCCESS -> ToastType.SUCCESS
        NotificationType.WARNING -> ToastType.WARNING
        NotificationType.ERROR -> ToastType.ERROR
    }

    private fun NotificationType.defaultTitle(): String = when (this) {
        NotificationType.INFO -> "Info"
        NotificationType.SUCCESS -> "Success"
        NotificationType.WARNING -> "Warning"
        NotificationType.ERROR -> "Error"
    }

    private fun NotificationDuration.toToastDuration(): ToastDuration = when (this) {
        NotificationDuration.SHORT -> ToastDuration.SHORT
        NotificationDuration.LONG -> ToastDuration.LONG
        NotificationDuration.INDEFINITE -> ToastDuration.INDEFINITE
    }
}
