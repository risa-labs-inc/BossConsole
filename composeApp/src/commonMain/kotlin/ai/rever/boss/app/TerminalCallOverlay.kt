package ai.rever.boss.app

import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** The live call belongs to the window, not the selected terminal or browser tab. */
@Composable
internal fun BoxScope.TerminalCallOverlay(windowId: String) {
    if (!TerminalTitleBarBridge.isHosted(windowId) || !TerminalTitleBarBridge.hasCallBar(windowId)) return
    if (!LocalWindowInfo.current.isWindowFocused) {
        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
            TerminalTitleBarBridge.CallBarContent(windowId)
        }
        return
    }
    OverlayCorner(alignment = Alignment.BottomEnd, initialSize = DpSize(480.dp, 56.dp)) {
        Box(Modifier.padding(12.dp)) {
            TerminalTitleBarBridge.CallBarContent(windowId)
        }
    }
}
