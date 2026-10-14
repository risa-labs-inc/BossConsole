package ai.rever.boss.app

import androidx.compose.runtime.mutableStateListOf

/**
 * A plugin action link held for the operator.
 *
 * BOSS reaches this state when a `boss://plugin?id=…&action=…` request arrives
 * over a path any program can drive, rather than from the operator's own `boss`
 * invocation (see `DeepLinkOrigin`). [params] is carried verbatim so a confirmed
 * action runs exactly what was asked, while [paramKeys] is what the prompt shows.
 */
internal class PendingPluginAction(
    val handlerId: String,
    val action: String,
    val params: Map<String, String>,
) {
    /** Key names only: a parameter value is attacker-chosen text, never prompt copy. */
    val paramKeys: List<String> get() = params.keys.sorted()
}

/** Window-owned FIFO, accessed only on the UI thread. Closing the window drops its requests. */
internal class PluginActionApprovalQueue {
    private val requests = mutableStateListOf<PendingPluginAction>()

    val current: PendingPluginAction?
        get() = requests.firstOrNull()

    val size: Int
        get() = requests.size

    /**
     * Whether this window may take another request off the bus: only while nothing is on screen.
     *
     * One at a time, as the dependency bus does - AGENTS.md: "a window waits for its current
     * dialog to close before handling another prompt." A claimed request lives in this queue and
     * dies with the window, so claiming every eligible request at once let one window drain the
     * whole retained registry into itself, and closing it then abandoned all of them. Claiming
     * only while this is true leaves every request but the one actually shown on the bus, where
     * the next window - or this one, once its dialog closes - can still take it. The bus re-offers
     * each rescan, so waiting costs nothing. Checked before claiming, never after.
     */
    val canClaim: Boolean
        get() = current == null

    fun enqueue(request: PendingPluginAction): Boolean {
        if (requests.size >= MAX_PENDING) return false
        requests.add(request)
        return true
    }

    /** Only the request actually shown can be consumed, once, even for identical actions. */
    fun consume(request: PendingPluginAction): Boolean {
        if (current !== request) return false
        requests.removeAt(0)
        return true
    }

    companion object {
        const val MAX_PENDING = 16
    }
}
