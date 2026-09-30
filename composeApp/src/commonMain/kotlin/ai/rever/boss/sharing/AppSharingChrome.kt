package ai.rever.boss.sharing

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** Window-scoped sharing status belongs below the scaffold's title-bar inset. */
@Composable
internal expect fun AppSharingChromeVisible(windowId: String): Boolean

@Composable
internal expect fun AppSharingChrome(
    windowId: String,
    startInset: Dp,
)
