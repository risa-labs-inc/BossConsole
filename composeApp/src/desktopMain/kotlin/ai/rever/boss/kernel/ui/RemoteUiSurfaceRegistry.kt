package ai.rever.boss.kernel.ui

import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.ui.sdk.WidgetTree
import java.util.concurrent.ConcurrentHashMap

/**
 * The host-side renderer of one remote surface, as the transport sees it.
 *
 * Implemented by `RemotePanelComponent` / `RemoteTabComponent`. Callbacks arrive on whichever thread
 * gRPC delivered the message on, never the UI thread, and are invoked **while the surface's publish
 * lock is held** — which is what makes the sequence a host observes monotonic. So an implementation must:
 *
 * - touch only thread-safe state (Compose snapshot state is — writing it from any thread is fine);
 * - not block, and not dispatch and wait; and
 * - never call back into [RemoteUiSurfaceRegistry] or its surface from inside the callback.
 *
 * Anything heavier belongs on the far side of a state write the UI observes.
 */
interface RemoteUiSurfaceHost {
    /** The publishing surface's key declaration, delivered without a registry lookup by the host. */
    fun onKeyCapabilityChanged(wantsKeys: Boolean) {}

    /** A new widget tree to render. */
    fun onTreeUpdated(tree: WidgetTree)

    /** Whether a plugin process is currently streaming this surface. */
    fun onConnectionChanged(connected: Boolean)
}

/**
 * What a plugin declared about a surface when it registered it.
 *
 * Mirrors `UIRegistration`: placement consumes the type/name/icon/slot, and the renderer and
 * receiving event queue enforce the key declaration.
 */
data class RemoteUiSurfaceDescriptor(
    val surfaceType: String = "",
    val displayName: String = "",
    val iconName: String = "",
    val defaultSlot: String = "",
    /**
     * Whether the plugin declared it wants unclaimed [ai.rever.boss.ipc.proto.KeyEvent]s
     * (the security note in docs/KEYBOARD_SHORTCUTS.md). False by default and for
     * every plugin built against a proto before this field existed - the renderer that reads it
     * must fail closed on a plugin that never sends it.
     */
    val wantsKeys: Boolean = false,
)

/** Outcome of a plugin's `RegisterUI`. */
sealed interface SurfaceRegistration {
    data class Accepted(
        val surface: RemoteUiSurface,
    ) : SurfaceRegistration

    /** [reason] is written to be readable by a plugin author — it goes out as `error_message`. */
    data class Rejected(
        val reason: String,
    ) : SurfaceRegistration
}

/** Outcome of a plugin's `StreamUI` binding to a surface. */
sealed interface SurfaceStream {
    data class Bound(
        val surface: RemoteUiSurface,
    ) : SurfaceStream

    /**
     * Split by recovery, not just by message: an unregistered surface is fixable by calling `RegisterUI`,
     * an already-streaming one never is. Collapsing them left a plugin string-matching a description to
     * tell "retry after registering" from "give up", which the proto now documents as contract.
     */
    sealed interface Refused : SurfaceStream {
        val reason: String
    }

    data class Unregistered(
        override val reason: String,
    ) : Refused

    data class NotOwner(
        override val reason: String,
    ) : Refused

    data class AlreadyStreaming(
        override val reason: String,
    ) : Refused
}

/**
 * Whether [surface] may still publish under its id, given the currently registered surfaces.
 *
 * `claim()` installs a replacement *before* closing the surface it reclaimed, and the publish lock is
 * per-instance, so those two do not serialize against each other: without this check a predecessor's
 * `close()` could announce "disconnected" *after* its successor had already announced a live stream,
 * leaving the component reading disconnected while trees kept arriving.
 *
 * A surface whose id is now **free** still publishes — that is `closeStream`'s remove-then-close order
 * delivering the legitimate "your plugin died" notice, and suppressing it would recreate the frozen
 * surface this transport exists to avoid.
 *
 * A file-level predicate rather than a member: it needs nothing but the map, and reads as a question
 * about the map.
 */
private fun Map<String, RemoteUiSurface>.stillOwnedBy(surface: RemoteUiSurface): Boolean {
    val current = this[surface.surfaceId]
    return current == null || current === surface
}

/**
 * Directory of live remote UI surfaces, and the seam the two sides of a surface meet at.
 *
 * The problem this solves is that a surface's two halves start independently: the plugin process
 * registers and streams whenever it happens to come up, while the host component is constructed when
 * the user opens the panel or tab. Either order is normal, and a plugin can crash and respawn under a
 * component that never went away.
 *
 * So neither half holds a reference to the other. Both are indexed by `surfaceId` in separate maps —
 * plugin-side surfaces here, host-side renderers in [hosts] — and every delivery is a lookup at the
 * moment it happens. A tree that arrives with nobody attached is retained on the surface for whoever
 * attaches next; an event emitted with no plugin registered is reported undeliverable to its caller
 * rather than queued into a void or thrown; and a component that attaches to an already-streaming
 * surface is immediately given the current tree and connection state, so it does not render blank
 * until the plugin's next update.
 */
class RemoteUiSurfaceRegistry {
    private val surfaces = ConcurrentHashMap<String, RemoteUiSurface>()

    // Reserve surface names for a stable process identity for this host registry lifetime.
    // Unregister and kernel restart can race with a still-composed host, so neither releases it.
    private val owners = mutableMapOf<String, String>()
    private val hosts = ConcurrentHashMap<String, RemoteUiSurfaceHost>()

    /**
     * Claim [surfaceId] for a plugin process.
     *
     * A claim held by a *different* process is refused rather than taken over: two plugins rendering into
     * one surface would interleave trees, and the second one's events would be delivered to the first's
     * stream.
     *
     * A claim held by the **same** process with no stream open is taken over, because that is what a
     * respawn looks like. [closeStream] releases the id when a stream dies, but a plugin can also die in
     * the window between `RegisterUI` returning and `StreamUI` binding, or hold claims on more surfaces
     * than it streams — and there is no notification for either. Refusing those would lock a plugin out of
     * its own `surface_id` forever, leaving the attached component permanently disconnected: exactly the
     * lockout `closeStream` exists to prevent, reached by a path it cannot see.
     */
    @Synchronized
    fun register(
        surfaceId: String,
        processId: String,
        descriptor: RemoteUiSurfaceDescriptor = RemoteUiSurfaceDescriptor(),
    ): SurfaceRegistration {
        // process_id is not cosmetic: `claim()` compares it to decide whether a claim may be taken over, so
        // a blank one is an authorization key every plugin shares. proto3 makes the empty string the
        // default, so a runtime that simply forgets the field would let any plugin reclaim any other
        // plugin's registered-but-not-yet-streaming surface — and then receive its TextChangeEvents.
        val owner = owners[surfaceId]
        val refusal =
            when {
                surfaceId.isBlank() -> {
                    "surface_id is required"
                }

                processId.isBlank() -> {
                    "process_id is required"
                }

                owner != null && owner != processId -> {
                    "surface_id '$surfaceId' is reserved for process '$owner'"
                }

                else -> {
                    null
                }
            }
        if (refusal != null) return SurfaceRegistration.Rejected(refusal)
        owners[surfaceId] = processId
        val created =
            RemoteUiSurface(
                surfaceId = surfaceId,
                processId = processId,
                descriptor = descriptor,
                publishTree = { from, tree ->
                    if (surfaces.stillOwnedBy(from)) {
                        hosts[surfaceId]?.apply {
                            onKeyCapabilityChanged(from.descriptor.wantsKeys)
                            onTreeUpdated(tree)
                        }
                    }
                },
                publishConnected = { from, connected ->
                    if (surfaces.stillOwnedBy(from)) {
                        hosts[surfaceId]?.apply {
                            // A false publication comes from close(); reset the tap on teardown.
                            onKeyCapabilityChanged(connected && from.descriptor.wantsKeys)
                            onConnectionChanged(connected)
                        }
                    }
                },
            )
        val stale = claim(surfaceId, created)
        return if (stale != null) {
            SurfaceRegistration.Rejected(
                "surface_id '$surfaceId' is already registered by process '${stale.processId}'",
            )
        } else {
            logger.info(
                LogCategory.UI,
                "Remote UI surface registered",
                mapOf("surfaceId" to surfaceId, "processId" to processId, "attached" to hosts.containsKey(surfaceId)),
            )
            SurfaceRegistration.Accepted(created)
        }
    }

    /**
     * Install [created], returning the surface that blocked it, or `null` on success.
     *
     * Loops because the abandoned-claim replacement is a compare-and-set: another `RegisterUI` for the
     * same id can win the race, and the loser has to re-read rather than assume its own view.
     */
    private fun claim(
        surfaceId: String,
        created: RemoteUiSurface,
    ): RemoteUiSurface? {
        var blocker = surfaces.putIfAbsent(surfaceId, created)
        while (blocker != null) {
            val abandoned = blocker.processId == created.processId && !blocker.streaming
            if (!abandoned) break
            if (surfaces.replace(surfaceId, blocker, created)) {
                logger.info(
                    LogCategory.UI,
                    "Reclaimed an abandoned UI surface for its own process - treating it as a respawn",
                    mapOf("surfaceId" to surfaceId, "processId" to created.processId),
                )
                // No `announceDestroyed`: `abandoned` requires `!blocker.streaming`, and a surface is
                // only ever announced *created* while streaming, so the latch cannot be set here. The
                // one teardown path that opts out on purpose rather than because it cannot deliver.
                blocker.close()
                blocker = null
            } else {
                // Another RegisterUI for this id won the swap; re-read rather than trust our own view.
                blocker = surfaces.putIfAbsent(surfaceId, created)
            }
        }
        return blocker
    }

    /** Remove only the registration the caller authorized, never a concurrent replacement. */
    @Synchronized
    fun unregister(
        surfaceId: String,
        expected: RemoteUiSurface? = surfaces[surfaceId],
    ): Boolean {
        if (expected == null || !surfaces.remove(surfaceId, expected)) return false
        val surface = expected
        // Before close(), and that order is the whole reason `destroyed` is deliverable: Channel.close()
        // is graceful, so an event queued first is handed to the still-collecting StreamUI call and only
        // then does the flow complete. See RemoteUiLifecycle.
        RemoteUiLifecycle.announceDestroyed(surface)
        surface.close()
        // debug, not info: register + unregister + closeStream at info is three lines per restart of a
        // crash-looping plugin. The registration itself is the event worth seeing at info.
        logger.debug(LogCategory.UI, "Remote UI surface unregistered", mapOf("surfaceId" to surfaceId))
        return true
    }

    /**
     * Bind a plugin's `StreamUI` call to its surface.
     *
     * Registration first, deliberately: a stream for an unknown id is a protocol error worth reporting,
     * and accepting it would mean inventing a surface with no `surface_type`, `display_name` or slot —
     * i.e. one the host could never place.
     */
    @Synchronized
    fun openStream(
        surfaceId: String,
        processId: String? = surfaces[surfaceId]?.processId,
    ): SurfaceStream {
        val surface = surfaces[surfaceId]
        return when {
            surface == null -> {
                SurfaceStream.Unregistered("surface_id '$surfaceId' is not registered - call RegisterUI first")
            }

            processId == null || surface.processId != processId -> {
                SurfaceStream.NotOwner("Only the registered process may stream this surface")
            }

            !surface.claimStream() -> {
                SurfaceStream.AlreadyStreaming("surface_id '$surfaceId' already has an open StreamUI call")
            }

            else -> {
                // Half two of the rendezvous. `claimStream()` above has already set `streaming`, and
                // `attach` publishes its host into `hosts` before reading `streaming` — so whichever of
                // the two runs second sees the other's write and announces. Neither ordering can miss it,
                // and the latch in RemoteUiLifecycle means both racing cannot double-announce.
                if (hosts.containsKey(surfaceId)) RemoteUiLifecycle.announceCreated(surface)
                SurfaceStream.Bound(surface)
            }
        }
    }

    /**
     * Release a stream that has ended, for any reason.
     *
     * This also drops the registration. A dying stream is the *only* signal the host gets that a plugin
     * process is gone — there is no `UnregisterUI` from a process that crashed — so holding the claim
     * would lock the id out and a respawned plugin could never re-register it. Any attached component
     * stays attached and simply sees `connected == false`, ready for the replacement process.
     *
     * Pointedly **no** `destroyed` announcement here, unlike every other teardown path: this one is
     * reached because the transport is gone, so there is nothing to send over and nobody to receive it.
     * Stream completion is the plugin's notice, which is what #34 meant by inferring destruction from
     * the stream. A graceful `UnregisterUI` still gets the event — it announces before closing, and
     * arrives here afterwards with the latch already spent. See [RemoteUiLifecycle].
     */
    @Synchronized
    fun closeStream(surface: RemoteUiSurface) {
        surfaces.remove(surface.surfaceId, surface)
        surface.close()
        logger.debug(
            LogCategory.UI,
            "Remote UI stream closed",
            mapOf("surfaceId" to surface.surfaceId, "processId" to surface.processId, "shed" to surface.shedEventCount),
        )
    }

    /**
     * Bind a host component to [surfaceId], replaying whatever the surface already holds.
     *
     * Ordered so no update can fall between the two steps: the host goes into [hosts] *first*, so a
     * surface that registers a microsecond later delivers straight to it, and the replay then happens
     * under that surface's publish lock, so it cannot hand back a tree older than one already delivered.
     */
    fun attach(
        surfaceId: String,
        host: RemoteUiSurfaceHost,
    ) {
        val displaced = hosts.put(surfaceId, host)
        if (displaced != null && displaced !== host) {
            // The registry routes to one host per id, and it is process-wide while the app is
            // multi-window — so this is reachable, and silence would leave the displaced component
            // rendering its last tree and still reporting `connected`, frozen from the host side rather
            // than the plugin side. One surface renders in one place; saying so is better than the
            // follow-up discovering it.
            logger.warn(
                LogCategory.UI,
                "A second component attached to a surface already being rendered - the first is detached",
                mapOf("surfaceId" to surfaceId),
            )
            displaced.onKeyCapabilityChanged(false)
            displaced.onConnectionChanged(false)
        }
        val surface = surfaces[surfaceId]
        if (surface == null) {
            host.onKeyCapabilityChanged(false)
            host.onConnectionChanged(false)
        } else {
            surface.replayTo(host)
            // Half one of the rendezvous — see openStream for why reading `streaming` *after* publishing
            // into `hosts` is what makes the handshake gapless. Announced after the replay so the plugin
            // never hears "you are rendered" before the component holds the tree it is rendering.
            if (surface.streaming) RemoteUiLifecycle.announceCreated(surface)
        }
    }

    /**
     * Unbind a host component. Scoped to [host] so a component disposed late cannot evict its successor.
     *
     * The only path that removes from [hosts] — `clear()` deliberately leaves them, since components belong
     * to the window rather than the kernel. So a component collected without `dispose()` leaves an entry
     * behind for its surface id. Bounded by the number of surfaces a user opens, and the entry is a dead
     * reference rather than a live subscription, but binding `attach`/`dispose` to the component's
     * composition is what makes it structural — for the change that gives these components a caller.
     */
    fun detach(
        surfaceId: String,
        host: RemoteUiSurfaceHost,
    ) {
        // Only the owner's removal ends the rendered lifetime. A late `dispose()` from a component that
        // was already displaced must not tell a live plugin its successor's surface went away.
        if (!hosts.remove(surfaceId, host)) return
        // The plugin is still streaming here, so this is the `destroyed` most worth sending: its UI was
        // closed by the user and it should stop doing work for a surface nobody is looking at.
        surfaces[surfaceId]?.let(RemoteUiLifecycle::announceDestroyed)
    }

    /**
     * Queue a user event for the plugin behind [surfaceId].
     *
     * @return `false` when no surface is registered, the receiving surface is closed, or a key
     *   was not requested by its declaration. Callers drop the event rather than retry a policy refusal.
     */
    fun emit(
        surfaceId: String,
        event: UIEvent,
    ): Boolean = surfaces[surfaceId]?.emit(event) == true

    /** The live surface for [surfaceId], if a plugin currently holds it. */
    fun surfaceOf(surfaceId: String): RemoteUiSurface? = surfaces[surfaceId]

    /**
     * Close and forget every surface.
     *
     * For kernel shutdown. [shared] outlives a single `KernelBootstrap`, so without this a restart would
     * come up holding claims from processes that no longer exist. Attached components are left attached
     * and simply see `connected == false` — they belong to the window, not to the kernel.
     */
    @Synchronized
    fun clear() {
        val closing = surfaces.keys.toList()
        closing.forEach { surfaceId ->
            surfaces.remove(surfaceId)?.let { surface ->
                // Same before-close ordering as unregister: a plugin whose stream is still up on the way
                // down gets a last `destroyed` rather than an abrupt completion.
                RemoteUiLifecycle.announceDestroyed(surface)
                surface.close()
            }
        }
        if (closing.isNotEmpty()) {
            logger.info(LogCategory.UI, "Closed all remote UI surfaces", mapOf("count" to closing.size))
        }
    }

    companion object {
        /**
         * The host-wide registry.
         *
         * One per process, because the surfaces it indexes are process-wide: the single IPC server every
         * plugin connects to is on one side and the single window's components on the other. Tests build
         * their own instances instead, which is why every collaborator takes one as a parameter rather
         * than reaching for this.
         */
        val shared = RemoteUiSurfaceRegistry()

        private val logger = BossLogger.forComponent("RemoteUiSurfaceRegistry")
    }
}
