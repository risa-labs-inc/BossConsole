package ai.rever.boss.sharing

import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Reduce capture work before sacrificing motion; recover resolution only after sustained headroom. */
internal class AppCaptureQuality {
    private val dimensions = intArrayOf(1920, 1600, 1280, 960)
    private var tier = 0
    private var slowFrames = 0
    private var healthyFrames = 0

    val maxDimension: Int get() = dimensions[tier]
    val frameIntervalNanos: Long = TimeUnit.SECONDS.toNanos(1) / TARGET_FPS

    fun recordCapture(durationNanos: Long) {
        require(durationNanos >= 0)
        if (durationNanos > frameIntervalNanos) {
            healthyFrames = 0
            slowFrames++
            if (slowFrames >= SLOW_FRAMES && tier < dimensions.lastIndex) {
                tier++
                slowFrames = 0
            }
        } else {
            slowFrames = 0
            healthyFrames = if (durationNanos < frameIntervalNanos * RECOVERY_HEADROOM) healthyFrames + 1 else 0
            if (healthyFrames >= HEALTHY_FRAMES && tier > 0) {
                tier--
                healthyFrames = 0
            }
        }
    }

    private companion object {
        const val TARGET_FPS = 30
        const val SLOW_FRAMES = 10
        const val HEALTHY_FRAMES = 150
        const val RECOVERY_HEADROOM = 0.6
    }
}

internal data class AppCaptureFrameSize(
    val width: Int,
    val height: Int,
)

/** Owned windows may have different display densities; composition uses one logical coordinate space. */
internal fun appCaptureSurfaceFrameSize(
    geometry: WindowCaptureGeometry,
    groupLogicalWidth: Int,
    frameWidth: Int,
): AppCaptureFrameSize {
    require(groupLogicalWidth > 0 && frameWidth > 0)
    val scale = frameWidth.toDouble() / groupLogicalWidth
    return AppCaptureFrameSize(
        (geometry.logicalWidth * scale).roundToInt().coerceAtLeast(1),
        (geometry.logicalHeight * scale).roundToInt().coerceAtLeast(1),
    )
}

/** Downscale the entire window group uniformly; input continues to use its original logical bounds. */
internal fun appCaptureFrameSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): AppCaptureFrameSize {
    require(width > 0 && height > 0 && maxDimension > 0)
    val scale = minOf(1.0, maxDimension.toDouble() / maxOf(width, height))
    return AppCaptureFrameSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}
