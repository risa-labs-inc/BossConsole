package ai.rever.boss.plugin.browser

import ai.rever.boss.config.SwipeNavSettingsManager
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** The part of a macOS scroll sequence which comes from fingers still touching the trackpad. */
internal data class ScrollGestureSnapshot(
    val id: Long,
    val active: Boolean,
    /** Redundant with active today; retained to make claim visibility an explicit state boundary. */
    val observable: Boolean = active,
    val horizontalEvents: Int = 0,
    val accumX: Double = 0.0,
    val verticalPath: Double = 0.0,
    val direction: Int = 0,
    val rejected: Boolean = false,
    val reversed: Boolean = false,
    val reachedCommit: Boolean = false,
    val mayBegin: Boolean = false,
    val beganAtEpochMs: Long = 0,
    val claimantIds: Set<Long> = emptySet(),
)

internal data class ScrollGestureEnd(
    val id: Long,
    val cancelled: Boolean,
    val accumX: Double,
    val verticalPath: Double,
    val rejected: Boolean,
    val reversed: Boolean,
    val claimantIds: Set<Long>,
)

/** Claim [claimantId] only while this exact native sequence is still active. */
internal fun claimScrollGesture(
    current: ScrollGestureSnapshot,
    claimantId: Long,
): ScrollGestureSnapshot? =
    when {
        !current.active || !current.observable -> null
        claimantId in current.claimantIds -> current
        else -> current.copy(claimantIds = current.claimantIds + claimantId)
    }

@Suppress("LongParameterList", "CyclomaticComplexMethod") // One branch per native phase, including replacement.
internal fun scrollGestureTransition(
    current: ScrollGestureSnapshot,
    phase: Long,
    momentumPhase: Long,
    dx: Double = 0.0,
    dy: Double = 0.0,
    nowEpochMs: Long = 0,
): Pair<ScrollGestureSnapshot, ScrollGestureEnd?> {
    val terminalDx = if (momentumPhase == CG_SCROLL_PHASE_NONE) dx else 0.0
    val terminalDy = if (momentumPhase == CG_SCROLL_PHASE_NONE) dy else 0.0
    return when {
        phase and CG_SCROLL_PHASE_CANCELLED != 0L -> {
            finish(current, terminalDx, terminalDy, cancelled = true)
        }

        phase and CG_SCROLL_PHASE_ENDED != 0L -> {
            finish(current, terminalDx, terminalDy, cancelled = false)
        }

        momentumPhase != CG_SCROLL_PHASE_NONE -> {
            if (current.active) {
                finish(current, 0.0, 0.0, cancelled = true)
            } else {
                current.copy(observable = false) to null
            }
        }

        phase and CG_SCROLL_PHASE_MAY_BEGIN != 0L -> {
            ScrollGestureSnapshot(
                current.id + 1,
                true,
                mayBegin = true,
                beganAtEpochMs = nowEpochMs,
            ) to finish(current, 0.0, 0.0, cancelled = true).second
        }

        phase and CG_SCROLL_PHASE_BEGAN != 0L -> {
            accumulate(
                if (current.active && current.mayBegin) {
                    current.copy(mayBegin = false)
                } else {
                    ScrollGestureSnapshot(current.id + 1, true, beganAtEpochMs = nowEpochMs)
                },
                dx,
                dy,
            ) to if (current.mayBegin) null else finish(current, 0.0, 0.0, cancelled = true).second
        }

        phase and CG_SCROLL_PHASE_CHANGED != 0L -> {
            (if (current.active) accumulate(current, dx, dy) else current) to null
        }

        else -> {
            current to null
        }
    }
}

private fun finish(
    current: ScrollGestureSnapshot,
    dx: Double,
    dy: Double,
    cancelled: Boolean,
): Pair<ScrollGestureSnapshot, ScrollGestureEnd?> {
    if (!current.active) return current to null
    val final = accumulate(current, dx, dy).copy(active = false, observable = false)
    return final to
        ScrollGestureEnd(
            final.id,
            cancelled,
            final.accumX,
            final.verticalPath,
            final.rejected,
            final.reversed,
            final.claimantIds,
        )
}

@Suppress("CyclomaticComplexMethod") // Each branch is one documented Chrome cancellation tier.
private fun accumulate(
    current: ScrollGestureSnapshot,
    dx: Double,
    dy: Double,
): ScrollGestureSnapshot {
    val x = current.accumX + dx
    val vertical = current.verticalPath + kotlin.math.abs(dy)
    // Match the page: retain leading vertical travel, but evaluate tiers only on horizontal samples.
    if (dx == 0.0) return current.copy(verticalPath = vertical)
    val events = current.horizontalEvents + 1
    val direction =
        current.direction.takeIf { it != 0 } ?: if (x < 0) {
            -1
        } else if (x > 0) {
            1
        } else {
            0
        }
    val reversed = current.direction != 0 && (if (x < 0) -1 else 1) != current.direction
    val xDelta = kotlin.math.abs(x)
    val rejected =
        current.rejected || reversed || (
            !current.reachedCommit &&
                (
                    vertical > SWIPE_CANCEL_STRONG_RATIO * xDelta ||
                        (
                            vertical * SWIPE_CANCEL_MIXED_RATIO > xDelta &&
                                vertical > SWIPE_COMMIT_PX * SWIPE_CANCEL_VERTICAL_LOW
                        ) ||
                        vertical > SWIPE_COMMIT_PX * SWIPE_CANCEL_VERTICAL_HIGH
                )
        )
    return current.copy(
        active = true,
        observable = true,
        accumX = x,
        horizontalEvents = events,
        verticalPath = vertical,
        direction = direction,
        rejected = rejected,
        reversed = current.reversed || reversed,
        reachedCommit = current.reachedCommit || (!rejected && events >= SWIPE_MIN_EVENTS && xDelta >= SWIPE_COMMIT_PX),
    )
}

internal const val SWIPE_COMMIT_PX = 90.0
internal const val SWIPE_MIN_EVENTS = 3
internal const val SWIPE_CANCEL_STRONG_RATIO = 2.0
internal const val SWIPE_CANCEL_MIXED_RATIO = 1.3
internal const val SWIPE_CANCEL_VERTICAL_LOW = 0.125
internal const val SWIPE_CANCEL_VERTICAL_HIGH = 3.0

internal enum class ScrollPhaseAvailability { DISABLED, STARTING, AVAILABLE, PERMISSION_DENIED, FAILED }

/** A session owns its native resources on the observation thread, including partial setup. */
internal interface ScrollPhaseSession : AutoCloseable {
    /** False means the run loop no longer has a live source. */
    fun poll(): Boolean
}

internal class ScrollPhasePermissionDenied : IllegalStateException()

/**
 * Serializes transitions, claims, terminal publication and delivery. Native setup/poll/cleanup
 * runs off the UI thread. A generation fences callbacks from disabled or replaced sessions.
 * Terminal history is bounded and non-destructive so multiple home surfaces can reconcile it.
 */
@Suppress("TooManyFunctions")
internal class ScrollPhaseObserver(
    private val open: (onEvent: (Long, Long, Double, Double) -> Unit, onInterrupted: () -> Unit) -> ScrollPhaseSession,
    private val launch: (() -> Unit) -> Unit,
    private val publish: (String, String) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val listeners = mutableMapOf<Long, (ScrollGestureEnd) -> Unit>()
    private val nextClaimantId = AtomicLong()
    private var snapshot = ScrollGestureSnapshot(0, false)
    private var generation = 0L
    private var enabled = false
    private var previousTerminatedAtEpochMs: Long? = null
    private val terminals = ArrayDeque<String>()
    private val mutableAvailability = MutableStateFlow(ScrollPhaseAvailability.DISABLED)
    val availability = mutableAvailability.asStateFlow()

    init {
        publish(MacOSScrollGesturePhases.PHASE_PROPERTY, "unavailable")
        publish(MacOSScrollGesturePhases.TERMINALS_PROPERTY, "")
    }

    fun setEnabled(value: Boolean) {
        val sessionId =
            synchronized(lock) {
                // Permission denial retries only after an explicit off/on transition, matching the
                // Settings recovery instructions; ordinary failures may retry a redundant enable.
                if (enabled == value && (!value || availability.value != ScrollPhaseAvailability.FAILED)) return
                enabled = value
                generation++
                cancelCurrent()
                publish(MacOSScrollGesturePhases.PHASE_PROPERTY, "unavailable")
                mutableAvailability.value =
                    if (value) ScrollPhaseAvailability.STARTING else ScrollPhaseAvailability.DISABLED
                generation
            }
        if (value) launch { observe(sessionId) }
    }

    fun register(onEnded: (ScrollGestureEnd) -> Unit): ScrollGestureClaim {
        val id = nextClaimantId.incrementAndGet()
        synchronized(lock) { listeners[id] = onEnded }
        return ScrollGestureClaim({ claimCurrent(id) }, {
            synchronized(lock) { listeners.remove(id) }
            Unit
        })
    }

    private fun claimCurrent(id: Long): String? =
        synchronized(lock) {
            if (!enabled || availability.value != ScrollPhaseAvailability.AVAILABLE) return null
            val claimed = claimScrollGesture(snapshot, id) ?: return null
            snapshot = claimed
            "${claimed.id}:${claimed.beganAtEpochMs}"
        }

    private fun isCurrent(id: Long): Boolean = synchronized(lock) { enabled && generation == id }

    @Suppress("TooGenericExceptionCaught") // Native setup and polling failures must cancel and release the session.
    private fun observe(id: Long) {
        try {
            if (!isCurrent(id)) return
            open(
                { phase, momentum, dx, dy -> consume(id, phase, momentum, dx, dy) },
                { synchronized(lock) { if (isCurrent(id)) cancelCurrent(clock()) } },
            ).use { session ->
                synchronized(lock) {
                    if (!isCurrent(id)) return
                    mutableAvailability.value = ScrollPhaseAvailability.AVAILABLE
                }
                while (isCurrent(id)) {
                    check(session.poll()) { "Native scroll phase run loop stopped" }
                }
            }
        } catch (error: Exception) {
            failed(id, error)
        } catch (error: LinkageError) {
            failed(id, error)
        } finally {
            synchronized(lock) {
                if (isCurrent(id)) {
                    cancelCurrent(clock())
                    publish(MacOSScrollGesturePhases.PHASE_PROPERTY, "unavailable")
                    if (availability.value == ScrollPhaseAvailability.AVAILABLE) {
                        mutableAvailability.value = ScrollPhaseAvailability.FAILED
                    }
                }
            }
        }
    }

    private fun failed(
        id: Long,
        error: Throwable,
    ): Unit =
        synchronized(lock) {
            if (!isCurrent(id)) return
            cancelCurrent(clock())
            mutableAvailability.value =
                if (error is ScrollPhasePermissionDenied) {
                    ScrollPhaseAvailability.PERMISSION_DENIED
                } else {
                    reportFailure(error)
                    ScrollPhaseAvailability.FAILED
                }
        }

    private fun consume(
        id: Long,
        phase: Long,
        momentum: Long,
        dx: Double,
        dy: Double,
    ): Unit =
        synchronized(lock) {
            if (!isCurrent(id)) return
            val callbackAt = clock()
            val before = snapshot
            val (after, end) = scrollGestureTransition(before, phase, momentum, dx, dy, callbackAt)
            snapshot = after
            // Publish the replaced contact's cancellation before announcing the new contact.
            end?.let { deliver(it, callbackAt) }
            if (after.active && (!before.active || after.id != before.id)) {
                val previous = previousTerminatedAtEpochMs?.let { ":$it" }.orEmpty()
                publish(
                    MacOSScrollGesturePhases.PHASE_PROPERTY,
                    "${after.id}:active:${after.beganAtEpochMs}$previous",
                )
            }
        }

    private fun cancelCurrent(endedAtEpochMs: Long = clock()) {
        val (after, end) = scrollGestureTransition(snapshot, CG_SCROLL_PHASE_CANCELLED, 0)
        snapshot = after
        end?.let { deliver(it, endedAtEpochMs) }
    }

    private fun deliver(
        end: ScrollGestureEnd,
        endedAtEpochMs: Long,
    ) {
        previousTerminatedAtEpochMs = endedAtEpochMs
        val state = if (end.cancelled) "cancelled" else "ended"
        val wire = "${end.id}:$state:${end.accumX}:${end.verticalPath}:${end.rejected}:${end.reversed}"
        terminals.addLast(wire)
        if (terminals.size > TERMINAL_HISTORY_LIMIT) terminals.removeFirst()
        // History comes first: a reader observing the next active id can already recover the end.
        publish(MacOSScrollGesturePhases.TERMINALS_PROPERTY, terminals.joinToString(";"))
        publish(MacOSScrollGesturePhases.PHASE_PROPERTY, wire)
        // Listener callbacks must only enqueue work. This tap thread holds the monitor also used
        // by page claims, so synchronously entering the renderer here would deadlock.
        end.claimantIds.forEach { id -> listeners[id]?.let { runCatching { it(end) } } }
    }

    companion object {
        const val TERMINAL_HISTORY_LIMIT = 32
    }
}

/** Opens and releases every CoreFoundation resource on the same native observation thread. */
// All acquired resources are released even if another cleanup fails.
@Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
internal fun openScrollPhaseSession(
    api: ApplicationServices,
    mode: Pointer,
    onEvent: (Long, Long, Double, Double) -> Unit,
    onInterrupted: () -> Unit,
): ScrollPhaseSession {
    if (!api.CGPreflightListenEventAccess()) throw ScrollPhasePermissionDenied()
    var tap: Pointer? = null
    var source: Pointer? = null
    val loop = api.CFRunLoopGetCurrent()
    var added = false
    var callbackFailure: Throwable? = null
    val callback =
        ScrollEventCallback { _, type, event, _ ->
            try {
                when {
                    type == CG_EVENT_SCROLL_WHEEL && event != null -> {
                        onEvent(
                            api.CGEventGetIntegerValueField(event, CG_SCROLL_WHEEL_EVENT_SCROLL_PHASE),
                            api.CGEventGetIntegerValueField(event, CG_SCROLL_WHEEL_EVENT_MOMENTUM_PHASE),
                            api.CGEventGetIntegerValueField(event, CG_SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_2).toDouble(),
                            api.CGEventGetIntegerValueField(event, CG_SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_1).toDouble(),
                        )
                    }

                    type == CG_EVENT_TAP_DISABLED_BY_TIMEOUT || type == CG_EVENT_TAP_DISABLED_BY_USER_INPUT -> {
                        onInterrupted()
                        tap?.let { api.CGEventTapEnable(it, true) }
                    }
                }
            } catch (error: Exception) {
                callbackFailure = error
                onInterrupted()
            } catch (error: LinkageError) {
                callbackFailure = error
                onInterrupted()
            }
            event
        }

    fun cleanup() {
        try {
            if (added) api.CFRunLoopRemoveSource(loop, checkNotNull(source), mode)
        } finally {
            try {
                source?.let { api.CFRelease(it) }
            } finally {
                tap?.let { port ->
                    try {
                        api.CFMachPortInvalidate(port)
                    } finally {
                        api.CFRelease(port)
                    }
                }
            }
        }
    }
    try {
        tap = checkNotNull(api.CGEventTapCreate(1, 0, 1, 1L shl CG_EVENT_SCROLL_WHEEL, callback, null))
        source = checkNotNull(api.CFMachPortCreateRunLoopSource(null, tap, 0))
        api.CFRunLoopAddSource(loop, source, mode)
        added = true
        api.CGEventTapEnable(tap, true)
        return object : ScrollPhaseSession {
            // Retained until the tap is invalidated, including between native callbacks.
            private val retainedCallback = callback

            override fun poll(): Boolean {
                // A bounded wait avoids the stop-before-run race and lets an off switch tear down
                // an idle tap without needing any further mouse/trackpad event.
                callbackFailure?.let { throw it }
                val result = api.CFRunLoopRunInMode(mode, 0.25, false)
                callbackFailure?.let { throw it }
                java.lang.ref.Reference
                    .reachabilityFence(retainedCallback)
                return result != 1 && result != 2
            }

            override fun close() {
                try {
                    cleanup()
                } finally {
                    java.lang.ref.Reference
                        .reachabilityFence(retainedCallback)
                }
            }
        }
    } catch (error: Throwable) {
        cleanup()
        throw error
    }
}

internal object MacOSScrollGesturePhases {
    const val PHASE_PROPERTY = "boss.browser.swipe.phase"
    const val TERMINALS_PROPERTY = "boss.browser.swipe.terminals"
    private val logger = BossLogger.forComponent("MacOSScrollGesturePhases")
    private val observer =
        ScrollPhaseObserver(
            open = { event, interrupted ->
                val api = Native.load("ApplicationServices", ApplicationServices::class.java)
                // RunInMode requires a concrete mode, not the common-modes registration sentinel.
                val mode =
                    NativeLibrary
                        .getInstance("CoreFoundation")
                        .getGlobalVariableAddress("kCFRunLoopDefaultMode")
                        .getPointer(0)
                openScrollPhaseSession(api, mode, event, interrupted)
            },
            launch = { task ->
                Thread(task, "boss-scroll-phases").apply {
                    isDaemon = true
                    start()
                }
            },
            publish = { key, value -> System.setProperty(key, value) },
            reportFailure = { logger.warn(LogCategory.BROWSER, "Native scroll phase observation failed", error = it) },
        )
    val availability = observer.availability

    fun ensureStarted() {
        SwipeNavSettingsManager.onEnabledChanged = { observer.setEnabled(SystemUtils.isMacOS && it) }
        observer.setEnabled(SystemUtils.isMacOS && SwipeNavSettingsManager.isEnabled())
    }

    fun register(onEnded: (ScrollGestureEnd) -> Unit): ScrollGestureClaim = observer.register(onEnded)

    fun isAvailable(): Boolean = availability.value == ScrollPhaseAvailability.AVAILABLE
}

internal class ScrollGestureClaim(
    private val claim: () -> String?,
    private val unregister: () -> Unit,
) : AutoCloseable {
    fun currentGestureToken(): String? = claim()

    override fun close() = unregister()
}

internal fun interface ScrollEventCallback : Callback {
    fun invoke(
        proxy: Pointer?,
        type: Int,
        event: Pointer?,
        refcon: Pointer?,
    ): Pointer?
}

// These names are the native ApplicationServices symbols JNA binds verbatim.
@Suppress("FunctionNaming", "LongParameterList", "TooManyFunctions", "ktlint:standard:function-naming")
internal interface ApplicationServices : Library {
    fun CGPreflightListenEventAccess(): Boolean

    fun CGEventTapCreate(
        location: Int,
        placement: Int,
        options: Int,
        mask: Long,
        callback: ScrollEventCallback,
        refcon: Pointer?,
    ): Pointer?

    fun CGEventGetIntegerValueField(
        event: Pointer,
        field: Int,
    ): Long

    fun CGEventTapEnable(
        tap: Pointer,
        enable: Boolean,
    )

    fun CFMachPortCreateRunLoopSource(
        allocator: Pointer?,
        port: Pointer,
        order: Long,
    ): Pointer?

    fun CFRunLoopGetCurrent(): Pointer

    fun CFRunLoopAddSource(
        loop: Pointer,
        source: Pointer,
        mode: Pointer,
    )

    fun CFRunLoopRunInMode(
        mode: Pointer,
        seconds: Double,
        returnAfterSourceHandled: Boolean,
    ): Int

    fun CFRunLoopRemoveSource(
        loop: Pointer,
        source: Pointer,
        mode: Pointer,
    )

    fun CFMachPortInvalidate(port: Pointer)

    fun CFRelease(value: Pointer)
}

private const val CG_EVENT_SCROLL_WHEEL = 22
private const val CG_EVENT_TAP_DISABLED_BY_TIMEOUT = -2
private const val CG_EVENT_TAP_DISABLED_BY_USER_INPUT = -1
private const val CG_SCROLL_WHEEL_EVENT_SCROLL_PHASE = 99
private const val CG_SCROLL_WHEEL_EVENT_MOMENTUM_PHASE = 123
private const val CG_SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_1 = 96
private const val CG_SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_2 = 97
private const val CG_SCROLL_PHASE_NONE = 0L
private const val CG_SCROLL_PHASE_BEGAN = 1L
private const val CG_SCROLL_PHASE_CHANGED = 2L
private const val CG_SCROLL_PHASE_ENDED = 4L
private const val CG_SCROLL_PHASE_CANCELLED = 8L
private const val CG_SCROLL_PHASE_MAY_BEGIN = 128L
