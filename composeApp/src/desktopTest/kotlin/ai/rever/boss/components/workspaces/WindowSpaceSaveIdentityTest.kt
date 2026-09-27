package ai.rever.boss.components.workspaces

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WindowSpaceSaveIdentityTest {
    private fun layout(panelId: String) =
        ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel(
            ai.rever.boss.plugin.workspace.PanelConfig(
                id = panelId,
                tabs = emptyList(),
            ),
        )

    private fun space(
        id: String,
        panelId: String = "$id-panel",
        projectPath: String? = "/projects/$id",
        timestamp: Long = 1L,
    ) = LayoutWorkspace(
        id = id,
        name = "$id-name",
        description = "$id-description",
        layout = layout(panelId),
        timestamp = timestamp,
        projectPath = projectPath,
    )

    @Test
    fun `missing active identity never borrows a different windows global Space`() {
        val otherWindow = space("space-b")
        val liveLayout =
            space(
                id = "fresh-space-a",
                panelId = "live-a",
                projectPath = "/live/a",
                timestamp = 2L,
            )

        val snapshot =
            spaceSnapshotForSave(
                activeWorkspaceId = "space-a",
                liveLayout = liveLayout,
                knownSpaces = emptyList(),
                processGlobalCurrent = otherWindow,
            )

        assertEquals(liveLayout.id, snapshot.id)
        assertEquals(liveLayout.layout, snapshot.layout)
        assertEquals(liveLayout.projectPath, snapshot.projectPath)
        assertNotEquals(otherWindow.id, snapshot.id)
        assertNotEquals(otherWindow.name, snapshot.name)
        assertNotEquals(otherWindow.description, snapshot.description)
    }

    @Test
    fun `save binds the invoking windows live state to its own Space identity`() {
        val windowA = space("space-a", "saved-a", "/saved/a")
        val windowB = space("space-b", "saved-b", "/saved/b")
        val liveLayoutFromA =
            space(
                id = "throwaway-extraction-id",
                panelId = "live-a",
                projectPath = "/live/a",
                timestamp = 2L,
            )

        val snapshot =
            spaceSnapshotForSave(
                activeWorkspaceId = windowA.id,
                liveLayout = liveLayoutFromA,
                knownSpaces = listOf(windowA, windowB),
                processGlobalCurrent = windowB,
            )

        assertEquals(windowA.id, snapshot.id)
        assertEquals(windowA.name, snapshot.name)
        assertEquals(windowA.description, snapshot.description)
        assertEquals(liveLayoutFromA.layout, snapshot.layout)
        assertEquals(liveLayoutFromA.projectPath, snapshot.projectPath)
        assertEquals(liveLayoutFromA.timestamp, snapshot.timestamp)
        assertNotEquals(windowB.layout, snapshot.layout)
        assertNotEquals(windowB.projectPath, snapshot.projectPath)
    }

    @Test
    fun `normal single-window save preserves identity and uses live state`() {
        val currentSpace = space("space-a", "saved-a", "/saved/a")
        val liveLayout =
            space(
                id = "throwaway-extraction-id",
                panelId = "live-a",
                projectPath = "/live/a",
                timestamp = 2L,
            )

        val snapshot =
            spaceSnapshotForSave(
                activeWorkspaceId = currentSpace.id,
                liveLayout = liveLayout,
                knownSpaces = listOf(currentSpace),
                processGlobalCurrent = currentSpace,
            )

        assertEquals(currentSpace.id, snapshot.id)
        assertEquals(currentSpace.name, snapshot.name)
        assertEquals(currentSpace.description, snapshot.description)
        assertEquals(liveLayout.layout, snapshot.layout)
        assertEquals(liveLayout.projectPath, snapshot.projectPath)
        assertEquals(liveLayout.timestamp, snapshot.timestamp)
    }

    @Test
    fun `matching global identity is a safe fallback while the Space list catches up`() {
        val currentSpace = space("space-a", "saved-a", "/saved/a")
        val liveLayout =
            space(
                id = "throwaway-extraction-id",
                panelId = "live-a",
                projectPath = "/live/a",
                timestamp = 2L,
            )

        val snapshot =
            spaceSnapshotForSave(
                activeWorkspaceId = currentSpace.id,
                liveLayout = liveLayout,
                knownSpaces = emptyList(),
                processGlobalCurrent = currentSpace,
            )

        assertEquals(currentSpace.id, snapshot.id)
        assertEquals(currentSpace.name, snapshot.name)
        assertEquals(liveLayout.layout, snapshot.layout)
        assertEquals(liveLayout.projectPath, snapshot.projectPath)
    }

    @Test
    fun `save creates a new Space when the window has no active identity`() {
        val otherWindow = space("space-b", "other-window", "/other/window")
        val liveLayout =
            space(
                id = "new-space-id",
                panelId = "new-live-layout",
                projectPath = "/live/new",
                timestamp = 12_345L,
            )

        val snapshot =
            spaceSnapshotForSave(
                activeWorkspaceId = null,
                liveLayout = liveLayout,
                knownSpaces = listOf(otherWindow),
                processGlobalCurrent = otherWindow,
            )

        assertEquals("new-space-id", snapshot.id)
        assertEquals("Workspace 12", snapshot.name)
        assertEquals("Saved workspace", snapshot.description)
        assertEquals(liveLayout.layout, snapshot.layout)
        assertEquals(liveLayout.projectPath, snapshot.projectPath)
        assertNotEquals(otherWindow.id, snapshot.id)
    }

    @Test
    fun `named save resolves an owner registered after composition`() {
        val windowId = "named-save-late-registration"
        SplitViewStateRegistry.unregister(windowId)
        val ownerBeforeRegistration = NamedSaveOwner.capture(windowId)
        val state = SplitViewState(TabRegistry(), windowId)

        try {
            SplitViewStateRegistry.register(windowId, state)
            val ownerAtPress = NamedSaveOwner.capture(windowId)

            assertEquals(NamedSaveRebindOutcome.NEVER_REGISTERED, ownerBeforeRegistration.rebind("stale-space"))
            assertEquals(NamedSaveRebindOutcome.REBOUND, ownerAtPress.rebind("saved-space"))
            assertEquals("saved-space", state.currentWorkspaceId)
        } finally {
            SplitViewStateRegistry.unregister(windowId)
            state.dispose()
        }
    }

    @Test
    fun `named save drops a rebind after its captured window deregisters`() {
        val windowId = "named-save-deregistered"
        val state = SplitViewState(TabRegistry(), windowId)
        SplitViewStateRegistry.register(windowId, state)
        val ownerAtPress = NamedSaveOwner.capture(windowId)

        try {
            SplitViewStateRegistry.unregister(windowId)

            assertEquals(NamedSaveRebindOutcome.DEREGISTERED, ownerAtPress.rebind("saved-space"))
            assertNotEquals("saved-space", state.currentWorkspaceId)
        } finally {
            SplitViewStateRegistry.unregister(windowId)
            state.dispose()
        }
    }
}
