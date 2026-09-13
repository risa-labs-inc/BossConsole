package ai.rever.boss.app

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/** Measures even while the actions live elsewhere, so growing the rail can reclaim them. */
@Composable
internal fun MeasuredRailHostActions(
    actions: List<(@Composable () -> Unit)?>,
    showActions: Boolean,
    onFitsChange: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fits = railFitsActions(maxHeight, actions.filterNotNull().size)
        val report by rememberUpdatedState(onFitsChange)
        LaunchedEffect(fits) { report(fits) }
        if (showActions && fits) VerticalBarRailActions(actions)
    }
}
