package ai.rever.boss.app

import ai.rever.boss.window.NativeSidebarTitleBar
import ai.rever.boss.window.NativeTitleBarAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State

/** Read animation state in this scope so frame updates do not rebuild scaffold actions. */
@Composable
internal fun AnimatedSidebarNativeHeader(
    title: String,
    actions: List<NativeTitleBarAction>,
    sharing: Boolean,
    barRailed: Boolean,
    progress: State<Float>,
    onReady: (Boolean) -> Unit,
) {
    val fraction = if (barRailed) progress.value.coerceIn(0f, 1f) else 1f
    val animated =
        actions.map { action ->
            if (action.id == "sidebar") {
                action.copy(sidebarWidth = action.sidebarWidth * fraction, active = action.active && fraction > 0f)
            } else {
                action
            }
        }
    val ready = NativeSidebarTitleBar(title, animated, sharing)
    SideEffect { onReady(ready) }
}
