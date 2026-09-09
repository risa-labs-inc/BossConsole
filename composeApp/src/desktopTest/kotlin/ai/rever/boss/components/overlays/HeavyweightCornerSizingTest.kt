package ai.rever.boss.components.overlays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the sizing half of [HeavyweightCorner], which the placement tests cannot reach.
 *
 * The bug this exists for is a one-way ratchet, and it is invisible to every other gate: measuring
 * content against the overlay's own current size means the window shrinks to fit what is showing,
 * the next toast is measured inside that smaller window and so measures CLIPPED, and the overlay can
 * never grow back. Nothing warns, placement stays correct, and the toast simply renders squashed.
 *
 * These assert on the REAL modifier chain rather than on reasoning about it, because that is the
 * only thing that distinguishes the two implementations.
 */
class HeavyweightCornerSizingTest {
    @get:Rule
    val rule = createComposeRule()

    private val ceiling = DpSize(432.dp, 600.dp)
    private val toast = DpSize(400.dp, 120.dp)

    /**
     * Lays [contentSize] out inside a window of [available], recording every size the observer
     * inside the constraint override reports. [available] is a state, so a test can shrink or grow
     * "the window" and watch what measurement does in response.
     */
    private fun harness(
        initialAvailable: DpSize,
        contentSize: DpSize,
        measurementCeiling: DpSize = ceiling,
    ): Harness {
        val seen = mutableListOf<IntSize>()
        var available by mutableStateOf(initialAvailable)
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            // The outer Box stands in for the overlay WINDOW: content is laid out inside whatever
            // size the window currently has, which is exactly the feedback path being tested.
            Box(modifier = Modifier.size(available.width, available.height)) {
                Box(
                    modifier =
                        Modifier
                            .measuredAgainst(measurementCeiling)
                            .onGloballyPositioned { seen += it.size },
                ) {
                    Box(modifier = Modifier.size(contentSize.width, contentSize.height))
                }
            }
        }
        rule.waitForIdle()
        return Harness(
            seen = seen,
            resize = { next ->
                available = next
                rule.waitForIdle()
            },
            density = density,
        )
    }

    private class Harness(
        val seen: List<IntSize>,
        val resize: (DpSize) -> Unit,
        val density: Float,
    )

    @Test
    fun `content taller than the first frame is measured in full within the parent region`() {
        val parentCeiling = regionCeiling(intArrayOf(0, 0, 1000, 800), ceiling)
        val h =
            harness(
                initialAvailable = ceiling,
                contentSize = DpSize(400.dp, 750.dp),
                measurementCeiling = parentCeiling,
            )
        assertTrue(h.seen.isNotEmpty())
        h.seen.forEach { size ->
            assertEquals((750 * h.density).toInt(), size.height)
        }
    }

    @Test
    fun `content is measured against the ceiling, not the window's current size`() {
        // The ratchet state: the window has already shrunk to 32x32, which is exactly what an empty
        // toast host measures - a Column with nothing in it, plus 16.dp padding on each side. A
        // toast then arrives.
        val h = harness(initialAvailable = DpSize(32.dp, 32.dp), contentSize = toast)

        assertTrue(h.seen.isNotEmpty(), "expected at least one measurement")
        // Assert on EVERY measurement, not only the last: a wrong first frame IS the bug, because
        // the window is resized from it.
        h.seen.forEach { size ->
            assertTrue(
                size.width > 32 && size.height > 32,
                "measured $size - clipped to the window instead of the ceiling, so the overlay " +
                    "would resize to the clipped size and never grow back",
            )
        }
    }

    @Test
    fun `measurement does not change when the window shrinks under it`() {
        val h = harness(initialAvailable = ceiling, contentSize = toast)
        val whenLarge = h.seen.last()

        // Shrink the window the way the real one shrinks after fitting itself to a toast.
        h.resize(DpSize(32.dp, 32.dp))

        // Independence from the current size is the whole property: it is what makes the two-pass
        // sizing converge instead of ratchet.
        assertEquals(whenLarge, h.seen.last())
    }

    @Test
    fun `content is still capped by the ceiling`() {
        // The flip side: the ceiling is a real bound, so the overlay never opens a window larger
        // than the region it is allowed to swallow clicks in.
        val h = harness(initialAvailable = ceiling, contentSize = DpSize(2000.dp, 2000.dp))

        val expected =
            IntSize(
                (ceiling.width.value * h.density).toInt(),
                (ceiling.height.value * h.density).toInt(),
            )
        assertEquals(expected, h.seen.last())
    }
}
