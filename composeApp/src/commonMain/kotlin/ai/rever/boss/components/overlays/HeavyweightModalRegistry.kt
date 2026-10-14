package ai.rever.boss.components.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide count of composed heavyweight modals, including plugin dialogs and the update prompt.
 * UI-thread only: [TrackHeavyweightModal] pairs each acquisition with composition disposal.
 *
 * Only the update prompt yields to this count. Other autonomous prompts (including timed MCP
 * approvals) remain ungated. Native and Settings lightweight dialogs are not represented here.
 * The process-wide OFF_SCREEN path never acquires a registration.
 */
object HeavyweightModalRegistry {
    var openCount: Int by mutableStateOf(0)
        private set

    /** Called by [HeavyweightModal] when it enters composition. UI thread only. */
    fun acquire() {
        openCount += 1
    }

    /** Called by [HeavyweightModal] when it leaves composition. UI thread only. */
    fun release() {
        openCount = (openCount - 1).coerceAtLeast(0)
    }
}

/** Register in the caller's composition, before entering Window's separate content composition. */
@Composable
internal fun TrackHeavyweightModal() {
    DisposableEffect(Unit) {
        HeavyweightModalRegistry.acquire()
        onDispose { HeavyweightModalRegistry.release() }
    }
}
