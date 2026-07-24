package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Right-click menu items shared by every sidebar plugin icon and the
 * overflow "More" button: a single "Sidebar settings" entry that opens
 * the Settings window at the Sidebar section.
 *
 * The section is passed by enum *name* ("SIDEBAR") because
 * `SettingsSection` lives in desktopMain and this helper is commonMain —
 * same convention as the "KEYMAP" literal used by the shortcut help
 * dialog. `SettingsWindow` falls back to its default section for names
 * it doesn't recognise, so a stale string degrades gracefully.
 */
@Composable
fun rememberSidebarSettingsMenuItems(): List<ContextMenuItem> {
    val windowId = LocalWindowId.current
    return remember(windowId) {
        listOf(
            ContextMenuItem(
                text = "Sidebar settings",
                icon = Icons.Default.Settings,
                onClick = {
                    // Null windowId means we're not hosted in a tracked
                    // window (shouldn't happen for the sidebar) — the
                    // settings event needs a window to route to, so do
                    // nothing rather than open settings in every window.
                    windowId?.let { id ->
                        MenuActionsHandler.triggerOpenSettings(id, "SIDEBAR")
                    }
                },
            ),
        )
    }
}
