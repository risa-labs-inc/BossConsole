package ai.rever.boss.updater

import ai.rever.boss.window.WindowManager
import androidx.compose.runtime.Composable

/**
 * Desktop: the current first window in [WindowManager.windows] owns the dialog.
 * The list is a SnapshotStateList, so this recomposes when windows open/close —
 * if the owner window closes, the next window becomes the owner automatically.
 */
@Composable
actual fun rememberUpdateDialogOwnership(windowId: String): Boolean {
    return WindowManager.windows.firstOrNull()?.id == windowId
}
