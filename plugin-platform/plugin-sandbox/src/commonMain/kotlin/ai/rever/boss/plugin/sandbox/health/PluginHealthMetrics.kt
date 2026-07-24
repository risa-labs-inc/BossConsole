package ai.rever.boss.plugin.sandbox.health

/**
 * Health metrics for a plugin sandbox.
 * Tracks heartbeats, errors, and crashes to determine plugin health.
 */
data class PluginHealthMetrics(
    /**
     * Timestamp of the last heartbeat received from the plugin.
     */
    val lastHeartbeat: Long = System.currentTimeMillis(),
    /**
     * Total number of errors recorded since the plugin started.
     */
    val errorCount: Int = 0,
    /**
     * Total number of crashes (sandbox restarts) since the plugin started.
     */
    val crashCount: Int = 0,
    /**
     * Number of consecutive errors without a successful operation.
     * Reset to 0 when an operation succeeds or the plugin restarts.
     */
    val consecutiveErrors: Int = 0,
    /**
     * Number of restart attempts since the plugin was last healthy.
     */
    val restartAttempts: Int = 0,
) {
    companion object {
        /**
         * Creates initial health metrics with current timestamp.
         */
        fun initial(): PluginHealthMetrics = PluginHealthMetrics()
    }

    /**
     * Creates updated metrics after recording a heartbeat.
     */
    fun withHeartbeat(): PluginHealthMetrics =
        copy(
            lastHeartbeat = System.currentTimeMillis(),
        )

    /**
     * Creates updated metrics after recording an error.
     */
    fun withError(): PluginHealthMetrics =
        copy(
            errorCount = errorCount + 1,
            consecutiveErrors = consecutiveErrors + 1,
        )

    /**
     * Creates updated metrics after a successful operation (resets consecutive errors).
     */
    fun withSuccess(): PluginHealthMetrics =
        copy(
            consecutiveErrors = 0,
        )

    /**
     * Creates updated metrics after recording a crash.
     */
    fun withCrash(): PluginHealthMetrics =
        copy(
            crashCount = crashCount + 1,
            restartAttempts = restartAttempts + 1,
        )

    /**
     * Creates updated metrics after a successful restart (resets restart attempts).
     */
    fun withSuccessfulRestart(): PluginHealthMetrics =
        copy(
            consecutiveErrors = 0,
            restartAttempts = 0,
            lastHeartbeat = System.currentTimeMillis(),
        )
}
