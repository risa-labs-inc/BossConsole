package ai.rever.boss.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The deployment-level refusal for YOLO mode: `BOSS_MCP_YOLO_DISABLED=true` (also `1` / `yes` /
 * `on`), or the `boss.mcp.yolo.disabled` system property. A managed install sets it to take the
 * option away entirely - both entry points are hidden and turning it on is refused - whatever an
 * individual user would choose.
 *
 * Read straight from the environment and system properties, not through `ConfigLoader`, for the
 * same reason as the MCP kill switch and `BOSS_BROWSER_TELEMETRY_DISABLED`: an operator control
 * that must hold before any settings exist, never a Settings row. Read once per process.
 */
object McpYoloGate {
    private const val DISABLED_KEY = "BOSS_MCP_YOLO_DISABLED"
    private const val DISABLED_PROPERTY = "boss.mcp.yolo.disabled"

    val disabledByDeployment: Boolean by lazy {
        disabledFrom(System.getenv(DISABLED_KEY), System.getProperty(DISABLED_PROPERTY))
    }

    /**
     * `isNotBlank` on the env var, because `KEY=` is a common way to "unset" one in a launcher
     * script and must not shadow the system property.
     */
    internal fun disabledFrom(
        env: String?,
        property: String?,
    ): Boolean {
        val raw = (env?.takeIf { it.isNotBlank() } ?: property)?.trim()?.lowercase() ?: return false
        return raw == "true" || raw == "1" || raw == "yes" || raw == "on"
    }
}

/**
 * Which window has asked to confirm turning YOLO mode on. One confirmation for both entry points -
 * the bottom bar's MCP access menu and the application menu's Tools item - composed by each
 * window's `BossAppDialogs`, so it still appears when the bottom bar is hidden (Focus mode hides
 * it by default), which is exactly when the menu item is the way in.
 */
object McpYoloPrompt {
    private val _requestedBy = MutableStateFlow<String?>(null)
    val requestedBy: StateFlow<String?> = _requestedBy.asStateFlow()

    fun request(windowId: String) {
        _requestedBy.value = windowId
    }

    fun dismiss() {
        _requestedBy.value = null
    }
}
