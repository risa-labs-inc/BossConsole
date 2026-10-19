package ai.rever.boss.components.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform

/**
 * A `boss://plugin?id=…&action=…` request that arrived from outside the
 * operator's own `boss` invocation and is waiting to be shown to them.
 *
 * @property handlerId the plugin deep-link handler the action is addressed to
 * @property action the action name the handler would be asked to run
 * @property params the parameters the handler would receive. Carried verbatim so
 *   a confirmed action runs exactly what was asked; only the KEYS are ever
 *   displayed, because a value is attacker-chosen text and a prompt is not a
 *   place to render it.
 * @property sourceWindowId the window the request resolved to, and therefore the
 *   one preferred to show the prompt. Null when no window was registered yet -
 *   the cold-start case, where the link arrived in `argv` before `application {}`
 *   built anything - and then whichever window opens first asks about it.
 */
data class PluginActionConfirmEvent(
    val handlerId: String,
    val action: String,
    val params: Map<String, String>,
    val sourceWindowId: String?,
)

/**
 * Registry of plugin action links that need the operator's say-so.
 *
 * `boss://` is registered with the OS, so an action link is not evidence the
 * operator asked for anything. [ai.rever.boss.utils.DeepLinkHandler] decides which
 * links need confirming and retains them here; a window's BossApp claims one,
 * queues it and dispatches it only if the operator agrees.
 *
 * **Retained until claimed, not broadcast once.** The obvious shape - a
 * `replay = 0` `MutableSharedFlow` emitted into and forgotten - loses a request
 * in the two cases that matter most for this scheme:
 *
 * - **Cold start.** `CliBootstrap.dispatchPostLock` runs a `boss://` argv link
 *   before `application {}` creates the first window, so at the moment the link is
 *   processed there is no collector and no window id at all. That is the ordinary
 *   path for this scheme (a link clicked while BOSS is not running), not an edge
 *   case, and a fire-and-forget emission there reaches nobody: the operator is
 *   never asked about the very request the gate exists to ask about.
 * - **A warm window that has not subscribed yet.** A `MutableSharedFlow` with no
 *   subscriber *drops*; `extraBufferCapacity` only buffers for a collector that
 *   already exists. The hold path answers the single-instance caller "queued"
 *   (null) as soon as it hands the request over, so a drop there is an
 *   acknowledgement of something that then silently never happens.
 *
 * So a request is held in [pending] until some window atomically [claim]s it, and
 * [changed] is only a "go look" signal - nothing is lost if it is missed, because
 * the re-scan ticker fires and the entry is still there. This is the same pattern,
 * for the same reason, as `PluginDependencyBus`.
 *
 * Retaining rather than dropping widens nothing: a held request has not run, and
 * still cannot run until the operator confirms it in a window.
 */
object PluginActionEventBus {
    /**
     * Keyed by an ever-increasing [nextKey], deliberately NOT by content: two
     * identical action links are two separate requests and the operator must be
     * asked about each, unlike a duplicate missing-dependency prompt, which asks an
     * identical question and is therefore redundant. `PluginActionApprovalQueue`
     * draws the same distinction with its identity-keyed `consume`.
     */
    private val pending = LinkedHashMap<Long, PluginActionConfirmEvent>()
    private val lock = Any()
    private var nextKey = 0L

    /**
     * Wakes every collector to re-scan [pending]. `replay = 1` so a window that starts
     * collecting after a request was retained - the cold-start ordering, every time - still
     * gets a signal to run its first scan instead of waiting for a change that already
     * happened. `DROP_OLDEST` with a spare slot because repeated "go look" signals conflate:
     * [MutableSharedFlow.tryEmit] must never fail or block, since [requestConfirmation] is
     * called from a deep-link path with no UI to wait on.
     */
    private val changed =
        MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Every open window collects this and decides, via [shouldClaimPluginAction], whether a
     * given request is its to show - then calls [claim] before acting on it. Re-scanning on a
     * bounded interval as well as on every [changed] signal covers the case no signal fires
     * for: the preferred window closing without answering, leaving a request that another
     * window should now pick up.
     */
    val confirmEvents: Flow<PluginActionConfirmEvent> =
        merge(changed, ticker()).transform { snapshot().forEach { event -> emit(event) } }

    private fun ticker() =
        flow {
            while (true) {
                delay(PLUGIN_ACTION_RESCAN_INTERVAL_MS)
                emit(Unit)
            }
        }

    /** A snapshot of [pending] at this instant, oldest first; never the live map. */
    private fun snapshot(): List<PluginActionConfirmEvent> = synchronized(lock) { pending.values.toList() }

    private val retained = MutableStateFlow(0)

    /**
     * How many requests are held but not yet claimed. Read from [retained] rather than from
     * [pending] directly, so this and [pendingCountFlow] are one answer and cannot drift apart;
     * [retained] is written under [lock] at every change to [pending], so it is already published.
     */
    val pendingCount: Int get() = retained.value

    /**
     * [pendingCount] as state a prompt can recompose on. A window holds only the request it is
     * showing (`PluginActionApprovalQueue.canClaim`), so its own queue cannot say how many more are
     * waiting - under a flood of links that number lives here. Written under [lock] alongside
     * [pending], so it never reports a size the map did not have.
     */
    val pendingCountFlow: StateFlow<Int> = retained.asStateFlow()

    /**
     * Retain an action link for the operator of [sourceWindowId] - or, when that is null, for
     * whichever window opens first - to confirm or dismiss.
     *
     * Non-suspending on purpose: the deep-link path must retain the request *before* it tells
     * its caller the request is queued, so there is no window in which an acknowledged action
     * has not actually been recorded anywhere.
     *
     * @return false when [MAX_PENDING] requests are already waiting, so the caller can refuse
     *   rather than report a queued request that was in fact discarded. Bounded because
     *   anything that can open a `boss://` URL can produce these, and a machine that never
     *   opens a window would otherwise accumulate them without limit.
     */
    fun requestConfirmation(
        handlerId: String,
        action: String,
        params: Map<String, String>,
        sourceWindowId: String?,
    ): Boolean {
        val event = PluginActionConfirmEvent(handlerId, action, params, sourceWindowId)
        val admitted =
            synchronized(lock) {
                if (pending.size >= MAX_PENDING) {
                    false
                } else {
                    pending[nextKey++] = event
                    retained.value = pending.size
                    true
                }
            }
        if (admitted) changed.tryEmit(Unit)
        return admitted
    }

    /**
     * Atomically takes [event] out of [pending] for the window that wins the race. A losing
     * racer - another window's collector woken by the same signal - gets false and does
     * nothing further, which is what keeps one request from being shown twice.
     *
     * Identity, not equality: two identical requests are distinct entries and claiming one
     * must not remove the other.
     */
    internal fun claim(event: PluginActionConfirmEvent): Boolean =
        synchronized(lock) {
            val key = pending.entries.firstOrNull { it.value === event }?.key
            if (key == null) {
                false
            } else {
                pending.remove(key)
                retained.value = pending.size
                true
            }
        }

    /** The retained requests, oldest first. Tests only. */
    internal fun confirmEventsSnapshotForTest(): List<PluginActionConfirmEvent> = snapshot()

    /** Drops every retained request. Tests only; there is no product reason to forget one. */
    internal fun clearForTest() {
        synchronized(lock) {
            pending.clear()
            retained.value = 0
        }
    }

    const val MAX_PENDING = 16
}

/**
 * How often [PluginActionEventBus.confirmEvents] re-broadcasts with no new request.
 *
 * `internal` rather than private because this repo pins its timing constants against a test.
 */
internal const val PLUGIN_ACTION_RESCAN_INTERVAL_MS = 1000L

/**
 * Whether the window at [collectorWindowId] should claim [event], rather than leave it for
 * [PluginActionConfirmEvent.sourceWindowId]'s own window.
 *
 * [PluginActionEventBus.confirmEvents] offers every retained request to every open window, so
 * this is the other half of routing correctly: a window that is not the preferred one simply
 * does not claim, leaving the request in place. That "leave it alone" behaviour lives at the
 * collector; this is only the yes/no decision, kept pure so it is testable without a window
 * registry - [targetWindowOpen] is passed in for the same reason, since whether a window id is
 * still live is a `WindowFocusManager` question.
 *
 * True when there is no window to prefer ([PluginActionConfirmEvent.sourceWindowId] is null,
 * i.e. the request arrived before any window existed), when [collectorWindowId] IS the
 * preferred window, or when the preferred window has since closed - a request must never wait
 * forever for a window that will never answer it.
 */
fun shouldClaimPluginAction(
    event: PluginActionConfirmEvent,
    collectorWindowId: String,
    targetWindowOpen: Boolean,
): Boolean = event.sourceWindowId == null || event.sourceWindowId == collectorWindowId || !targetWindowOpen
