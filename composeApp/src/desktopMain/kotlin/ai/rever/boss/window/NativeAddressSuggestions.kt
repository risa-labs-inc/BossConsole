package ai.rever.boss.window

import ai.rever.boss.components.overlays.HeavyweightCorner
import ai.rever.boss.plugin.browser.LocalAwtWindow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.RootPaneContainer

/** A content-sized owned window keeps the browser visible and the native field focused. */
@Composable
internal fun NativeAddressSuggestions(
    controller: MacSidebarToolbar?,
    actions: List<NativeTitleBarAction>,
) {
    val address = actions.firstOrNull { it.textInput != null }?.textInput?.address
    val parent = LocalAwtWindow.current
    val bounds = controller?.addressField?.bounds?.value
    if (address?.showSuggestions == true && bounds != null && parent != null) {
        val content = (parent as? RootPaneContainer)?.contentPane
        val inset = content?.let { javax.swing.SwingUtilities.convertPoint(it, 0, 0, parent) }
        val left = (bounds.left - (inset?.x ?: 0) - 8).coerceAtLeast(0)
        val top = (bounds.bottom - (inset?.y ?: 0)).coerceAtLeast(0)
        val width = (bounds.width + 16).coerceAtLeast(480)
        val region = IntRect(left, top, left + width, top + 420)
        DismissSuggestionsOnDeactivate(parent, address.onDismiss)
        HeavyweightCorner(
            alignment = Alignment.TopStart,
            initialSize = DpSize(width.dp, 420.dp),
            regionInWindow = region,
            focusable = false,
        ) {
            Box(Modifier.padding(8.dp)) { address.suggestions() }
        }
    }
}

@Composable
private fun DismissSuggestionsOnDeactivate(
    parent: Window,
    onDismiss: () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(parent) {
        val listener =
            object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent) = dismiss()
            }
        parent.addWindowFocusListener(listener)
        onDispose { parent.removeWindowFocusListener(listener) }
    }
}
