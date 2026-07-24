package ai.rever.boss.plugin.sandbox.notification

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.sandbox.PluginSandboxListener
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.plugin.sandbox.notification.PluginNotificationService

/**
 * Connects sandbox lifecycle events to the notification system.
 *
 * This listener bridges [PluginSandboxManager] events with [BossPluginNotificationService]
 * to show toast notifications when plugins encounter issues, restart, or get disabled.
 *
 * Usage:
 * ```kotlin
 * val toastState = PluginToastState(scope)
 * val notificationService = BossPluginNotificationService(
 *     toastController = toastState,
 *     onDisablePlugin = { sandboxManager.disablePlugin(it) },
 *     onEnablePlugin = { sandboxManager.enablePlugin(it) }
 * )
 * val listener = PluginSandboxNotificationListener(notificationService)
 * (sandboxManager as PluginSandboxManagerImpl).addListener(listener)
 * ```
 *
 * @param notificationService The notification service to use for displaying notifications
 */
class PluginSandboxNotificationListener(
    private val notificationService: PluginNotificationService,
) : PluginSandboxListener {
    private val logger = BossLogger.forComponent("PluginSandboxNotificationListener")

    // Track restart attempts for showing attempt number in notifications
    private val restartAttempts = mutableMapOf<String, Int>()

    override fun onPluginRestarting(pluginId: String) {
        val attempt = restartAttempts.getOrDefault(pluginId, 0) + 1
        restartAttempts[pluginId] = attempt

        logger.debug(
            LogCategory.SYSTEM,
            "Plugin restarting notification",
            mapOf(
                "pluginId" to pluginId,
                "attempt" to attempt,
            ),
        )

        notificationService.notifyPluginRestarting(pluginId, attempt)
    }

    override fun onPluginRestarted(pluginId: String) {
        // Reset attempt counter on successful restart
        restartAttempts.remove(pluginId)

        logger.debug(
            LogCategory.SYSTEM,
            "Plugin restarted notification",
            mapOf(
                "pluginId" to pluginId,
            ),
        )

        notificationService.notifyPluginRestartSuccess(pluginId)
    }

    override fun onPluginDisabled(pluginId: String) {
        // Clear attempt counter
        restartAttempts.remove(pluginId)

        logger.debug(
            LogCategory.SYSTEM,
            "Plugin disabled notification",
            mapOf(
                "pluginId" to pluginId,
            ),
        )

        notificationService.notifyPluginDisabled(pluginId)
    }

    override fun onPluginError(
        pluginId: String,
        error: Throwable,
    ) {
        logger.debug(
            LogCategory.SYSTEM,
            "Plugin error notification",
            mapOf(
                "pluginId" to pluginId,
                "error" to (error.message ?: "Unknown error"),
            ),
        )

        notificationService.notifyPluginError(pluginId, error)
    }
}
