package ai.rever.boss.sharing

import ai.rever.boss.plugin.sandbox.notification.PluginToastState
import ai.rever.boss.plugin.sandbox.notification.ToastAction
import ai.rever.boss.plugin.sandbox.notification.ToastDuration
import ai.rever.boss.plugin.sandbox.notification.ToastMessage
import ai.rever.boss.plugin.sandbox.notification.ToastType
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.NativeTitleBarAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
internal actual fun AppSharingActive(windowId: String): Boolean {
    val state by AppSharingService.state.collectAsState()
    return windowId in state.activeWindowIds
}

@Composable
internal actual fun AppSharingTitleBarAction(
    windowId: String,
    shareTab: (() -> Unit)?,
): NativeTitleBarAction {
    val state by AppSharingService.state.collectAsState()
    val tabAction = shareTab?.let { NativeTitleBarAction("browser_share", "Share tab", "qrcode", onClick = it) }
    return NativeTitleBarAction(
        id = "sharing",
        label = "Sharing",
        symbol = "square.and.arrow.up",
        active = windowId in state.activeWindowIds,
        menu = listOfNotNull(tabAction) + appWindowSharingActions(windowId, state),
        onClick = {},
    )
}

internal fun appWindowSharingActions(
    windowId: String,
    state: AppSharingState,
): List<NativeTitleBarAction> =
    listOfNotNull(
        NativeTitleBarAction(
            id = "share_app_window",
            label = "Share BossConsole Window",
            active = windowId in state.activeWindowIds,
            enabled = !state.busy && windowId !in state.activeWindowIds,
            localOnly = true,
            onClick = { AppSharingService.start(windowId) },
        ),
        NativeTitleBarAction(
            id = "share_selected_app_windows",
            label = "Share Selected BossConsole Windows",
            enabled =
                !state.busy && state.selectedWindowIds.isNotEmpty() &&
                    state.selectedWindowIds != state.activeWindowIds,
            localOnly = true,
            onClick = { AppSharingService.startSelectedWindows() },
        ),
        NativeTitleBarAction(
            id = "stop_app_sharing",
            label = if (state.activeWindowIds.isEmpty()) "Cancel BossConsole Sharing" else "Stop BossConsole Sharing",
            onClick = { AppSharingService.stop() },
        ).takeIf { state.busy || state.activeWindowIds.isNotEmpty() },
        NativeTitleBarAction(
            id = "app_sharing_settings",
            label = "Sharing Settings",
            onClick = { MenuActionsHandler.triggerOpenSettings(windowId, "SHARING") },
        ),
    )

@Composable
internal actual fun AppSharingNotifications(
    windowId: String,
    toastState: PluginToastState,
) {
    val state by AppSharingService.state.collectAsState()
    val sharing = windowId in state.activeWindowIds
    val status = state.status.takeIf { sharing || state.statusWindowId == windowId }.orEmpty()
    val statusId = "boss-app-sharing-$windowId"
    val controlId = "boss-app-control-$windowId"
    LaunchedEffect(toastState, sharing, status) {
        toastState.dismiss(statusId)
        if (status.isNotBlank()) {
            toastState.show(
                ToastMessage(
                    id = statusId,
                    type = if (sharing) ToastType.INFO else ToastType.WARNING,
                    title = if (sharing) "Sharing BossConsole" else "BossConsole sharing",
                    message = status,
                    action = if (sharing) ToastAction("Stop sharing") { AppSharingService.stop() } else null,
                    duration = if (sharing) ToastDuration.INDEFINITE else ToastDuration.LONG,
                ),
            )
        }
    }
    LaunchedEffect(toastState, sharing, state.controller) {
        toastState.dismiss(controlId)
        if (sharing && state.controller) {
            toastState.show(
                ToastMessage(
                    id = controlId,
                    type = ToastType.INFO,
                    title = "Remote control active",
                    message = "Another device is controlling this BossConsole window.",
                    action = ToastAction("Take back control") { AppSharingService.takeBackControl() },
                    duration = ToastDuration.INDEFINITE,
                ),
            )
        }
    }
    DisposableEffect(toastState, windowId) {
        onDispose {
            toastState.dismiss(statusId)
            toastState.dismiss(controlId)
        }
    }
}
