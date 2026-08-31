package ai.rever.boss.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the captured-full-screen button's position against the traffic lights' **measured** frames.
 *
 * Two shipped versions of this constant were guesses and both were visibly wrong: 70dp (the light
 * box minus an arbitrary nudge), then 74dp from an assumed 20pt pitch. The second put the button
 * 18pt from the zoom button where macOS spaces its own 23pt apart, which reads as crowded rather
 * than as a fourth light, and was reported as such.
 *
 * These are the real frames, read back from AppKit through the accessibility API on a live BOSS
 * window (`System Events` -> `buttons of window 1`), with the window origin at (0, 33):
 *
 * ```
 * close     position {8, 41}   size {16, 16}
 * minimise  position {31, 41}  size {16, 16}
 * zoom      position {54, 41}  size {16, 16}
 * ```
 *
 * The test derives the constants from those numbers rather than restating them, so a future change
 * has to argue with the measurement instead of with a magic number.
 */
class CapturedButtonGeometryTest {
    /** Accessibility frames, as measured. Window origin y is 33. */
    private val windowOriginY = 33
    private val closeFrame = Frame(x = 8, y = 41, size = 16)
    private val minimiseFrame = Frame(x = 31, y = 41, size = 16)
    private val zoomFrame = Frame(x = 54, y = 41, size = 16)

    /**
     * The DRAWN circle, measured from a 2x screen capture scanned along the centre line: the three
     * lights are 14pt across. Smaller than their 16pt accessibility frame, and larger than the 12pt
     * the button first used - which is what made it visibly the wrong size beside them.
     */
    private val visibleDiameter = 14

    private data class Frame(
        val x: Int,
        val y: Int,
        val size: Int,
    ) {
        val centreX get() = x + size / 2.0
    }

    private val pitch get() = minimiseFrame.centreX - closeFrame.centreX

    @Test
    fun `the lights are evenly spaced, which is what makes a fourth position meaningful`() {
        assertEquals(
            minimiseFrame.centreX - closeFrame.centreX,
            zoomFrame.centreX - minimiseFrame.centreX,
            "The measured cluster is not evenly spaced, so the pitch below is not a pitch",
        )
    }

    @Test
    fun `the pitch is 23pt, not the 20 that was assumed`() {
        assertEquals(23.0, pitch, "This is the number both wrong versions of the constant got wrong")
        assertEquals(TRAFFIC_LIGHT_PITCH, pitch.dp)
        assertEquals(TRAFFIC_LIGHT_FIRST_CENTRE, closeFrame.centreX.dp)
    }

    @Test
    fun `the drawn circle matches the lights, not their accessibility frame`() {
        // The button drew a 12pt circle next to 14pt lights and read as the wrong size. The frame
        // is 16, so neither the frame nor a guess would have produced the right answer.
        assertEquals(visibleDiameter.dp, TRAFFIC_LIGHT_DIAMETER)
        assertEquals(16, closeFrame.size, "the accessibility frame, which is NOT what is drawn")
    }

    @Test
    fun `the button starts where a fourth light on the same pitch would`() {
        val fourthCentre = zoomFrame.centreX + pitch
        assertEquals(85.0, fourthCentre)
        assertEquals(
            (fourthCentre - visibleDiameter / 2.0).dp,
            CAPTURED_BUTTON_START,
            "CAPTURED_BUTTON_START must put a ${visibleDiameter}dp circle centred at $fourthCentre",
        )
    }

    @Test
    fun `the button sits at the lights' height, not the title row's middle`() {
        val centreFromWindowTop = (closeFrame.y - windowOriginY) + closeFrame.size / 2.0
        assertEquals(16.0, centreFromWindowTop)
        assertEquals(
            (centreFromWindowTop - visibleDiameter / 2.0).dp,
            CAPTURED_BUTTON_TOP,
            "Centring in the 26dp title row instead would put it at 13, three points high",
        )
    }

    @Test
    fun `chrome after the cluster clears the fourth button plus the same air the box allows`() {
        // TRAFFIC_LIGHT_WIDTH is 78 against a zoom frame ending at 70, i.e. 8pt of trailing air.
        val trailingAir = TRAFFIC_LIGHT_WIDTH.value - (zoomFrame.x + zoomFrame.size)
        assertEquals(8f, trailingAir)

        val fourthRightEdge = zoomFrame.centreX + pitch + visibleDiameter / 2.0
        assertEquals(
            (fourthRightEdge + trailingAir).dp,
            TRAFFIC_LIGHT_WIDTH_WITH_BUTTON,
        )
    }

    @Test
    fun `the button clears the zoom button rather than overlapping it`() {
        val zoomVisibleRight = zoomFrame.centreX + visibleDiameter / 2.0
        val buttonLeft = CAPTURED_BUTTON_START.value.toDouble()
        val gap = buttonLeft - zoomVisibleRight
        assertEquals(
            pitch - visibleDiameter,
            gap,
            "The gap between the blue button and zoom must equal the gap between the OS's own",
        )
    }
}
