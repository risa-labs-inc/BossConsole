package ai.rever.boss.app

import ai.rever.boss.plugin.sandbox.notification.PluginToastState
import ai.rever.boss.plugin.sandbox.notification.ToastDuration
import ai.rever.boss.plugin.sandbox.notification.ToastMessage
import ai.rever.boss.plugin.sandbox.notification.ToastType
import ai.rever.boss.updater.takeCompletedUpdateNotification
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

/** One window owns the success toast; the existing overlay keeps it above browser surfaces. */
@Composable
internal fun rememberUpdateToastState(): PluginToastState {
    val scope = rememberCoroutineScope()
    val toastState = remember { PluginToastState(scope) }
    LaunchedEffect(Unit) {
        takeCompletedUpdateNotification()?.let { version ->
            toastState.show(
                ToastMessage(
                    type = ToastType.SUCCESS,
                    title = "BOSS updated",
                    message = "BOSS updated to v$version",
                    duration = ToastDuration.LONG,
                ),
            )
        }
    }
    return toastState
}
