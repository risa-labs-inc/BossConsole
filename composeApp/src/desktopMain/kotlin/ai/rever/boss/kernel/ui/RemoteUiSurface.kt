package ai.rever.boss.kernel.ui

import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.WidgetUpdate
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.ui.sdk.WidgetDiffEngine
import ai.rever.boss.ui.sdk.WidgetProtoConverter.decodeOperations
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toKotlin
import ai.rever.boss.ui.sdk.WidgetTree
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import ai.rever.boss.ipc.proto.WidgetDiff as ProtoWidgetDiff

/**
 * One remote UI surface, from the host's side of the IPC boundary.
 *
 * A surface is the meeting point of two independent lifetimes: an out-of-process plugin that streams
 * widget trees in, and a host component ([ai.rever.boss.components.plugin.remote.RemotePanelComponent]
 * or `RemoteTabComponent`) that renders them and hands back user events. Neither one holds the other
 * — both address the surface by `surfaceId` through [RemoteUiSurfaceRegistry], so they can appear in
 * either order and either can go away without stranding the other.
 *
 * This object owns the two pieces of per-surface transport state:
 * - the last widget tree the plugin published (retained, so a component attaching later renders
 *   immediately instead of waiting for the next update), and
 * - the queue of user events waiting to go out to the plugin.
 *
 * Instances come from [RemoteUiSurfaceRegistry.register]; the constructor is internal because a
 * surface that is not in the registry is unreachable by definition.
 */
class RemoteUiSurface internal constructor(
    val surfaceId: String,
    val processId: String,
    /**
     * What the plugin declared about this surface at registration.
     *
     * Placement uses the type, slot, name and icon. The renderer observes wantsKeys through
     * capability callbacks, and emit enforces it against this immutable receiving registration.
     */
    val descriptor: RemoteUiSurfaceDescriptor = RemoteUiSurfaceDescriptor(),
    /**
     * Deliver to whichever host component currently owns this surface's id.
     *
     * Both take the surface as their first argument so the registry can drop a publication from a surface
     * that has already been replaced — a reclaimed predecessor's `close()` would otherwise report
     * "disconnected" over its successor's live stream.
     */
    private val publishTree: (RemoteUiSurface, WidgetTree) -> Unit,
    private val publishConnected: (RemoteUiSurface, Boolean) -> Unit,
) {
    /**
     * Serializes publication against a host component attaching.
     *
     * Without it, [RemoteUiSurfaceRegistry.attach] could install a host, have a gRPC thread deliver tree
     * *N+1* through it, and then replay the *N* it had already read — leaving the component rendering an
     * older tree than the surface holds, with nothing but the plugin's next update to correct it. Taking
     * this lock while reading the state to replay makes the sequence a host sees monotonic.
     *
     * Held across a callback into the host component, which is safe because those callbacks only write
     * Compose snapshot state and never re-enter the surface.
     */
    private val publishLock = Any()

    /**
     * User events queued for the plugin process.
     *
     * **Ordered, and bounded.** Ordering is the load-bearing property: `TextChange` carries the whole
     * field value with last-write-wins semantics, so two keystrokes that arrive out of order silently
     * revert a character. A single channel written straight from the Compose callback makes emission
     * order equal interaction order, and one collector drains it into one gRPC stream, which preserves
     * that order on the wire.
     *
     * The bound is the part that differs from a purely in-process queue. Across a process boundary the
     * reader can stop reading — a wedged or paused plugin leaves gRPC flow control blocking our
     * collector, and an unlimited queue would then grow without limit inside the *host* for a fault
     * that is entirely the plugin's. [OUTGOING_BUFFER] events is over ten seconds of continuous human
     * interaction; a surface that falls that far behind is not "busy", it is gone.
     *
     * Overflow drops the **oldest** event, not the newest. Both lose information, but dropping the
     * newest inverts last-write-wins: the plugin would end up holding a stale text value with no later
     * event to correct it, and host and plugin would disagree about the field forever. Dropping from
     * the head keeps the queue converging on the current truth, and preserves the order of what
     * survives. Sheds are approximated in [shedEventCount] rather than being silent.
     */
    private val outgoing = Channel<UIEvent>(OUTGOING_BUFFER, BufferOverflow.DROP_OLDEST)

    /**
     * Whether this surface has told its plugin it is rendered, and therefore owes it a `destroyed`.
     *
     * Lives here rather than in [RemoteUiLifecycle] because it is per-surface state with exactly this
     * object's lifetime — a map keyed by surface id there would need its own eviction, and would get it
     * wrong across a respawn, where a *new* surface under the same id must start un-announced. Owned by
     * [RemoteUiLifecycle]; nothing else may touch it.
     */
    internal val createdAnnounced = AtomicBoolean(false)

    private val streamClaimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val eventsTaken = AtomicBoolean(false)
    private val diverged = AtomicBoolean(false)
    private val malformed = AtomicLong(0)
    private val buffered = AtomicInteger(0)
    private val shed = AtomicLong(0)

    /** The last tree the plugin published, or `null` before its first update. */
    @Volatile
    var tree: WidgetTree? = null
        private set

    /** Whether a plugin process currently holds this surface's `StreamUI` call. */
    val streaming: Boolean get() = streamClaimed.get()

    /**
     * Roughly how many outgoing events have been shed because the plugin stopped reading.
     *
     * **Approximate by construction.** `DROP_OLDEST` evicts inside the channel, so occupancy has to be
     * inferred from outside it, and the collector's decrement lands just after its receive — so a
     * concurrent drain can make an ordinary send look like an eviction (or hide a real one). Exact while
     * nothing is collecting, which is the case that matters: a plugin that has stopped reading. Treat it
     * as a health signal, not a count.
     */
    val shedEventCount: Long get() = shed.get()

    /**
     * Take ownership of this surface's outgoing stream, or fail if someone already has it.
     *
     * One stream per surface is not a detail: two concurrent `StreamUI` calls would each drain part of
     * [outgoing], splitting one ordered event sequence across two readers and losing the ordering
     * guarantee the queue exists to provide.
     */
    internal fun claimStream(): Boolean {
        // Order matters, and checking `closed` first is not enough. `close()` *resets* streamClaimed, so a
        // check-then-CAS still admits: read closed (false) → close() runs fully, resetting the flag → CAS
        // succeeds → publish connected = true on a dead surface. Nothing takes that back, because
        // stillOwnedBy passes for a removed id, events() completes at once over the closed channel, and the
        // compensating close() returns early. The component would sit reading *connected* for a surface
        // that no longer exists — the frozen state this transport exists to avoid.
        //
        // Claiming first and re-checking closes it: either close()'s write is visible to the re-check, or
        // the CAS landed before close() reset the flag and the re-check sees closed anyway. Not reachable
        // from a test, which is why the ordering carries the weight here.
        if (!streamClaimed.compareAndSet(false, true)) return false
        val alive = !closed.get()
        if (alive) {
            synchronized(publishLock) { publishConnected(this, true) }
        } else {
            streamClaimed.set(false)
        }
        return alive
    }

    /**
     * Hand a freshly attached host component the state this surface already holds.
     *
     * Under [publishLock], so the state read here is the state as of the last delivery — a live update
     * cannot slip in between the read and the callback and be overwritten by an older tree.
     */
    internal fun replayTo(host: RemoteUiSurfaceHost) {
        synchronized(publishLock) {
            host.onKeyCapabilityChanged(descriptor.wantsKeys)
            host.onConnectionChanged(streaming)
            tree?.let(host::onTreeUpdated)
        }
    }

    /**
     * The outgoing event stream, consumable exactly once.
     *
     * Enforced here rather than left to [claimStream]'s convention: a second consumer would drain part of
     * [outgoing] and split one ordered event sequence across two readers, which is the failure the queue
     * exists to prevent, and the underlying channel's own error for that says nothing about surfaces.
     */
    internal fun events(): Flow<UIEvent> {
        check(eventsTaken.compareAndSet(false, true)) {
            "surface '$surfaceId' already has an event consumer - one StreamUI call per surface"
        }
        return outgoing.consumeAsFlow().onEach { buffered.decrementAndGet() }
    }

    /**
     * Queue one user event for the plugin.
     *
     * Non-suspending on purpose: it is called straight from a Compose callback, where suspending would
     * mean either blocking the frame or handing the event to a coroutine that can be reordered against
     * its neighbours.
     *
     * @return `false` when the surface is closed or a key was not requested.
     */
    internal fun emit(event: UIEvent): Boolean {
        // A composed renderer can still hold the previous registration's capability until its next
        // callback/recomposition. Enforce the receiving registration's immutable policy at enqueue.
        if ((event.hasKey() && !descriptor.wantsKeys) || outgoing.trySend(event).isFailure) return false
        // DROP_OLDEST evicts inside the channel, so overflow has to be inferred from outside it: the
        // buffer cannot hold more than its capacity, so a count above it means this send probably
        // evicted. "Probably" because the collector decrements just after its receive — see
        // shedEventCount for why that is acceptable for a health signal.
        if (buffered.incrementAndGet() > OUTGOING_BUFFER) {
            buffered.decrementAndGet()
            val total = shed.incrementAndGet()
            if (total == 1L || total % SHED_LOG_INTERVAL == 0L) {
                logger.warn(
                    LogCategory.UI,
                    "Plugin appears not to be reading its UI events - shedding the oldest",
                    mapOf("surfaceId" to surfaceId, "processId" to processId, "shedApprox" to total),
                )
            }
        }
        return true
    }

    /** Publish a tree the host already holds in SDK form (a registration's `initial_tree`, or a test). */
    internal fun pushTree(next: WidgetTree) {
        synchronized(publishLock) {
            tree = next
            publishTree(this, next)
        }
    }

    /** Route one inbound `WidgetUpdate` into this surface's tree. */
    internal fun applyUpdate(update: WidgetUpdate) {
        when (update.updateCase) {
            WidgetUpdate.UpdateCase.FULL_TREE -> {
                // A full tree is the only thing that repairs a diverged surface, so it also clears the flag
                // that suppresses repeat divergence warnings.
                diverged.set(false)
                pushTree(update.fullTree.toKotlin())
            }

            WidgetUpdate.UpdateCase.DIFF -> {
                applyDiff(update.diff)
            }

            WidgetUpdate.UpdateCase.UPDATE_NOT_SET, null -> {
                // Neither oneof arm set: a sender bug, or a proto case newer than this build. Either
                // way there is nothing to render, and guessing would corrupt the tree.
                noteMalformed("Ignoring a WidgetUpdate that carries neither a full tree nor a diff")
            }
        }
    }

    /**
     * Close the surface: complete the outgoing stream and report the surface disconnected.
     *
     * Completing the channel is what unblocks the `StreamUI` collector and lets gRPC finish the call
     * cleanly, so this is also the anti-leak path. The last tree is deliberately **kept**: a surface
     * whose plugin died shows its final state with `connected == false` rather than going blank, which
     * is the difference between "disconnected" and "frozen" from the user's side.
     *
     * That applies to a component that was *already* attached — the tree it last rendered stays in its own
     * state. A component that attaches *after* the plugin is gone gets nothing, because the surface is out
     * of the registry by then and there is nothing to replay. Deliberate: a tree from a process that died
     * some time ago is stale, and showing it as if it were live would be the more misleading of the two.
     *
     * Idempotent, because it is legitimately reached twice on a graceful shutdown: `UnregisterUI` closes
     * the surface, which ends the event queue, which ends the `StreamUI` call, whose teardown closes the
     * surface again. Without the guard the host component would be told it disconnected twice.
     */
    internal fun close() {
        if (!closed.compareAndSet(false, true)) return
        outgoing.close()
        streamClaimed.set(false)
        synchronized(publishLock) { publishConnected(this, false) }
    }

    /**
     * Report a malformed update, first and then every [MALFORMED_LOG_INTERVAL]th.
     *
     * These paths are all reachable by a misbehaving plugin on the once-per-update path, so an unthrottled
     * warning is a log-flooding primitive handed to the other side of the boundary.
     */
    private fun noteMalformed(message: String) {
        val total = malformed.incrementAndGet()
        if (total == 1L || total % MALFORMED_LOG_INTERVAL == 0L) {
            logger.warn(
                LogCategory.UI,
                message,
                mapOf("surfaceId" to surfaceId, "processId" to processId, "occurrences" to total),
            )
        }
    }

    private fun applyDiff(diff: ProtoWidgetDiff) =
        // The whole read-modify-write, not just the write. One stream per surface rules out concurrent
        // diffs, but a registration's `initial_tree` is pushed from a different coroutine and nothing makes
        // a plugin await the RegisterUI response before opening StreamUI — so an initial tree and a first
        // diff can interleave and lose one. The monitor is reentrant, so pushTree taking it again is free.
        synchronized(publishLock) { applyDiffLocked(diff) }

    private fun applyDiffLocked(diff: ProtoWidgetDiff) {
        val base = tree
        if (base == null) {
            // A diff is meaningless without the tree it was computed against, and the protocol has no way to
            // ask for a resync — so say so instead of inventing an empty base. Throttled, because a plugin
            // that only ever sends diffs would otherwise log one per update forever.
            noteMalformed("Dropping a widget diff for a surface with no tree - the plugin must send one first")
            return
        }
        val stale = diff.baseVersion != 0L && diff.baseVersion != base.version
        val decoded = diff.decodeOperations()
        // Reported on the TRANSITION into divergence, not per update. Keeping the local version means every
        // subsequent diff mismatches by design — that is what makes the next one trip — so warning each
        // time would turn one bad diff into a warning per frame for the life of the surface. Cleared when a
        // full tree arrives, because that is the thing that actually repairs it.
        if ((stale || decoded.skipped > 0) && diverged.compareAndSet(false, true)) {
            logger.warn(
                LogCategory.UI,
                "Widget diff cannot be applied faithfully - this surface's tree may now diverge from the " +
                    "plugin's until it sends a full tree",
                mapOf(
                    "surfaceId" to surfaceId,
                    "expectedBaseVersion" to base.version,
                    "actualBaseVersion" to diff.baseVersion,
                    "undecodableOperations" to decoded.skipped,
                ),
            )
        }
        val applied = WidgetDiffEngine.apply(base, decoded.operations)
        // WidgetDiffEngine bumps the version by one; adopt the sender's numbering only when we believe we
        // applied the diff faithfully. Adopting it after a divergence would make every SUBSEQUENT
        // base_version check pass and the surface permanently, invisibly wrong — and "the next full tree
        // repairs it" is no comfort to a plugin that sends one full tree and then only diffs, which is
        // the intended steady state. Keeping the local version instead makes the next diff trip too.
        val faithful = !stale && decoded.skipped == 0
        pushTree(if (faithful && diff.newVersion != 0L) applied.copy(version = diff.newVersion) else applied)
    }

    companion object {
        /**
         * How many outgoing events a surface holds for a plugin that has stopped reading.
         *
         * Public because it is a documented property of the transport, not a tuning detail: it is the
         * point past which the host starts shedding a plugin's events. See [outgoing].
         */
        const val OUTGOING_BUFFER = 256

        /** Log the first shed event, then every Nth, so a wedged plugin cannot flood the log. */
        private const val SHED_LOG_INTERVAL = 256L

        /** Same restraint for malformed updates, which are equally plugin-controlled. */
        private const val MALFORMED_LOG_INTERVAL = 256L

        private val logger = BossLogger.forComponent("RemoteUiSurface")
    }
}
