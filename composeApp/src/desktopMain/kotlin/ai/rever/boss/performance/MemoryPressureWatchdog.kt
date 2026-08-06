package ai.rever.boss.performance

import ai.rever.boss.config.BossResourceMode
import ai.rever.boss.config.ResourceModeConfig
import ai.rever.boss.config.SystemMemory
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the watchdog wants the user told, once it has acted. */
data class MemoryPressureNotice(
    /** The tier now in force. */
    val appliedMode: BossResourceMode,
    /** Free physical memory when the decision was made, as a percentage. */
    val freePercent: Int,
    /**
     * True when the remaining savings need a restart, i.e. the session is already as
     * constrained as it can get live and plugin gating is the only lever left.
     */
    val restartWouldHelpFurther: Boolean,
)

/**
 * Tightens the resource tier when the machine is actually running out of memory, rather than
 * only guessing from its total RAM at startup.
 *
 * Needed because the startup decision reads *installed* RAM, which says nothing about what else
 * the user is running. A 64 GB machine hosting a build, a VM and BOSS can be closer to the wall
 * than an idle 8 GB one, and the wall here is not a swap slowdown - PartitionAlloc aborts the
 * process outright when it cannot allocate.
 *
 * Deliberately narrow in what it does:
 *
 *  - It only ever tightens to [BossResourceMode.LITE], never [BossResourceMode.ULTRA_LITE].
 *    ULTRA_LITE's distinguishing lever is plugin gating, and plugins already loaded cannot be
 *    unloaded to reclaim memory (a classloader that exists cannot be un-built, and disabling
 *    skips `dispose()` and orphans child processes). Claiming ULTRA_LITE live would advertise
 *    a saving that did not happen; the notice asks for a restart instead.
 *  - It requires the reading to be sustained, so one transient dip during a build does not
 *    cap the user's browser.
 *  - It never loosens. See [ResourceModeConfig.tightenTo].
 *  - It does not run at all when the session is already at its tightest, or when the operator
 *    turned it off.
 */
object MemoryPressureWatchdog {
    private val logger = BossLogger.forComponent("MemoryPressureWatchdog")

    /**
     * Available-memory fraction at or below which the machine counts as under pressure.
     *
     * **Not calibrated against a measured allocation failure.** It is a judgement call, chosen on
     * the reasoning that PartitionAlloc's pools are a fraction of the machine and 12% leaves room
     * to act before the wall. Treat it as provisional until someone reproduces the 5 Aug crash
     * with this watchdog running and records what the number actually was beforehand.
     *
     * It reads [SystemMemory.availableBytes], which is `MemAvailable` on Linux rather than
     * `MemFree`. That distinction matters more than the threshold: `MemFree` excludes the page
     * cache, and a healthy long-running Linux box sits in single digits by that measure. Against
     * `MemFree` almost any threshold produces false positives.
     */
    internal const val PRESSURE_THRESHOLD = 0.12

    /** How long pressure must persist before acting. */
    internal const val SUSTAIN_MS = 60_000L

    /** Gap between readings. Cheap enough to be irrelevant, frequent enough to be timely. */
    internal const val POLL_INTERVAL_MS = 15_000L

    private val started =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val _notices = MutableStateFlow<MemoryPressureNotice?>(null)

    /** The most recent notice, or null when the watchdog has never acted. */
    val notices: StateFlow<MemoryPressureNotice?> = _notices

    /** Clears the current notice once the UI has shown it. */
    fun acknowledge() {
        _notices.value = null
    }

    /**
     * Whether a downgrade is warranted, given how long pressure has been sustained.
     *
     * Pure so the hysteresis is testable without waiting a real minute.
     *
     * [freeFraction] is null when the reading failed, which must NOT count as pressure - an
     * unreadable MXBean is not evidence of a full machine, and acting on it would cap browsers
     * on a machine with plenty of room. The sustain clock resets on an unreadable reading for
     * the same reason.
     */
    internal fun shouldTighten(
        freeFraction: Double?,
        pressureSinceMs: Long?,
        nowMs: Long,
        currentMode: BossResourceMode,
    ): Boolean =
        currentMode == BossResourceMode.FULL &&
            freeFraction != null &&
            freeFraction <= PRESSURE_THRESHOLD &&
            pressureSinceMs != null &&
            nowMs - pressureSinceMs >= SUSTAIN_MS

    /**
     * Advances the "pressure started at" clock.
     *
     * Returns the timestamp pressure began, or null when there is none. Kept separate from
     * [shouldTighten] so both halves of the hysteresis are independently testable.
     */
    internal fun nextPressureSince(
        freeFraction: Double?,
        pressureSinceMs: Long?,
        nowMs: Long,
    ): Long? =
        when {
            freeFraction == null -> null
            freeFraction > PRESSURE_THRESHOLD -> null
            else -> pressureSinceMs ?: nowMs
        }

    /**
     * Whether a polling loop should be launched at all.
     *
     * Three reasons not to: the operator turned it off, the session is already reduced so the
     * only lever left needs a restart, or a loop is already running. The last is enforced rather
     * than merely documented, since two loops racing on the same one-way tighten is a confusing
     * way to learn this is only called once today.
     *
     * Claims the [started] flag as a side effect, so it must be called exactly once per [start].
     */
    private fun shouldStart(): Boolean {
        // Both declines are logged, with their reason. Logging only one of them left an empty
        // log with two possible meanings and no way to tell them apart.
        val decline =
            when {
                !ResourceModeConfig.livePressureEnabled -> {
                    "disabled by setting"
                }

                ResourceModeConfig.mode != BossResourceMode.FULL -> {
                    "the session is already reduced to ${ResourceModeConfig.mode.name}"
                }

                else -> {
                    null
                }
            }
        return if (decline != null) {
            logger.info(LogCategory.SYSTEM, "Live memory-pressure watchdog not started - $decline")
            false
        } else {
            started.compareAndSet(false, true)
        }
    }

    /**
     * Starts polling. Idempotent: only the first call starts a loop. Returns immediately without
     * starting anything when there is nothing this watchdog could do.
     */
    fun start(scope: CoroutineScope) {
        if (!shouldStart()) return

        scope.launch {
            var pressureSince: Long? = null
            while (isActive) {
                delay(POLL_INTERVAL_MS)

                val free = SystemMemory.freeFraction()
                val now = System.currentTimeMillis()
                pressureSince = nextPressureSince(free, pressureSince, now)

                if (shouldTighten(free, pressureSince, now, ResourceModeConfig.mode)) {
                    val freePercent = ((free ?: 0.0) * 100).toInt()
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Sustained low memory - tightening the resource mode",
                        mapOf(
                            "freePercent" to freePercent.toString(),
                            "sustainedMs" to (now - (pressureSince ?: now)).toString(),
                        ),
                    )
                    if (ResourceModeConfig.tightenTo(BossResourceMode.LITE)) {
                        _notices.value =
                            MemoryPressureNotice(
                                appliedMode = BossResourceMode.LITE,
                                freePercent = freePercent,
                                // LITE is as far as a live downgrade can go; plugin gating is
                                // the remaining saving and it needs a restart to take effect.
                                restartWouldHelpFurther = true,
                            )
                    }
                    return@launch
                }
            }
        }
    }
}
