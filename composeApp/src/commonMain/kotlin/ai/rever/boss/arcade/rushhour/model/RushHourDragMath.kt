@file:Suppress("LongParameterList")

package ai.rever.boss.arcade.rushhour.model

import kotlin.math.abs

/**
 * Pure 1D drag & snap physics calculator for Rush Hour vehicles.
 * Enforces strict axis isolation, dynamic boundary clamping, and snap-to-grid thresholds.
 */
object RushHourDragMath {
    /**
     * Minimum drag fraction required to commit a move to the next grid cell.
     * Moving less than 40% will snap back smoothly to origin.
     */
    const val COMMIT_THRESHOLD_FRACTION = 0.40f

    /**
     * Calculates the clamped 1D visual translation offset from raw 2D pointer drag gestures.
     *
     * @param totalDragX Cumulative X drag distance in pixels.
     * @param totalDragY Cumulative Y drag distance in pixels.
     * @param isHorizontal Whether the vehicle is horizontally oriented.
     * @param cellSizePx Size of one grid cell in pixels.
     * @param minSteps Maximum backward steps allowed (<= 0).
     * @param maxSteps Maximum forward steps allowed (>= 0).
     * @return 1D offset in pixels along the active axis, strictly clamped to legal bounds.
     */
    fun calculateConstrainedOffset(
        totalDragX: Float,
        totalDragY: Float,
        isHorizontal: Boolean,
        cellSizePx: Float,
        minSteps: Int,
        maxSteps: Int,
    ): Float {
        val rawOffset = if (isHorizontal) totalDragX else totalDragY
        val minOffsetPx = minSteps * cellSizePx
        val maxOffsetPx = maxSteps * cellSizePx

        return rawOffset.coerceIn(minOffsetPx, maxOffsetPx)
    }

    /**
     * Evaluates whether a release offset should commit a step or snap back to 0.
     *
     * @param clampedOffset 1D offset in pixels along the active axis.
     * @param cellSizePx Size of one grid cell in pixels.
     * @param thresholdFraction Fraction of cell dimension required to commit (default 40%).
     * @param minSteps Maximum backward steps allowed (<= 0).
     * @param maxSteps Maximum forward steps allowed (>= 0).
     * @return Integer step count to execute (-N..+N), or 0 to bounce back.
     */
    fun computeSnapStep(
        clampedOffset: Float,
        cellSizePx: Float,
        thresholdFraction: Float = COMMIT_THRESHOLD_FRACTION,
        minSteps: Int,
        maxSteps: Int,
    ): Int {
        if (cellSizePx <= 0f || minSteps > 0 || maxSteps < 0) return 0
        val thresholdPx = cellSizePx * thresholdFraction

        return when {
            clampedOffset >= thresholdPx && maxSteps > 0 -> {
                // Determine how many cells forward
                val steps = ((clampedOffset + cellSizePx * (1f - thresholdFraction)) / cellSizePx).toInt()
                steps.coerceIn(1, maxSteps)
            }

            clampedOffset <= -thresholdPx && minSteps < 0 -> {
                // Determine how many cells backward
                val steps = ((clampedOffset - cellSizePx * (1f - thresholdFraction)) / cellSizePx).toInt()
                steps.coerceIn(minSteps, -1)
            }

            else -> {
                0
            } // Snap back to 0
        }
    }

    /**
     * Calculates the real-time projected target step count from the current drag offset.
     *
     * @param clampedOffset Current 1D drag offset in pixels along the active axis.
     * @param cellSizePx Grid cell dimension in pixels.
     * @param minSteps Maximum backward steps allowed (<= 0).
     * @param maxSteps Maximum forward steps allowed (>= 0).
     * @return Integer step offset (-N..+N) representing the projected drop target, or 0 if within the starting cell.
     */
    fun computeProjectedStep(
        clampedOffset: Float,
        cellSizePx: Float,
        minSteps: Int,
        maxSteps: Int,
    ): Int = computeSnapStep(clampedOffset, cellSizePx, minSteps = minSteps, maxSteps = maxSteps)

    /**
     * Computes the projected grid coordinate (row, col) of a vehicle given a projected step.
     */
    fun computeProjectedCoordinates(
        vehicle: RushHourVehicle,
        projectedStep: Int,
    ): Pair<Int, Int> {
        val targetRow = if (vehicle.isHorizontal) vehicle.row else vehicle.row + projectedStep
        val targetCol = if (vehicle.isHorizontal) vehicle.col + projectedStep else vehicle.col
        return targetRow to targetCol
    }

    /**
     * Checks if perpendicular drag distance exceeds active axis movement.
     * Used for diagnosing user gesture intent.
     */
    fun isPerpendicularDrag(
        dragX: Float,
        dragY: Float,
        isHorizontal: Boolean,
    ): Boolean =
        if (isHorizontal) {
            abs(dragY) > abs(dragX) * 1.5f && abs(dragY) > 5f
        } else {
            abs(dragX) > abs(dragY) * 1.5f && abs(dragX) > 5f
        }
}
