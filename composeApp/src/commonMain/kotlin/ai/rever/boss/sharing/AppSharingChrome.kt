package ai.rever.boss.sharing

import ai.rever.boss.plugin.sandbox.notification.PluginToastState
import ai.rever.boss.window.NativeTitleBarAction
import androidx.compose.runtime.Composable

/** Sharing uses the window's existing notification surface without reserving layout space. */
@Composable
internal expect fun AppSharingActive(windowId: String): Boolean

/** Native title-bar menu; capture-start entries require local input. */
@Composable
internal expect fun AppSharingTitleBarAction(
    windowId: String,
    shareTab: (() -> Unit)?,
): NativeTitleBarAction

@Composable
internal expect fun AppSharingNotifications(
    windowId: String,
    toastState: PluginToastState,
)
