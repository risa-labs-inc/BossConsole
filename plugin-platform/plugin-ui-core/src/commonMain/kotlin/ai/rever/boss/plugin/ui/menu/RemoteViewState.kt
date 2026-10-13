package ai.rever.boss.plugin.ui.menu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * How many remote views of BOSS are live: a plugin streaming its windows, or an app share.
 *
 * Process-wide, so it is a count and not a flag: two sources must not clear each other's view.
 * While it is above zero, UI that only the local machine can see or operate must not be used.
 */
object RemoteViewState {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /**
     * Returns a handle whose close is idempotent and ends exactly this view.
     *
     * An open native menu cannot be dismissed, but it can be made inert; do that when the first
     * view starts, or the viewer is left with a menu it cannot see that still owns the cursor.
     */
    fun begin(): AutoCloseable {
        val closed = MutableStateFlow(false)
        val previous = _count.getAndUpdate { it + 1 }
        if (previous == 0) NativeContextMenus.hide()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) _count.update { (it - 1).coerceAtLeast(0) }
        }
    }
}
