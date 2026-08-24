package ai.rever.boss.components.workspaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which workspaces are actually running.
 *
 * Two things make this more than "the one on screen", and the menu could report neither:
 *
 * - A single window runs several at once. Switching does not tear the old one down -
 *   `SplitViewState.preserveCurrentState` keeps its whole split tree, live tab components and
 *   all - so a workspace you switched away from is still going.
 * - `currentWorkspace` is one value on a manager every window shares, so with two windows on
 *   different workspaces it names whichever loaded last.
 */
class WindowWorkspacesTest {
    private fun manager() = WorkspaceManager()

    @Test
    fun `nothing is running to begin with`() {
        val m = manager()
        assertTrue(m.windowWorkspaces.value.isEmpty())
        assertTrue(m.liveWorkspaceIds.isEmpty())
    }

    @Test
    fun `one window runs several workspaces at once`() {
        // The case the whole feature exists for: switch away from a workspace and it keeps
        // running behind the one you switched to.
        val m = manager()
        m.setWindowWorkspaces("w1", setOf("design", "review", "inbox"))

        assertEquals(setOf("design", "review", "inbox"), m.liveWorkspaceIds)
    }

    @Test
    fun `two windows on different workspaces are both counted`() {
        val m = manager()
        m.setWindowWorkspaces("w1", setOf("design"))
        m.setWindowWorkspaces("w2", setOf("review"))

        assertEquals(setOf("design", "review"), m.liveWorkspaceIds)
    }

    @Test
    fun `a workspace running in two windows is listed once`() {
        val m = manager()
        m.setWindowWorkspaces("w1", setOf("design", "review"))
        m.setWindowWorkspaces("w2", setOf("design"))

        assertEquals(setOf("design", "review"), m.liveWorkspaceIds)
    }

    @Test
    fun `a window's set replaces its own, and only its own`() {
        // Keyed by window rather than accumulated, so a workspace a window has genuinely finished
        // with stops being reported - without disturbing what other windows are running.
        val m = manager()
        m.setWindowWorkspaces("w1", setOf("design", "review"))
        m.setWindowWorkspaces("w2", setOf("inbox"))
        m.setWindowWorkspaces("w1", setOf("review"))

        assertEquals(mapOf("w1" to setOf("review"), "w2" to setOf("inbox")), m.windowWorkspaces.value)
        assertEquals(setOf("review", "inbox"), m.liveWorkspaceIds)
    }

    @Test
    fun `a window running nothing is dropped rather than kept with an empty set`() {
        val m = manager()
        m.setWindowWorkspaces("w1", setOf("design"))
        m.setWindowWorkspaces("w1", emptySet())

        assertTrue(m.windowWorkspaces.value.isEmpty())
    }

    @Test
    fun `closing a window stops its workspaces being reported`() {
        // A stale claim is worse than no mark: it is a claim, and it is false.
        val m = manager()
        m.setWindowWorkspaces("w1", setOf("design", "review"))
        m.setWindowWorkspaces("w2", setOf("inbox"))
        m.releaseWindow("w1")

        assertEquals(setOf("inbox"), m.liveWorkspaceIds)
    }

    @Test
    fun `closing a window leaves a workspace another window still runs`() {
        val m = manager()
        m.setWindowWorkspaces("w1", setOf("design"))
        m.setWindowWorkspaces("w2", setOf("design"))
        m.releaseWindow("w1")

        assertEquals(setOf("design"), m.liveWorkspaceIds)
    }

    @Test
    fun `releasing a window that was never recorded changes nothing`() {
        val m = manager()
        m.setWindowWorkspaces("w1", setOf("design"))
        m.releaseWindow("w-unknown")

        assertEquals(setOf("design"), m.liveWorkspaceIds)
    }
}
