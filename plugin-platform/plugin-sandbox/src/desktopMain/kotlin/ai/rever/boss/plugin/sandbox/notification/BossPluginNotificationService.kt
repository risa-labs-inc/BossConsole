package ai.rever.boss.plugin.sandbox.notification

import ai.rever.boss.plugin.sandbox.notification.PluginNotificationService
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory

/**
 * Plugin notification service that displays status via toast messages.
 *
 * Integrates with the app's toast system to show:
 * - Plugin errors (with "Disable" action)
 * - Restart in progress notifications
 * - Restart success (auto-dismiss)
 * - Plugin disabled (with "Re-enable" action)
 *
 * @property toastController Controller for displaying toast messages
 * @property onDisablePlugin Callback when user clicks "Disable" action
 * @property onEnablePlugin Callback when user clicks "Re-enable" action
 * @property onShowErrorDetails Callback when user clicks "Details" action
 */
class BossPluginNotificationService(
    private val toastController: ToastController,
    private val onDisablePlugin: (pluginId: String) -> Unit = {},
    private val onEnablePlugin: (pluginId: String) -> Unit = {},
    private val onShowErrorDetails: (pluginId: String, error: Throwable) -> Unit = { _, _ -> }
) : PluginNotificationService {

    private val logger = BossLogger.forComponent("BossPluginNotificationService")

    override fun notifyPluginError(pluginId: String, error: Throwable) {
        logger.warn(LogCategory.SYSTEM, "Notifying plugin error", mapOf(
            "pluginId" to pluginId,
            "error" to (error.message ?: "Unknown error")
        ))

        toastController.show(
            ToastMessage(
                type = ToastType.WARNING,
                title = "Plugin Error",
                message = "Plugin '$pluginId' encountered an error: ${error.message ?: "Unknown error"}",
                action = ToastAction("Disable") { onDisablePlugin(pluginId) },
                duration = ToastDuration.LONG
            )
        )
    }

    override fun notifyPluginRestarting(pluginId: String, attempt: Int) {
        logger.info(LogCategory.SYSTEM, "Notifying plugin restarting", mapOf(
            "pluginId" to pluginId,
            "attempt" to attempt
        ))

        toastController.show(
            ToastMessage(
                type = ToastType.INFO,
                title = "Restarting Plugin",
                message = "Plugin '$pluginId' is restarting (attempt $attempt)...",
                duration = ToastDuration.SHORT
            )
        )
    }

    override fun notifyPluginRestartSuccess(pluginId: String) {
        logger.info(LogCategory.SYSTEM, "Notifying plugin restart success", mapOf(
            "pluginId" to pluginId
        ))

        toastController.show(
            ToastMessage(
                type = ToastType.SUCCESS,
                title = "Plugin Recovered",
                message = "Plugin '$pluginId' restarted successfully",
                duration = ToastDuration.SHORT
            )
        )
    }

    override fun notifyPluginRestartFailed(pluginId: String, error: Throwable) {
        logger.error(LogCategory.SYSTEM, "Notifying plugin restart failed", mapOf(
            "pluginId" to pluginId
        ), error)

        toastController.show(
            ToastMessage(
                type = ToastType.ERROR,
                title = "Restart Failed",
                message = "Plugin '$pluginId' failed to restart: ${error.message ?: "Unknown error"}",
                action = ToastAction("Details") { onShowErrorDetails(pluginId, error) },
                duration = ToastDuration.LONG
            )
        )
    }

    override fun notifyPluginDisabled(pluginId: String) {
        logger.error(LogCategory.SYSTEM, "Notifying plugin disabled", mapOf(
            "pluginId" to pluginId
        ))

        toastController.show(
            ToastMessage(
                type = ToastType.ERROR,
                title = "Plugin Disabled",
                message = "Plugin '$pluginId' has been disabled due to repeated failures",
                action = ToastAction("Re-enable") { onEnablePlugin(pluginId) },
                duration = ToastDuration.INDEFINITE
            )
        )
    }

    /**
     * Show a notification that a plugin is now enabled.
     */
    fun notifyPluginEnabled(pluginId: String) {
        logger.info(LogCategory.SYSTEM, "Notifying plugin enabled", mapOf(
            "pluginId" to pluginId
        ))

        toastController.show(
            ToastMessage(
                type = ToastType.SUCCESS,
                title = "Plugin Enabled",
                message = "Plugin '$pluginId' has been enabled",
                duration = ToastDuration.SHORT
            )
        )
    }

    /**
     * Show a notification about plugin health status change.
     */
    fun notifyPluginUnhealthy(pluginId: String, consecutiveErrors: Int) {
        logger.warn(LogCategory.SYSTEM, "Notifying plugin unhealthy", mapOf(
            "pluginId" to pluginId,
            "consecutiveErrors" to consecutiveErrors
        ))

        toastController.show(
            ToastMessage(
                type = ToastType.WARNING,
                title = "Plugin Unhealthy",
                message = "Plugin '$pluginId' has $consecutiveErrors consecutive errors",
                action = ToastAction("Restart") { /* Will be handled by listener */ },
                duration = ToastDuration.LONG
            )
        )
    }
}
