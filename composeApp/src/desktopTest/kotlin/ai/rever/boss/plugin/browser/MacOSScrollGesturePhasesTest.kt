package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacOSScrollGesturePhasesTest {
    @Test
    fun `sdk phase values begin change and end one gesture`() {
        var state = ScrollGestureSnapshot(0, false)
        state = scrollGestureTransition(state, phase = 128, momentumPhase = 0).first
        val afterBegan = scrollGestureTransition(state, phase = 1, momentumPhase = 0).first
        assertEquals(1, afterBegan.id, "MayBegin followed by Began must retain one id")
        state = scrollGestureTransition(afterBegan, phase = 2, momentumPhase = 0, dx = -100.0).first
        val (ended, signal) = scrollGestureTransition(state, phase = 4, momentumPhase = 0)
        assertFalse(ended.active)
        assertEquals(ScrollGestureEnd(1, false, -100.0, 0.0, false, false, emptySet()), signal)
    }

    @Test
    fun `sdk cancelled value resets without looking like release`() {
        val active = scrollGestureTransition(ScrollGestureSnapshot(4, false), phase = 1, momentumPhase = 0).first
        val (_, signal) = scrollGestureTransition(active, phase = 8, momentumPhase = 0)
        assertEquals(true, signal?.cancelled)
    }

    @Test
    fun `changed cannot resurrect an inactive gesture and momentum is unobservable`() {
        val idle = ScrollGestureSnapshot(7, false)
        val changed = scrollGestureTransition(idle, phase = 2, momentumPhase = 0)
        assertEquals(idle, changed.first)
        assertNull(changed.second)

        val active = scrollGestureTransition(idle, phase = 1, momentumPhase = 0).first
        val momentum = scrollGestureTransition(active, phase = 0, momentumPhase = 1).first
        assertFalse(momentum.active)
        assertFalse(momentum.observable)
        assertEquals(true, scrollGestureTransition(active, phase = 0, momentumPhase = 1).second?.cancelled)
    }

    @Test
    fun `ended wins when momentum begins on the same event`() {
        val active = scrollGestureTransition(ScrollGestureSnapshot(0, false), phase = 1, momentumPhase = 0).first
        val (ended, signal) = scrollGestureTransition(active, phase = 4, momentumPhase = 1, dx = -500.0)
        assertEquals(false, signal?.cancelled)
        assertEquals(0.0, signal?.accumX, "momentum on the terminal event is not finger travel")
        assertFalse(ended.observable, "a new document must not claim the ended id")
    }

    @Test
    fun `cancelled outranks ended when both terminal bits appear`() {
        val active = scrollGestureTransition(ScrollGestureSnapshot(0, false), phase = 1, momentumPhase = 0).first
        assertEquals(true, scrollGestureTransition(active, phase = 4 or 8, momentumPhase = 0).second?.cancelled)
    }

    @Test
    fun `native final displacement records easing and reversal`() {
        var state = scrollGestureTransition(ScrollGestureSnapshot(0, false), 1, 0, -110.0, 0.0).first
        state = scrollGestureTransition(state, 2, 0, 30.0, 0.0).first
        assertEquals(-80.0, state.accumX)
        assertFalse(state.rejected, "easing without crossing through zero stays cancellable at release")
        state = scrollGestureTransition(state, 2, 0, 100.0, 0.0).first
        assertTrue(state.rejected, "crossing through zero is a reversal")
    }

    @Test
    fun `multiple browser claimants cannot starve one another`() {
        val active = scrollGestureTransition(ScrollGestureSnapshot(0, false), phase = 1, momentumPhase = 0).first
        val first = claimScrollGesture(active, claimantId = 10)
        assertEquals(setOf(10L), first?.claimantIds)
        val both = claimScrollGesture(first!!, claimantId = 20)
        assertEquals(setOf(10L, 20L), both?.claimantIds)
        assertEquals(both, claimScrollGesture(both!!, claimantId = 10))
    }

    @Test
    fun `end is delivered to its claimant and no claimant means no target`() {
        val active = scrollGestureTransition(ScrollGestureSnapshot(0, false), phase = 1, momentumPhase = 0).first
        assertEquals(emptySet(), scrollGestureTransition(active, phase = 4, momentumPhase = 0).second?.claimantIds)

        val claimed = claimScrollGesture(active, claimantId = 17)!!
        assertEquals(setOf(17L), scrollGestureTransition(claimed, phase = 4, momentumPhase = 0).second?.claimantIds)
    }

    @Test
    fun `a claim racing after native end fails closed`() {
        val active = scrollGestureTransition(ScrollGestureSnapshot(0, false), phase = 1, momentumPhase = 0).first
        val ended = scrollGestureTransition(active, phase = 4, momentumPhase = 0).first
        assertNull(claimScrollGesture(ended, claimantId = 4))
    }
}
