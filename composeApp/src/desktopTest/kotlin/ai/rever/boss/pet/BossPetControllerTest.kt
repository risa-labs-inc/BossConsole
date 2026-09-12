package ai.rever.boss.pet

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins every transition of [BossPetController], the state machine the floating pet renders.
 *
 * The controller is deliberately free of Compose and timers so these can drive it by calls alone.
 * The cases that matter most are the ones that would otherwise strand the pet: a double-finish that
 * must not drive the count negative, a failure that must survive the idle timer, and a dismiss that
 * must return to Working rather than Idle while work is still in flight.
 */
class BossPetControllerTest {
    private fun controller() = BossPetController()

    @Test
    fun `starts idle`() {
        assertEquals(BossPetMood.Idle, controller().mood.value)
    }

    @Test
    fun `one task started shows working with a count of one`() {
        val c = controller()
        c.taskStarted("a")
        assertEquals(BossPetMood.Working(1), c.mood.value)
    }

    @Test
    fun `concurrent tasks are counted`() {
        val c = controller()
        c.taskStarted("a")
        c.taskStarted("b")
        assertEquals(BossPetMood.Working(2), c.mood.value)
    }

    @Test
    fun `starting the same id twice does not double-count`() {
        val c = controller()
        c.taskStarted("a")
        c.taskStarted("a")
        assertEquals(BossPetMood.Working(1), c.mood.value)
    }

    @Test
    fun `finishing the only task announces completion`() {
        val c = controller()
        c.taskStarted("a")
        c.taskFinished("a", "Build finished")
        assertEquals(BossPetMood.Completed("Build finished", "a"), c.mood.value)
    }

    @Test
    fun `finishing one of several keeps announcing but work remains underneath`() {
        val c = controller()
        c.taskStarted("a")
        c.taskStarted("b")
        c.taskFinished("a", "Agent finished")
        assertEquals(BossPetMood.Completed("Agent finished", "a"), c.mood.value)
        // Dismissing the announcement must fall back to the still-running task, not to idle.
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Working(1), c.mood.value)
    }

    @Test
    fun `finishing an id that was never started still announces and never goes negative`() {
        val c = controller()
        c.taskFinished("ghost", "Done")
        assertEquals(BossPetMood.Completed("Done", "ghost"), c.mood.value)
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Idle, c.mood.value)
    }

    @Test
    fun `finishing the same id twice does not strand the pet in working`() {
        val c = controller()
        c.taskStarted("a")
        c.taskFinished("a", "Done")
        c.taskFinished("a", "Done")
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Idle, c.mood.value)
    }

    @Test
    fun `a failure is announced`() {
        val c = controller()
        c.taskStarted("a")
        c.taskFailed("a", "Build failed")
        assertEquals(BossPetMood.Failed("Build failed", "a"), c.mood.value)
    }

    @Test
    fun `the idle timeout clears a completion but never a failure`() {
        val c = controller()
        c.taskStarted("a")
        c.taskFinished("a", "Done")
        c.onIdleTimeout()
        assertEquals(BossPetMood.Idle, c.mood.value)

        val f = controller()
        f.taskStarted("b")
        f.taskFailed("b", "Boom")
        f.onIdleTimeout()
        assertEquals(BossPetMood.Failed("Boom", "b"), f.mood.value)
    }

    @Test
    fun `the idle timeout leaves live work alone`() {
        val c = controller()
        c.taskStarted("a")
        c.onIdleTimeout()
        assertEquals(BossPetMood.Working(1), c.mood.value)
    }

    @Test
    fun `the idle timeout returns to other live work`() {
        val c = controller()
        c.taskStarted("a")
        c.taskStarted("b")
        c.taskFinished("a", "One done")
        c.onIdleTimeout()
        assertEquals(BossPetMood.Working(1), c.mood.value)
    }

    @Test
    fun `dismiss is a no-op while idle or working`() {
        val c = controller()
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Idle, c.mood.value)
        c.taskStarted("a")
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Working(1), c.mood.value)
    }

    @Test
    fun `new work preserves a standing announcement`() {
        val c = controller()
        c.taskStarted("a")
        c.taskFinished("a", "Done")
        assertEquals(BossPetMood.Completed("Done", "a"), c.mood.value)
        c.taskStarted("b")
        assertEquals(BossPetMood.Completed("Done", "a"), c.mood.value)
    }

    @Test
    fun `new activity and completion cannot erase an unacknowledged failure`() {
        val c = controller()
        c.taskFailed("a", "Build failed")
        c.taskStarted("b")
        c.taskFinished("b", "Agent finished")
        c.onIdleTimeout()
        assertEquals(BossPetMood.Failed("Build failed", "a"), c.mood.value)
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Completed("Agent finished", "b", 1), c.mood.value)
        c.onIdleTimeout()
        assertEquals(BossPetMood.Idle, c.mood.value)
    }

    @Test
    fun `identically labelled completions retain separate task identities`() {
        val c = controller()
        c.taskFinished("a", "Done")
        c.taskFinished("b", "Done")
        assertEquals(BossPetMood.Completed("Done", "a"), c.mood.value)
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Completed("Done", "b", 1), c.mood.value)
    }

    @Test
    fun `stopping one task preserves other activity and failures`() {
        val c = controller()
        c.taskStarted("a")
        c.taskStarted("b")
        c.taskFailed("c", "Failure")
        c.taskStopped("a")
        assertEquals(BossPetMood.Failed("Failure", "c"), c.mood.value)
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Working(1), c.mood.value)
    }

    @Test
    fun `a new run of the same task retains both results with distinct timer identities`() {
        val c = controller()
        c.taskStarted("a")
        c.taskFinished("a", "Done")
        c.taskStarted("a")
        c.taskFinished("a", "Done")
        assertEquals(BossPetMood.Completed("Done", "a"), c.mood.value)
        c.onIdleTimeout()
        assertEquals(BossPetMood.Completed("Done", "a", 1), c.mood.value)
        c.onIdleTimeout()
        assertEquals(BossPetMood.Idle, c.mood.value)
    }

    @Test
    fun `a stale timer cannot acknowledge the next task result`() {
        val c = controller()
        c.taskFinished("a", "Done")
        val firstNotice = c.mood.value
        c.taskFinished("b", "Done")
        c.dismissAnnouncement()
        c.onIdleTimeout(firstNotice)
        assertEquals(BossPetMood.Completed("Done", "b", 1), c.mood.value)
    }

    @Test
    fun `repeated failing checks need only one acknowledgement`() {
        val c = controller()
        repeat(500) {
            c.taskStarted("update")
            c.taskFailed("update", "Update failed")
        }
        assertEquals(BossPetMood.Failed("Update failed", "update", occurrences = 500), c.mood.value)
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Idle, c.mood.value)
    }

    @Test
    fun `a blocked queue has bounded detail and an explicit overflow count`() {
        val c = controller()
        repeat(500) { c.taskFailed("task-$it", "Failed $it") }
        repeat(127) { c.dismissAnnouncement() }
        assertEquals(
            BossPetMood.Failed("373 additional results - check BOSS", "pet-overflow", -1),
            c.mood.value,
        )
        c.dismissAnnouncement()
        assertEquals(BossPetMood.Idle, c.mood.value)
    }

    @Test
    fun `overflow keeps its chronological aggregate until acknowledged then restores detail`() {
        val c = controller()
        repeat(128) { c.taskFailed("task-$it", "Failed $it") }
        repeat(100) { c.dismissAnnouncement() }
        c.taskFailed("later", "Later failure")
        repeat(27) { c.dismissAnnouncement() }
        assertEquals(
            BossPetMood.Failed("2 additional results - check BOSS", "pet-overflow", -1),
            c.mood.value,
        )
        c.dismissAnnouncement()
        c.taskFailed("new", "New failure")
        assertEquals("New failure", (c.mood.value as BossPetMood.Failed).label)
    }
}
