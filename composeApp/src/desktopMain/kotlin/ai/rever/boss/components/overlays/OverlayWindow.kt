package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Dialog
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt

/** On macOS, overlays belong to their owner rather than floating over every Space and app. */
@Composable
internal fun OverlayWindow(
    onCloseRequest: () -> Unit,
    state: WindowState,
    focusable: Boolean,
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable (java.awt.Window) -> Unit,
) {
    val parent = LocalAwtWindow.current
    if (!SystemUtils.isMacOS || parent == null) {
        Window(
            onCloseRequest,
            state = state,
            undecorated = true,
            transparent = true,
            alwaysOnTop = true,
            focusable = focusable,
            resizable = false,
            icon = BossWindowIcon.painter,
            onKeyEvent = onKeyEvent,
        ) {
            ApplyBossWindowIcon(window)
            content(window)
        }
    } else {
        DialogWindow(
            create = {
                ComposeDialog(parent, Dialog.ModalityType.MODELESS).apply {
                    isUndecorated = true
                    isTransparent = true
                    type = java.awt.Window.Type.UTILITY
                    isResizable = false
                    focusableWindowState = focusable
                    isAutoRequestFocus = focusable
                    background = java.awt.Color(0, 0, 0, 0)
                }
            },
            dispose = ComposeDialog::dispose,
            update = { dialog ->
                val at = state.position
                if (at is WindowPosition.Absolute) dialog.setLocation(at.x.value.toInt(), at.y.value.toInt())
                dialog.setSize(
                    state.size.width.value
                        .roundToInt(),
                    state.size.height.value
                        .roundToInt(),
                )
            },
            onKeyEvent = onKeyEvent,
        ) {
            ApplyBossWindowIcon(window)
            OverlayCloseListener(window, onCloseRequest)
            content(window)
        }
    }
}

@Composable
private fun OverlayCloseListener(
    window: java.awt.Window,
    onClose: () -> Unit,
) {
    val current by rememberUpdatedState(onClose)
    DisposableEffect(window) {
        val listener =
            object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) = current()
            }
        window.addWindowListener(listener)
        onDispose { window.removeWindowListener(listener) }
    }
}
