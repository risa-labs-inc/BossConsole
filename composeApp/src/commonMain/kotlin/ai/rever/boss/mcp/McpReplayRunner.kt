package ai.rever.boss.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Reserves a replay before dispatch, including the gap before a UI recomposition. */
internal class McpReplayRunner(
    private val scope: CoroutineScope,
) {
    private val active = MutableStateFlow(false)
    val running = active.asStateFlow()

    fun start(action: suspend () -> Unit): Boolean {
        if (!active.compareAndSet(false, true)) return false
        scope.launch { action() }.invokeOnCompletion { active.value = false }
        return true
    }
}
