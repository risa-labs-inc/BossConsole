package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * BossConsole#722: Save Space must update the Space THIS WINDOW is showing, resolved from its own
 * `SplitViewState.currentWorkspaceId` - never `WorkspaceManager.currentWorkspace`, which is one
 * value the whole process shares and names whichever window loaded a Space most recently.
 *
 * Deterministic failure this pins: window A is in Space A, window B is in Space B and is the last
 * window to have loaded a Space (so `currentWorkspace` now names B), and window A presses Save.
 * The old `BossAppMenuActionEffects` handler read `workspaceManager.currentWorkspace.value` for the
 * Space IDENTITY and only used window A's `SplitViewState` for the LAYOUT bytes - so it combined
 * window A's layout with window B's id/name and could silently overwrite window B's file with
 * window A's screen.
 *
 * [savedCopyOfIn] is the fix's core: a plain lookup by the window's own id against the Space list,
 * with no `currentWorkspace` in scope for a caller to reach for by mistake. Tested directly here
 * rather than through `WorkspaceManager`, which cannot be driven synchronously from a test - see
 * `SavedSpaceMergeTest`'s own note on why.
 */
class SaveWorkspaceWindowIdentityTest {
    private fun space(
        id: String,
        name: String,
        tabTitle: String = "Terminal",
    ) = LayoutWorkspace(
        id = id,
        name = name,
        description = "d",
        layout =
            SplitConfig.SinglePanel(
                panel = PanelConfig(id = "main", tabs = listOf(TabConfig(type = "terminal", title = tabTitle))),
            ),
    )

    @Test
    fun `resolves the Space by the window's own id, whichever Space is process-global current`() {
        val spaceA = space("space-a", "A", tabTitle = "A's tab")
        val spaceB = space("space-b", "B", tabTitle = "B's tab")
        val list = listOf(spaceA, spaceB)

        // Window B loaded last, so a process-global `currentWorkspace` would name B - but this
        // function takes no such pointer, so there is nothing here for that to leak through.
        assertEquals(spaceA, savedCopyOfIn("space-a", list))
        assertEquals(spaceB, savedCopyOfIn("space-b", list))
    }

    @Test
    fun `window A saving never resolves to window B's Space`() {
        val spaceA = space("space-a", "A")
        val spaceB = space("space-b", "B")

        val resolvedForWindowA = savedCopyOfIn("space-a", listOf(spaceA, spaceB))

        assertNotEquals(spaceB.id, resolvedForWindowA?.id, "window A's save must never target window B's Space")
        assertEquals(spaceA.id, resolvedForWindowA?.id)
    }

    @Test
    fun `a window with no Space loaded resolves to nothing, so Save creates a new Space instead`() {
        // BossAppMenuActionEffects only calls this when SplitViewState.currentWorkspaceId is
        // non-null; a null id short-circuits to the "create a new Space" branch without reaching
        // this lookup at all. This pins the other half: an id that simply names nothing yet.
        assertNull(savedCopyOfIn("space-never-saved", listOf(space("space-a", "A"))))
    }

    /**
     * The manager-level half: [WorkspaceManager.saveWorkspace] takes the Space to save as a
     * parameter now, rather than reading `currentWorkspace` internally the way
     * [WorkspaceManager.saveCurrentWorkspace] still does for its own (single-window-safe) callers.
     * Exercised through the synchronous return value only - the disk write itself runs on the
     * manager's own `Dispatchers.Main` scope, which a JVM unit test cannot pump reliably (see
     * `SavedSpaceMergeTest`'s note) - but the identity decision that #722 is about happens
     * synchronously, before that write is even queued.
     */
    @Test
    fun `WorkspaceManager saveWorkspace saves the identity handed to it, not currentWorkspace`() {
        val manager = WorkspaceManager()
        val spaceB = space("space-b", "B")
        // Simulates another window loading Space B last, which is what makes currentWorkspace
        // process-global and stale for window A.
        manager.loadWorkspace(spaceB)
        assertEquals("space-b", manager.currentWorkspace.value?.id)

        val spaceAEdited = space("space-a", "A", tabTitle = "A-edited")
        val saved = manager.saveWorkspace(spaceAEdited)

        assertEquals("space-a", saved?.id, "must save window A's own identity, not currentWorkspace's")
        val panel = saved?.layout as SplitConfig.SinglePanel
        assertEquals(
            "A-edited",
            panel.panel.tabs
                .single()
                .title,
        )
    }
}
