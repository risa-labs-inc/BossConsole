/*
 * detekt: this file catches Throwable and returns early a lot, both deliberately.
 *
 * Every call here crosses into a native library through JNA, where the failure modes are
 * UnsatisfiedLinkError, NoClassDefFoundError and Error subtypes as well as Exception - so catching
 * anything narrower would let exactly the interesting failures escape into a UI event handler. And
 * the contract of InputCapture is that a grab either happens or is reported as not having happened;
 * a throw crossing this boundary would leave a session half-entered, which is the one outcome the
 * whole design exists to prevent. The early returns are that contract: each one is a distinct
 * reason the platform cannot do this, answered where it is discovered.
 */
@file:Suppress("TooGenericExceptionCaught", "ReturnCount")

package ai.rever.boss.fullscreen

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private val logger = BossLogger.forComponent("PointerConfiner")

/**
 * Keeps the pointer inside a rectangle by watching where it is and putting it back.
 *
 * Windows has a system call for exactly this (`ClipCursor`) and uses it instead. macOS and X11 have
 * no equivalent an ordinary application can use without either a permission prompt or a full
 * device grab that would also freeze the visible cursor, so on those two the honest implementation
 * is to poll and warp, which is what this is.
 *
 * **Why polling, when AWT can report mouse motion.** AWT only sees the pointer while it is over one
 * of this app's own windows. The single event that matters here - the pointer crossing the display
 * edge onto the next monitor - happens once it has already left, where no listener of ours fires.
 * [MouseInfo] reads the real position wherever it is, and needs no permission on any platform.
 *
 * The thread exists only while a session is captured, so the cost is bounded by the mode being on.
 *
 * @param warp Moves the cursor to a screen point. Returns false if the platform could not.
 */
class PointerConfiner(
    private val warp: (x: Double, y: Double) -> Boolean,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var bounds: Rectangle? = null

    @Volatile
    private var worker: Thread? = null

    /**
     * Begin confining to [area], in screen coordinates.
     *
     * @return false if the platform cannot warp at all, in which case nothing is started and the
     *   caller reports [CaptureLimitation.POINTER_NOT_CONFINED] rather than pretending.
     */
    fun start(area: Rectangle): Boolean {
        bounds = area
        if (!running.compareAndSet(false, true)) return true

        // Prove the platform can actually warp before claiming the pointer is confined. A probe
        // that puts the cursor where it already is costs nothing and is invisible.
        val probe = MouseInfo.getPointerInfo()?.location
        if (probe != null && !warp(probe.x.toDouble(), probe.y.toDouble())) {
            running.set(false)
            return false
        }

        val thread =
            Thread({ loop() }, "boss-pointer-confiner").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        worker = thread
        thread.start()
        return true
    }

    /** Stop confining. Idempotent - every teardown path calls it and several can fire for one exit. */
    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
        bounds = null
    }

    private fun loop() {
        try {
            while (running.get()) {
                val area = bounds ?: break
                val at = MouseInfo.getPointerInfo()?.location
                if (at != null && !area.contains(at)) {
                    val target = clampIntoBounds(at, area)
                    warp(target.x.toDouble(), target.y.toDouble())
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            // Never let this thread die silently while the session still believes the pointer is
            // held: the mode would go on claiming a confinement that stopped happening.
            logger.warn(LogCategory.SYSTEM, "Pointer confinement loop stopped", error = e)
            running.set(false)
            CapturedFullScreenState.setPointerConfined(false)
        }
    }

    private companion object {
        /**
         * 8ms, i.e. faster than a 120Hz display refreshes.
         *
         * The pointer is visibly outside the area for at most one tick, so the effect reads as a
         * hard edge rather than as a cursor that escapes and is dragged back. Slower than this and
         * a fast flick reaches the next monitor before the warp lands.
         */
        const val POLL_INTERVAL_MS = 8L
    }
}

/**
 * The nearest point inside [area] to [at].
 *
 * Pure and separate from the loop so the edge case can be pinned: [java.awt.Rectangle.contains] is
 * **exclusive** at the far edge, so clamping to `x + width` returns a point the very next tick
 * considers outside and warps again - a cursor that judders in the corner for as long as the mode
 * is on, with no error anywhere. The last pixel inside is `x + width - 1`.
 */
internal fun clampIntoBounds(
    at: Point,
    area: Rectangle,
): Point =
    Point(
        min(max(at.x, area.x), area.x + area.width - 1),
        min(max(at.y, area.y), area.y + area.height - 1),
    )
