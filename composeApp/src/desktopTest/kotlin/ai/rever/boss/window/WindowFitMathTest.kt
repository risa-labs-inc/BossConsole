package ai.rever.boss.window

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [computeFitSize] — the px→dp scale, screen clamp, and min-fit
 * floor of "Fit host to my screen". This is the regression-prone math that used to
 * live inline in the BossWindow collector (and where the `coerceIn`-throws bug was).
 */
class WindowFitMathTest {
    private val current = DpSize(800.dp, 600.dp)
    private val noClamp = 100_000f

    @Test
    fun grow_at_1x_adds_delta_as_dp() {
        val r = computeFitSize(current, 200f, 100f, scaleX = 1f, scaleY = 1f, maxWidthDp = noClamp, maxHeightDp = noClamp)
        assertEquals(DpSize(1000.dp, 700.dp), r)
    }

    @Test
    fun scale_2x_halves_the_px_delta() {
        // A physical-px delta on a 2× display is half as many dp.
        val r = computeFitSize(current, 200f, 100f, scaleX = 2f, scaleY = 2f, maxWidthDp = noClamp, maxHeightDp = noClamp)
        assertEquals(DpSize(900.dp, 650.dp), r)
    }

    @Test
    fun negative_delta_shrinks() {
        val r = computeFitSize(current, -200f, -100f, scaleX = 1f, scaleY = 1f, maxWidthDp = noClamp, maxHeightDp = noClamp)
        assertEquals(DpSize(600.dp, 500.dp), r)
    }

    @Test
    fun clamps_to_usable_screen() {
        val r = computeFitSize(current, 5000f, 5000f, scaleX = 1f, scaleY = 1f, maxWidthDp = 1000f, maxHeightDp = 700f)
        assertEquals(DpSize(1000.dp, 700.dp), r)
    }

    @Test
    fun floors_at_min_fit_size() {
        val r = computeFitSize(current, -1000f, -1000f, scaleX = 1f, scaleY = 1f, maxWidthDp = noClamp, maxHeightDp = noClamp)
        assertEquals(DpSize(MIN_FIT_WIDTH_DP.dp, MIN_FIT_HEIGHT_DP.dp), r)
    }

    @Test
    fun usable_below_floor_yields_screen_not_a_throw() {
        // Range would invert (floor > screen) — must not throw, must cap at screen.
        val r = computeFitSize(current, 0f, 0f, scaleX = 1f, scaleY = 1f, maxWidthDp = 300f, maxHeightDp = 200f)
        assertEquals(DpSize(300.dp, 200.dp), r)
    }

    @Test
    fun non_positive_scale_is_treated_as_1x() {
        val r = computeFitSize(current, 200f, 100f, scaleX = 0f, scaleY = -1f, maxWidthDp = noClamp, maxHeightDp = noClamp)
        assertEquals(DpSize(1000.dp, 700.dp), r)
    }
}
