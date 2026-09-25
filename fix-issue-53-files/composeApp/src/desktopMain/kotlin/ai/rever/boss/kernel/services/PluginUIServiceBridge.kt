package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.AUTHENTICATED_PROCESS_ID
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.UIRegistrationResponse
import ai.rever.boss.ipc.proto.UIUnregistration
import ai.rever.boss.ipc.proto.WidgetUpdate
import ai.rever.boss.kernel.ui.RemoteUiSurface
import ai.rever.boss.kernel.ui.RemoteUiSurfaceDescriptor
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.kernel.ui.SurfaceRegistration
import ai.rever.boss.kernel.ui.SurfaceStream
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toKotlin
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Kernel-side implementation of `PluginUIService` — the transport that makes out-of-process plugin UI
 * actually work in both directions.
 *
 * **The host used to have this backwards.** `ui_protocol.proto` makes the plugin the client (it streams
 * `WidgetUpdate`s) and the kernel the server (it streams `UIEvent`s back), but `RemotePanelComponent`
 * and `RemoteTabComponent` each opened a `PluginUIServiceCoroutineStub` and dialled *out* to the plugin.
 * Because the request stream of `StreamUI` is typed `WidgetUpdate`, every outgoing `UIEvent` had to be
 * repacked as a `WidgetUpdate` — a message with no room for an event — so what crossed the wire was a
 * `surface_id` and nothing else. Inbound `UIEvent`s, arriving on the response stream where the host was
 * pretending to be a plugin, were logged at debug and discarded. No click, keystroke or selection could
 * reach a plugin, and no plugin-pushed tree could reach the host: the whole path was decorative.
 *
 * This class is the correct half of that inversion, and [RemoteUiSurfaceRegistry] is where it meets the
 * components. Nothing in the host dials a plugin's UI service any more.
 *
 * @param registry the surface directory to route through. Defaults to the host-wide one; tests pass
 *   their own so two suites cannot see each other's surfaces.
 * @param bindTimeoutMs how long a `StreamUI` call may stay open without naming a surface — see
 *   [DEFAULT_BIND_TIMEOUT_MS]. A parameter only so a test can assert the reaping in milliseconds instead of
 *   spending the production deadline doing it.
 */
class PluginUIServiceBridge(
    private val registry: RemoteUiSurfaceRegistry = RemoteUiSurfaceRegistry.shared,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
) : PluginUIServiceGrpcKt.PluginUIServiceCoroutineImplBase() {
    override suspend fun registerUI(request: UIRegistration): UIRegistrationResponse {
        // Read before anything below can suspend - see the file doc on AUTHENTICATED_PROCESS_ID for why
        // that matters. registerUI has no earlier suspension point, so this is the first statement.
        val authenticatedProcessId = AUTHENTICATED_PROCESS_ID.get()
        // Preferred over request.processId, not merely checked against it: process_id is a field the
        // calling plugin wrote, so a hostile one could put a trusted process's id there and register a
        // surface as if it owned that identity. A verified caller's own identity is what actually gets
        // recorded, regardless of what it claimed - unverified callers (no token attached yet, or none
        // recognised) fall back to the claim exactly as before this existed.
        val effectiveProcessId = authenticatedProcessId ?: request.processId
        return when (
            val outcome =
                registry.register(
                    request.surfaceId,
                    effectiveProcessId,
                    request.descriptor(),
                    identityVerified = authenticatedProcessId != null,
                )
        ) {
            is SurfaceRegistration.Rejected -> {
                logger.warn(
                    LogCategory.UI,
                    "Refused a UI surface registration",
                    mapOf("surfaceId" to request.surfaceId, "reason" to outcome.reason),
                )
                registrationResponse(success = false, error = outcome.reason)
            }

            is SurfaceRegistration.Accepted -> {
                // Applied before the stream exists so a surface renders from the moment it is opened,
                // rather than staying blank until the plugin's first update.
                if (request.hasInitialTree()) {
                    outcome.surface.pushTree(request.initialTree.toKotlin())
                }
                registrationResponse(success = true, error = "")
            }
        }
    }

    /**
     * Bidirectional stream: inbound `WidgetUpdate`s are routed to their surface, outbound `UIEvent`s are
     * drained from that surface's ordered queue.
     *
     * The two halves are deliberately independent. A plugin that has sent its tree and has nothing more
     * to say may half-close its request stream and keep listening for events forever — so the response
     * flow outlives the request flow and ends only when the surface closes or the plugin goes away.
     *
     * `StreamUI` carries no surface id of its own, so the stream is bound by the `surface_id` of its
     * first `WidgetUpdate` and pinned to it. That is a limitation of the protocol, not a choice: a
     * plugin that registers a surface and then streams nothing cannot be matched to it, and gets
     * `INVALID_ARGUMENT` when its request stream ends rather than an RPC that hangs open.
     */
    override fun streamUI(requests: Flow<WidgetUpdate>): Flow<UIEvent> {
        // Read before constructing the flow below, and captured into a local rather than read again from
        // inside it. Everything from `channelFlow` on runs in its own producer coroutine, and grpc-kotlin
        // gives no guarantee that coroutine shares a thread - or an io.grpc.Context, which is thread-local
        // - with this call. This line does, because a plain (non-suspend) function body runs synchronously
        // on whatever thread invoked it, which is the one ProcessAuthServerInterceptor attached the
        // Context to. See AUTHENTICATED_PROCESS_ID's own doc for the general rule this follows.
        val callerProcessId = AUTHENTICATED_PROCESS_ID.get()
        return channelFlow {
            val bound = CompletableDeferred<RemoteUiSurface>()
            // Written by the pump the instant it claims, and read by the finally below. The claim happens
            // inside the pump, so a `finally` guarding only the collect would miss it: an RPC cancelled
            // between `openStream()` returning Bound and this coroutine resuming from `await()` would
            // leave the surface in the registry with `streaming == true` forever — the component reading
            // *connected* for a dead plugin, and every respawn's RegisterUI refused for the rest of the
            // session, because the reclaim rule only takes over claims that are not streaming. That is
            // precisely the frozen-not-disconnected state this transport exists to avoid, and it is the
            // likeliest crash: a plugin dying right after its first WidgetUpdate.
            val claimed = AtomicReference<RemoteUiSurface>(null)
            val pump = launch { pumpUpdates(requests, bound, claimed, callerProcessId) }
            try {
                // Throws the StatusException the pump resolved this with when the id is unusable.
                //
                // Bounded, because the INVALID_ARGUMENT below is raised from onCompletion and so needs the
                // request stream to actually end. A plugin that opens the call and then neither sends nor
                // half-closes would otherwise park here forever with the pump on a stream that never
                // completes, and there is no server deadline to reap it.
                val surface = withTimeout(bindTimeoutMs) { bound.await() }
                // One collector for one queue: this is the hop that turns interaction order into wire
                // order, so it must stay single — see RemoteUiSurface.claimStream.
                surface.events().collect { event -> send(event) }
            } catch (timeout: TimeoutCancellationException) {
                // Our own timeout, not the caller's cancellation, so converting it to a status is right:
                // otherwise the plugin sees CANCELLED with a Kotlin message and no idea what it did wrong.
                throw StatusException(
                    Status.DEADLINE_EXCEEDED.withCause(timeout).withDescription(SILENT_STREAM),
                )
            } finally {
                // channelFlow does not complete until its children do, and the pump is collecting a
                // request stream the plugin may keep open indefinitely. Without this cancel, closing a
                // surface completed the event queue but left the RPC hanging: the plugin's response flow
                // never ended, so `UnregisterUI` looked like it had silently frozen the stream. The
                // surface is gone by now, so there is nothing left for the pump to route.
                //
                // cancelAndJoin, not cancel: cancel() only *requests* it and returns, so the read below
                // could race the pump sitting between `openStream()` (which has already set
                // streaming = true) and its write to `claimed` — two adjacent statements with no
                // suspension point between them, executing on a different thread, since grpc-kotlin
                // builds its RPC scope from EmptyCoroutineContext and an RPC cancellation cancels both
                // coroutines at once. Losing that read would strand the claim exactly as above.
                // NonCancellable because arriving here *via* cancellation is the common case.
                //
                // Joining is also why this cannot be pump.invokeOnCompletion: the pump completes when a
                // plugin merely half-closes its request stream, which is legal and must not tear the
                // surface down.
                withContext(NonCancellable) { pump.cancelAndJoin() }
                // Safe to reach twice — closeStream removes by identity and close() is idempotent.
                claimed.get()?.let(registry::closeStream)
            }
        }
    }

    override suspend fun unregisterUI(request: UIUnregistration): Empty {
        // Same reasoning as registerUI: read before anything here can suspend.
        val callerProcessId = AUTHENTICATED_PROCESS_ID.get()
        val removed = registry.unregister(request.surfaceId, callerProcessId)
        if (!removed) {
            logger.debug(
                LogCategory.UI,
                "Ignoring UnregisterUI for an unknown surface",
                mapOf("surfaceId" to request.surfaceId),
            )
        }
        return Empty.getDefaultInstance()
    }

    /**
     * Drain the plugin's update stream into its surface.
     *
     * Never fails the call by throwing: a broken request stream means the transport is already gone, and
     * letting that escape would race the response flow's own teardown for which error the plugin sees.
     * Binding problems are reported through [bound] instead, so exactly one status describes them.
     */
    private suspend fun pumpUpdates(
        requests: Flow<WidgetUpdate>,
        bound: CompletableDeferred<RemoteUiSurface>,
        claimed: AtomicReference<RemoteUiSurface>,
        callerProcessId: String?,
    ) {
        var surface: RemoteUiSurface? = null
        var refused = false
        var broken = false
        var strays = 0L
        requests
            .onEach { update ->
                val target = surface
                when {
                    target != null && target.surfaceId == update.surfaceId -> {
                        target.applyUpdate(update)
                    }

                    target != null -> {
                        strays++
                        noteStrayUpdate(target.surfaceId, update.surfaceId, strays)
                    }

                    !refused -> {
                        when (val opened = registry.openStream(update.surfaceId, callerProcessId)) {
                            is SurfaceStream.Bound -> {
                                surface = opened.surface
                                // Recorded before anything can suspend, so the claim is never held by a
                                // coroutine that no longer has a way to release it.
                                claimed.set(opened.surface)
                                bound.complete(opened.surface)
                                opened.surface.applyUpdate(update)
                            }

                            is SurfaceStream.Refused -> {
                                refused = true
                                bound.completeExceptionally(StatusException(opened.status()))
                            }
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }.catch { cause ->
                broken = true
                logger.warn(
                    LogCategory.UI,
                    "Plugin widget-update stream ended abnormally - closing the surface",
                    mapOf("surfaceId" to surface?.surfaceId, "error" to cause.message),
                )
                // Whatever the reason — a dead wire, or a failure applying an update — this pump has
                // stopped reading the request stream, and the call cannot usefully continue. Leaving the
                // response side running would keep the surface claimed and reading *connected* while the
                // plugin's next send blocked on flow control forever. Closing the surface completes the
                // event queue, which completes the response flow, which ends the RPC.
                surface?.let(registry::closeStream)
            }.onCompletion { cause ->
                // An unbindable stream is one that ended of its own accord without ever naming a surface.
                // A cancellation is not that, and neither is a broken wire — `catch` above swallows the
                // cause to keep one status on the call, so `broken` is what distinguishes them here.
                val endedCleanly = cause == null && !broken
                val neverBound = surface == null && !refused
                if (endedCleanly && neverBound) {
                    bound.completeExceptionally(
                        StatusException(Status.INVALID_ARGUMENT.withDescription(UNIDENTIFIED_STREAM)),
                    )
                }
            }.collect()
    }

    /**
     * The status a refusal goes out as.
     *
     * Distinct codes because the recoveries differ: `NOT_FOUND` means "register the surface and open a new
     * stream", `FAILED_PRECONDITION` means "someone else already owns this one, and always will", and
     * `PERMISSION_DENIED` means "you are not the process that owns it - retry as that process if you can,
     * do not retry as this one". A plugin should not have to parse a description to tell those apart.
     */
    private fun SurfaceStream.Refused.status(): Status =
        when (this) {
            is SurfaceStream.Unregistered -> Status.NOT_FOUND.withDescription(reason)
            is SurfaceStream.AlreadyStreaming -> Status.FAILED_PRECONDITION.withDescription(reason)
            is SurfaceStream.Forbidden -> Status.PERMISSION_DENIED.withDescription(reason)
        }

    /**
     * Report an update for a surface this stream is not bound to, first and then every Nth.
     *
     * Throttled because a plugin that multiplexes two surfaces onto one call sends these at its own update
     * rate, and an unthrottled warning is a log-flooding primitive handed across the boundary.
     */
    private fun noteStrayUpdate(
        streamSurfaceId: String,
        updateSurfaceId: String,
        occurrences: Long,
    ) {
        if (occurrences != 1L && occurrences % STRAY_LOG_INTERVAL != 0L) return
        logger.warn(
            LogCategory.UI,
            "Ignoring a WidgetUpdate for a surface this stream is not bound to - " +
                "use one StreamUI call per surface",
            mapOf(
                "streamSurfaceId" to streamSurfaceId,
                "updateSurfaceId" to updateSurfaceId,
                "occurrences" to occurrences,
            ),
        )
    }

    /**
     * Everything a registration declares beyond identity.
     *
     * Kept on the surface for the follow-up that places it in the window, which is what needs
     * `surface_type` to choose panel vs tab and `default_slot` to position a panel.
     */
    private fun UIRegistration.descriptor(): RemoteUiSurfaceDescriptor =
        RemoteUiSurfaceDescriptor(
            surfaceType = surfaceType,
            displayName = displayName,
            iconName = iconName,
            defaultSlot = defaultSlot,
        )

    private fun registrationResponse(
        success: Boolean,
        error: String,
    ): UIRegistrationResponse =
        UIRegistrationResponse
            .newBuilder()
            .setSuccess(success)
            .setErrorMessage(error)
            .build()

    companion object {
        /**
         * How long a `StreamUI` call may stay open without naming a surface.
         *
         * Generous on purpose: it bounds a stuck call, it is not a latency budget. A plugin sends its first
         * update immediately after opening the stream, so reaching this means the plugin is not going to.
         */
        val DEFAULT_BIND_TIMEOUT_MS = 30.seconds.inWholeMilliseconds

        private val logger = BossLogger.forComponent("PluginUIServiceBridge")

        /** Log the first stray update on a stream, then every Nth. See the throttle in pumpUpdates. */
        private const val STRAY_LOG_INTERVAL = 256L

        private const val SILENT_STREAM =
            "StreamUI takes its surface identity from the surface_id of its first WidgetUpdate; " +
                "none arrived, and the request stream stayed open"

        private const val UNIDENTIFIED_STREAM =
            "StreamUI takes its surface identity from the surface_id of its first WidgetUpdate; " +
                "the request stream ended without sending one"
    }
}
