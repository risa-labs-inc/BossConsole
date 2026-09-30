package ai.rever.boss.sharing

import ai.rever.boss.layout.TRAFFIC_LIGHT_HEIGHT
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal actual fun AppSharingChromeVisible(windowId: String): Boolean {
    val state by AppSharingService.state.collectAsState()
    return state.hasChrome(windowId)
}

/** Reserves real layout space below the title bar and above browser surfaces. */
@Composable
internal actual fun AppSharingChrome(
    windowId: String,
    startInset: Dp,
) {
    val state by AppSharingService.state.collectAsState()
    val colors = BossTheme.colors
    if (state.hasChrome(windowId)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.panel)
                .heightIn(min = TRAFFIC_LIGHT_HEIGHT)
                .padding(start = startInset + 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                state.status,
                color = colors.textPrimary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.controller) {
                TextButton(
                    onClick = { AppSharingService.takeBackControl() },
                ) { Text("Take back control", color = colors.signal) }
            }
            if (windowId in state.activeWindowIds) {
                TextButton(onClick = { AppSharingService.stop() }) { Text("Stop sharing", color = colors.signal) }
            } else {
                TextButton(onClick = { AppSharingService.dismissStatus() }) {
                    Text("Dismiss", color = colors.signal)
                }
            }
        }
    }
}

private fun AppSharingState.hasChrome(id: String): Boolean = id in activeWindowIds || statusWindowId == id
