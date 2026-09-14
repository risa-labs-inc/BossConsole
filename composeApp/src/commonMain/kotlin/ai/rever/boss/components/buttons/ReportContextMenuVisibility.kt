package ai.rever.boss.components.buttons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Pairs each menu-open report with cleanup on close or removal of the tab. */
@Composable
internal fun ReportContextMenuVisibility(
    open: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val latest by rememberUpdatedState(onChange)
    DisposableEffect(open) {
        // A value parameter captures this effect's state, unlike reading a mutable delegate on disposal.
        if (open) latest(true)
        onDispose { if (open) latest(false) }
    }
}
