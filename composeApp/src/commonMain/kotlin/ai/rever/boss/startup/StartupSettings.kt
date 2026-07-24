package ai.rever.boss.startup

import kotlinx.serialization.Serializable

/**
 * Settings related to application startup behavior.
 */
@Serializable
data class StartupSettings(
    /**
     * Timeout in milliseconds to wait for workspace to load before assuming fresh install.
     * If no workspaces are found within this time, the New Tab dialog will be shown.
     * Default: 1000ms (1 second) - provides adequate time for slower machines or systems under load.
     */
    val workspaceLoadTimeoutMs: Long = 1000L,
)
