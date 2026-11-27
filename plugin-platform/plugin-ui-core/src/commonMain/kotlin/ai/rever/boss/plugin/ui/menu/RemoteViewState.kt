package ai.rever.boss.plugin.ui.menu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * How many remote views of BOSS are live: a plugin streaming its windows, or an app share.
 *
 * Process-wide, so it is a count and not a flag: two sources must not clear each other's view.
 * While it is above zero, UI that only the local machine can see or operate must not be used.
 *
 * Threading: [begin] may run on any thread. The 0 to 1 transition calls [NativeContextMenus.hide],
 * whose desktop implementation marshals onto the EDT itself (`onEdt`), so no caller needs to.
 */
object RemoteViewState {
    private val shared = RemoteViewCounter { NativeContextMenus.hide() }

    val count: StateFlow<Int> get() = shared.count

    /**
     * Returns a handle whose close is idempotent and ends exactly this view.
     *
     * An open native menu cannot be dismissed, but it can be made inert; do that when the first
     * view starts, or the viewer is left with a menu it cannot see that still owns the cursor.
     */
    fun begin(): AutoCloseable = shared.begin()
}

internal class RemoteViewCounter(
    private val onFirstView: () -> Unit,
) {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun begin(): AutoCloseable {
        val closed = AtomicBoolean(false)
        val previous = _count.getAndUpdate { it + 1 }
        if (previous == 0) onFirstView()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                val before = _count.getAndUpdate { if (it > 0) it - 1 else it }
                // Per-handle idempotency makes this unreachable; surface it rather than clamp silently.
                if (before <= 0) LOG.severe("RemoteViewState underflow: a handle closed with no live view")
            }
        }
    }

    private companion object {
        val LOG: Logger = Logger.getLogger("RemoteViewState")
    }
}
