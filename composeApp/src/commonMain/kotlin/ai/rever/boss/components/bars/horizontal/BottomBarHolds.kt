package ai.rever.boss.components.bars.horizontal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What is keeping one window's bottom bar composed, whatever focus mode's auto-hide says.
 *
 * A dialog opened from a bottom-bar item is composed inside that item, so it exists only while the
 * bar does. In focus mode the bar is shown only while the pointer is over it, and opening a modal
 * moves the pointer onto the dialog: the bar hid and took the dialog with it. An item that opens a
 * dialog holds the bar for as long as the dialog is up, the way Terminal setup keeps it for its
 * reopened dialog ([setupKeepsBottomBarVisible]).
 */
internal class BottomBarHolds {
    private val keys = mutableStateListOf<String>()

    /** True while anything holds the bar. Snapshot state, so the scaffold recomposes when it changes. */
    val active: Boolean get() = keys.isNotEmpty()

    fun hold(key: String) {
        if (key !in keys) keys += key
    }

    fun release(key: String) {
        keys.remove(key)
    }
}

/** The window's [BottomBarHolds], or null outside a window scaffold, where there is no bar to hold. */
internal val LocalBottomBarHolds = staticCompositionLocalOf<BottomBarHolds?> { null }

/** Keep this window's bottom bar composed for as long as the caller is. */
@Composable
internal fun HoldBottomBar(key: String) {
    val holds = LocalBottomBarHolds.current
    if (holds != null) {
        DisposableEffect(holds, key) {
            holds.hold(key)
            onDispose { holds.release(key) }
        }
    }
}
