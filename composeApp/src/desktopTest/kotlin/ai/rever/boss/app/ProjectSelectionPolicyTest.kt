package ai.rever.boss.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule that keeps startup quiet.
 *
 * Without it, a Last Session restore looks exactly like the user opening a project: the
 * restore calls `selectProject` with the path its layout recorded, and the effect watching
 * `selectedProject.path` cannot see where the call came from. That already re-applied the
 * default workspace over the freshly restored layout on every launch; with the default now
 * "ask" it would have put a dialog on screen at every launch instead.
 */
class ProjectSelectionPolicyTest {
    @Test
    fun `opening a project is a user selection`() {
        assertTrue(isUserProjectSelection("/work/boss", restoredProjectPath = null))
    }

    @Test
    fun `the project startup restored is not`() {
        assertFalse(isUserProjectSelection("/work/boss", restoredProjectPath = "/work/boss"))
    }

    /** A restore of one project must not silence opening a different one in the same window. */
    @Test
    fun `a different project is still a user selection`() {
        assertTrue(isUserProjectSelection("/work/other", restoredProjectPath = "/work/boss"))
    }

    /**
     * "No project" is the starting value of every window's project state, so the effect sees
     * it on first composition. It is not a selection and must not raise anything.
     */
    @Test
    fun `no project is not a selection`() {
        assertFalse(isUserProjectSelection("", restoredProjectPath = null))
        assertFalse(isUserProjectSelection("", restoredProjectPath = "/work/boss"))
    }

    /**
     * A new window can arrive with a project that a restore records too, so both marks hold the
     * same path. Consuming only the first left the other behind to swallow the next real open.
     */
    @Test
    fun `a selection both marks account for clears both`() {
        val gate = gateProjectSelection("/work/boss", "/work/boss", "/work/boss")

        assertFalse(gate.handle)
        assertNull(gate.restoredProjectPath)
        assertNull(gate.answeredProjectPath)
        assertTrue(gateProjectSelection("/work/boss", gate.restoredProjectPath, gate.answeredProjectPath).handle)
    }

    @Test
    fun `an answered open is skipped once, and a mark for another project is kept`() {
        val answered = gateProjectSelection("/work/boss", null, "/work/boss")
        assertFalse(answered.handle)
        assertNull(answered.answeredProjectPath)

        val other = gateProjectSelection("/work/other", null, "/work/boss")
        assertTrue(other.handle)
        assertEquals("/work/boss", other.answeredProjectPath)
    }
}
