package ai.rever.boss.plugin.sandbox

/**
 * Exception wrapper that attributes an error to a specific plugin.
 *
 * Similar to IntelliJ's PluginException - allows any exception to be traced
 * back to its originating plugin for proper error reporting and handling.
 *
 * @property pluginId The unique identifier of the plugin that caused the error
 * @property pluginName Optional human-readable name of the plugin
 */
class PluginException(
    val pluginId: String,
    val pluginName: String? = null,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message ?: cause?.message, cause) {
    companion object {
        /**
         * Wrap any throwable with plugin attribution.
         *
         * If the throwable is already a PluginException, it is returned as-is.
         * Otherwise, a new PluginException is created wrapping the original error.
         *
         * @param pluginId The ID of the plugin that caused the error
         * @param cause The original error
         * @return A PluginException wrapping the error
         */
        fun createByPlugin(
            pluginId: String,
            cause: Throwable,
        ): PluginException =
            if (cause is PluginException) {
                cause
            } else {
                PluginException(
                    pluginId = pluginId,
                    message = "Error in plugin '$pluginId': ${cause.message}",
                    cause = cause,
                )
            }

        /**
         * Wrap any throwable with plugin attribution, including plugin name.
         *
         * @param pluginId The ID of the plugin that caused the error
         * @param pluginName The human-readable name of the plugin
         * @param cause The original error
         * @return A PluginException wrapping the error
         */
        fun createByPlugin(
            pluginId: String,
            pluginName: String,
            cause: Throwable,
        ): PluginException =
            if (cause is PluginException) {
                cause
            } else {
                PluginException(
                    pluginId = pluginId,
                    pluginName = pluginName,
                    message = "Error in plugin '$pluginName' ($pluginId): ${cause.message}",
                    cause = cause,
                )
            }

        /**
         * Extract plugin ID from an exception if it's a PluginException.
         *
         * @param throwable The exception to check
         * @return The plugin ID, or null if the exception is not a PluginException
         */
        fun getPluginId(throwable: Throwable): String? = (throwable as? PluginException)?.pluginId

        /**
         * Check if the given exception is attributed to a specific plugin.
         *
         * @param throwable The exception to check
         * @param pluginId The plugin ID to match
         * @return True if the exception is a PluginException for the given plugin
         */
        fun isFromPlugin(
            throwable: Throwable,
            pluginId: String,
        ): Boolean = getPluginId(throwable) == pluginId
    }

    /**
     * Get a display-friendly name for the plugin.
     * Returns pluginName if available, otherwise returns pluginId.
     */
    val displayName: String
        get() = pluginName ?: pluginId
}
