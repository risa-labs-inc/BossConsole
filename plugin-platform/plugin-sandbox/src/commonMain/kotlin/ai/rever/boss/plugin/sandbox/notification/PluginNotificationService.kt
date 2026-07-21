package ai.rever.boss.plugin.sandbox.notification

/**
 * Interface for plugin notification services.
 *
 * Implementations handle displaying notifications to users about plugin
 * lifecycle events (errors, restarts, disabling).
 */
interface PluginNotificationService {
    /**
     * Notify that a plugin encountered an error.
     */
    fun notifyPluginError(pluginId: String, error: Throwable)

    /**
     * Notify that a plugin is restarting.
     */
    fun notifyPluginRestarting(pluginId: String, attempt: Int)

    /**
     * Notify that a plugin restart succeeded.
     */
    fun notifyPluginRestartSuccess(pluginId: String)

    /**
     * Notify that a plugin restart failed.
     */
    fun notifyPluginRestartFailed(pluginId: String, error: Throwable)

    /**
     * Notify that a plugin has been disabled.
     */
    fun notifyPluginDisabled(pluginId: String)
}
