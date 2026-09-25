package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.PinchZoomAccumulator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pieces that let a canvas app claim a trackpad pinch instead of BOSS zooming the page
 * (#1565): the wheel event the page is offered, where it is aimed, and how declined deltas turn
 * into page-zoom steps.
 */
class BrowserPinchTest {
    // --- wheelDeltaY: the event canvas apps read ---

    @Test
    fun `pinching out is a negative deltaY, the same direction as Ctrl+scroll-up`() {
        // Excalidraw, Figma and tldraw all zoom in on a negative deltaY with ctrlKey set. A flipped
        // sign here zooms every canvas the wrong way, with nothing failing anywhere.
        assertTrue(BrowserPinchScript.wheelDeltaY(0.02) < 0)
        assertTrue(BrowserPinchScript.wheelDeltaY(-0.02) > 0)
        assertEquals(0.0, BrowserPinchScript.wheelDeltaY(0.0), 1e-12)
    }

    @Test
    fun `deltaY follows Chromium's -100 ln(scale) mapping`() {
        assertEquals(-100.0 * ln(1.05), BrowserPinchScript.wheelDeltaY(0.05), 1e-9)
    }

    @Test
    fun `the delta is spliced into the script as a plain decimal`() {
        // Pinned against a literal, not against wheelDeltaY itself, so a formatting change (an
        // exponent, a locale comma) shows up here instead of as a page that silently misreads it.
        assertTrue("deltaY: -4.8790164169432" in BrowserPinchScript.dispatch(0.05, 0.5, 0.5))
    }

    @Test
    fun `non-finite input yields no movement aimed at the middle, never NaN in the script`() {
        // coerceIn and coerceAtLeast pass NaN through, and NaN is valid JS, so nothing would fail
        // loudly: the page would get an event with a NaN delta.
        assertEquals(0.0, BrowserPinchScript.wheelDeltaY(Double.NaN))
        assertEquals(0.0, BrowserPinchScript.wheelDeltaY(Double.POSITIVE_INFINITY))
        val script = BrowserPinchScript.dispatch(Double.NaN, Double.NaN, Double.NEGATIVE_INFINITY)
        assertFalse("NaN" in script)
        assertFalse("Infinity" in script)
        assertTrue("win.innerWidth * 0.5" in script)
        assertTrue("win.innerHeight * 0.5" in script)
    }

    @Test
    fun `the aim is kept inside the last point elementFromPoint can hit`() {
        // innerWidth * 1.0 is one past the last addressable pixel and counts a classic scrollbar,
        // where elementFromPoint returns null and the aim is lost exactly at the clamped edge.
        val script = BrowserPinchScript.dispatch(0.05, 1.0, 1.0)
        assertTrue("root0.clientWidth - 1" in script)
        assertTrue("root0.clientHeight - 1" in script)
    }

    @Test
    fun `a magnification at or below -1 still yields a finite delta`() {
        // ln(0) is -Infinity, and "Infinity" or "NaN" spliced into the script would make it throw,
        // silently declining the pinch.
        assertTrue(BrowserPinchScript.wheelDeltaY(-1.0).isFinite())
        assertTrue(BrowserPinchScript.wheelDeltaY(-5.0).isFinite())
    }

    // --- dispatch: the script itself ---

    @Test
    fun `the offered event is a cancelable Ctrl+wheel and the script reports whether it was claimed`() {
        val script = BrowserPinchScript.dispatch(0.05, 0.25, 0.75)
        assertTrue("WheelEvent('wheel'" in script)
        assertTrue("ctrlKey: true" in script)
        // Without cancelable, preventDefault is a no-op and every pinch would read as declined.
        assertTrue("cancelable: true" in script)
        assertTrue("return !target.dispatchEvent(event);" in script)
        assertTrue("deltaY: ${BrowserPinchScript.wheelDeltaY(0.05)}," in script)
    }

    @Test
    fun `the event is aimed at the pointer's fraction of the viewport`() {
        val script = BrowserPinchScript.dispatch(0.05, 0.25, 0.75)
        assertTrue("win.innerWidth * 0.25" in script)
        assertTrue("win.innerHeight * 0.75" in script)
    }

    @Test
    fun `an unknown pointer aims at the middle, and an out-of-range one is clamped to the viewport`() {
        val unknown = BrowserPinchScript.dispatch(0.05, null, null)
        assertTrue("win.innerWidth * 0.5" in unknown)
        assertTrue("win.innerHeight * 0.5" in unknown)

        val outside = BrowserPinchScript.dispatch(0.05, -0.2, 1.4)
        assertTrue("win.innerWidth * 0.0" in outside)
        assertTrue("win.innerHeight * 1.0" in outside)
    }

    @Test
    fun `the target search looks into shadow roots and same-origin iframes`() {
        // A dispatch at a shadow host or an iframe element never reaches a canvas inside it, so
        // an embedded canvas app would lose the pinch to page zoom, which is #1565 again.
        val script = BrowserPinchScript.dispatch(0.05, 0.5, 0.5)
        assertTrue("root.elementFromPoint(x, y)" in script)
        assertTrue("childDoc.elementFromPoint(cx, cy)" in script)
        // Built from the child window's own constructor, so it belongs to the document it is
        // dispatched in.
        assertTrue("new win.WheelEvent('wheel'" in script)
    }

    // --- pointerFractionInBounds: the coordinate-space trap, again ---

    @Test
    fun `the fraction reconciles logical pointer units with pixel bounds`() {
        // At density 2 a view at px (0,100)-(2000,1300) is logical (0,50)-(1000,650). A pointer at
        // logical (250,350) is a quarter across and half way down. Compared unscaled it would read
        // as far outside the top-left quarter.
        val fraction =
            pointerFractionInBounds(
                boundsPx = Rect(0f, 100f, 2000f, 1300f),
                pointerLogical = Offset(250f, 350f),
                density = 2f,
            )
        assertEquals(Offset(0.25f, 0.5f), fraction)
    }

    @Test
    fun `a pointer outside the rect gives a fraction outside 0 to 1, left for the caller to clamp`() {
        val fraction = pointerFractionInBounds(Rect(0f, 0f, 100f, 100f), Offset(150f, -50f), 1f)
        assertEquals(Offset(1.5f, -0.5f), fraction)
    }

    @Test
    fun `an empty rect has no fraction`() {
        assertNull(pointerFractionInBounds(Rect(10f, 10f, 10f, 400f), Offset(10f, 20f), 1f))
    }

    // --- PinchZoomAccumulator: declined deltas become page-zoom steps ---

    @Test
    fun `deltas accumulate into one step at the threshold, then start over`() {
        val accumulator = PinchZoomAccumulator(threshold = 0.15)
        assertNull(accumulator.add(0.1))
        assertEquals(PinchZoomAccumulator.Step.IN, accumulator.add(0.06))
        assertNull(accumulator.add(0.1))
    }

    @Test
    fun `pinching in steps out`() {
        val accumulator = PinchZoomAccumulator(threshold = 0.15)
        assertNull(accumulator.add(-0.1))
        assertEquals(PinchZoomAccumulator.Step.OUT, accumulator.add(-0.06))
    }

    @Test
    fun `a claimed pinch leaves no remainder to tip a later delta into a page zoom`() {
        val accumulator = PinchZoomAccumulator(threshold = 0.15)
        accumulator.add(0.14)
        accumulator.reset()
        assertNull(accumulator.add(0.02))
    }

    // --- PinchOffers: a busy page cannot stall or replay a pinch ---

    @Test
    fun `the page's answer is passed on`() {
        val offers = PinchOffers(maxPending = 8, deadlineMs = 5_000)
        val answers = mutableListOf<PinchAnswer>()
        offers.offer(send = { answer, _ -> answer(true) }, onAnswer = { answers += it })
        offers.offer(send = { answer, _ -> answer(false) }, onAnswer = { answers += it })
        assertEquals(listOf(PinchAnswer.CLAIMED, PinchAnswer.DECLINED), answers)
    }

    @Test
    fun `a page that never answers gets no answer at the deadline, not a decline, and a late answer is dropped`() {
        val offers = PinchOffers(maxPending = 8, deadlineMs = 20)
        var late: ((Boolean) -> Unit)? = null
        val calls = AtomicInteger(0)
        val declined = CountDownLatch(1)
        offers.offer(send = { answer, _ -> late = answer }, onAnswer = { claimed ->
            calls.incrementAndGet()
            // TIMED_OUT, not DECLINED: a timeout mid-gesture is expected while a canvas app is busy
            // zooming, and reading it as a decline would stack page zoom on the canvas zoom.
            if (claimed == PinchAnswer.TIMED_OUT) declined.countDown()
        })
        assertTrue(declined.await(2, TimeUnit.SECONDS))
        late?.invoke(true)
        assertEquals(1, calls.get())
    }

    @Test
    fun `once the cap is waiting, a new delta skips the page and gets no answer at once`() {
        val offers = PinchOffers(maxPending = 2, deadlineMs = 5_000)
        val sent = AtomicInteger(0)
        repeat(2) { offers.offer(send = { _, _ -> sent.incrementAndGet() }, onAnswer = {}) }
        val answers = mutableListOf<PinchAnswer>()
        offers.offer(send = { _, _ -> sent.incrementAndGet() }, onAnswer = { answers += it })
        assertEquals(2, sent.get())
        assertEquals(listOf(PinchAnswer.SKIPPED), answers)
    }

    @Test
    fun `an answered offer gives its slot back`() {
        // A leaked slot would leave every later pinch skipping the page, with the cap test above
        // still green.
        val offers = PinchOffers(maxPending = 1, deadlineMs = 5_000)
        val answers = mutableListOf<PinchAnswer>()
        repeat(3) { offers.offer(send = { answer, _ -> answer(true) }, onAnswer = { answers += it }) }
        assertEquals(List(3) { PinchAnswer.CLAIMED }, answers)
    }

    @Test
    fun `an offer answered by its deadline reads as stale to a queued send`() {
        // A send that backed up behind a stalled renderer checks this and drops the offer
        // instead of replaying an old wheel event into the page after the gesture ended.
        // Half a second, so the not-yet-stale read cannot lose a race on a loaded CI machine.
        val offers = PinchOffers(maxPending = 8, deadlineMs = 500)
        var stale: (() -> Boolean)? = null
        val answered = CountDownLatch(1)
        offers.offer(send = { _, isStale -> stale = isStale }, onAnswer = { answered.countDown() })
        assertFalse(stale!!())
        assertTrue(answered.await(2, TimeUnit.SECONDS))
        assertTrue(stale!!())
    }
}
