package ai.rever.boss.components.workspaces

import ai.rever.boss.app.wireSaveWorkspaceMenuEffect
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WindowSpaceSaveIdentityTest {
    private fun layout(panelId: String) =
        SinglePanel(
            PanelConfig(
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

    private fun managerIn(directory: String) = WorkspaceManager(fileManager = WorkspaceFileManager(directory))

    private fun TestScope.awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!predicate()) {
            assertTrue(
                System.currentTimeMillis() < deadline,
                "the save callback did not arrive within 15 seconds",
            )
            Thread.sleep(20)
            runCurrent()
        }
    }

    private fun TestScope.awaitLoadSettled(manager: WorkspaceManager) {
        awaitMain { manager.workspaces.value.size >= PredefinedWorkspaces.allWorkspaces.size }
    }

    @Test
    fun `menu save wiring builds snapshot from invoking window id`(
        @TempDir directory: File,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val windowIdA = "window-save-id-a"
        val windowIdB = "window-save-id-b"
        val stateA = SplitViewState(TabRegistry(), windowIdA)
        val stateB = SplitViewState(TabRegistry(), windowIdB)
        SplitViewStateRegistry.register(windowIdA, stateA)
        SplitViewStateRegistry.register(windowIdB, stateB)

        try {
            val manager = managerIn(directory.absolutePath)
            awaitLoadSettled(manager)

            val spaceA = space("space-a", projectPath = "/projects/a")
            val spaceB = space("space-b", projectPath = "/projects/b")

            manager.updateCurrentWorkspace(spaceA)
            var aSaved = false
            manager.saveCurrentWorkspace(name = null, onSaved = { aSaved = true })
            awaitMain { aSaved }

            manager.updateCurrentWorkspace(spaceB)
            var bSaved = false
            manager.saveCurrentWorkspace(name = null, onSaved = { bSaved = true })
            awaitMain { bSaved }

            stateA.rebindCurrentWorkspace(spaceA.id)
            stateB.rebindCurrentWorkspace(spaceB.id)

            val projectStateA = WindowProjectState(windowIdA)
            projectStateA.selectProject(Project(name = "ProjA", path = "/projects/live-a"))

            val saveEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
            val statusMessages = mutableListOf<String>()

            wireSaveWorkspaceMenuEffect(
                scope = backgroundScope,
                windowId = windowIdA,
                splitViewState = stateA,
                windowProjectState = projectStateA,
                workspaceManager = manager,
                saveEvents = saveEvents,
                onStatusMessage = { statusMessages.add(it) },
            )
            runCurrent()

            // Event for window B is ignored by window A's effect
            saveEvents.tryEmit(windowIdB)
            runCurrent()
            assertTrue(statusMessages.isEmpty())

            // Event for window A triggers save
            saveEvents.tryEmit(windowIdA)
            awaitMain { statusMessages.contains("Space Saved") }

            // Snapshot was built from window A's active space identity ("space-a"), NOT window B's ("space-b")
            val savedSpace = manager.savedCopyOf(spaceA.id)
            assertNotNull(savedSpace)
            assertEquals(spaceA.id, savedSpace.id)
            assertEquals("/projects/live-a", savedSpace.projectPath)
            assertEquals(spaceA.id, stateA.currentWorkspaceId)
        } finally {
            SplitViewStateRegistry.unregister(windowIdA)
            SplitViewStateRegistry.unregister(windowIdB)
            stateA.dispose()
            stateB.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `menu save wiring coalesces second event into exactly one re-run on latest layout`(
        @TempDir directory: File,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val windowId = "window-coalesce"
        val state = SplitViewState(TabRegistry(), windowId)
        SplitViewStateRegistry.register(windowId, state)

        try {
            val manager = managerIn(directory.absolutePath)
            awaitLoadSettled(manager)

            val initialSpace = space("space-coalesce", projectPath = "/projects/v1")
            manager.updateCurrentWorkspace(initialSpace)
            var initialSaved = false
            manager.saveCurrentWorkspace(name = null, onSaved = { initialSaved = true })
            awaitMain { initialSaved }

            state.rebindCurrentWorkspace(initialSpace.id)

            val projectState = WindowProjectState(windowId)
            projectState.selectProject(Project(name = "Proj", path = "/projects/v1"))

            val saveEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
            val statusMessages = mutableListOf<String>()
            val latch = SaveInFlightLatch()

            wireSaveWorkspaceMenuEffect(
                scope = backgroundScope,
                windowId = windowId,
                splitViewState = state,
                windowProjectState = projectState,
                workspaceManager = manager,
                saveEvents = saveEvents,
                saveLatch = latch,
                onStatusMessage = { statusMessages.add(it) },
            )
            runCurrent()

            // Emit first save event
            saveEvents.tryEmit(windowId)
            runCurrent()
            assertTrue(latch.inFlight, "first save must be in flight")

            // While first save is in flight, update layout project path to v2, and emit multiple events
            projectState.selectProject(Project(name = "Proj", path = "/projects/v2"))
            saveEvents.tryEmit(windowId)
            saveEvents.tryEmit(windowId)
            runCurrent()

            // Wait until both saves (initial + 1 coalesced re-run) have settled
            awaitMain { statusMessages.size == 2 }

            // Exactly 2 saves ran (not 3), and the final saved workspace has the latest layout (v2)
            assertEquals(2, statusMessages.size)
            assertFalse(latch.inFlight)
            val finalSpace = manager.savedCopyOf(initialSpace.id)
            assertNotNull(finalSpace)
            assertEquals("/projects/v2", finalSpace.projectPath)
        } finally {
            SplitViewStateRegistry.unregister(windowId)
            state.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `menu save re-run is dropped after window deregisters`(
        @TempDir directory: File,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val windowId = "window-deregister-drop"
        val state = SplitViewState(TabRegistry(), windowId)
        SplitViewStateRegistry.register(windowId, state)

        try {
            val manager = managerIn(directory.absolutePath)
            awaitLoadSettled(manager)

            val initialSpace = space("space-deregister", projectPath = "/projects/orig")
            manager.updateCurrentWorkspace(initialSpace)
            var initialSaved = false
            manager.saveCurrentWorkspace(name = null, onSaved = { initialSaved = true })
            awaitMain { initialSaved }

            state.rebindCurrentWorkspace(initialSpace.id)

            val projectState = WindowProjectState(windowId)
            projectState.selectProject(Project(name = "Proj", path = "/projects/orig"))

            val saveEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
            val statusMessages = mutableListOf<String>()
            val latch = SaveInFlightLatch()

            wireSaveWorkspaceMenuEffect(
                scope = backgroundScope,
                windowId = windowId,
                splitViewState = state,
                windowProjectState = projectState,
                workspaceManager = manager,
                saveEvents = saveEvents,
                saveLatch = latch,
                onStatusMessage = { statusMessages.add(it) },
            )
            runCurrent()

            // Start first save
            saveEvents.tryEmit(windowId)
            runCurrent()
            assertTrue(latch.inFlight)

            // Queue a second save while first is in flight
            saveEvents.tryEmit(windowId)
            runCurrent()

            // Now deregister the window before the first save settles
            SplitViewStateRegistry.unregister(windowId)

            // Wait for latch to settle
            awaitMain { !latch.inFlight }

            // The late rebind was dropped because window deregistered, and the queued re-run was dropped
            assertTrue(statusMessages.isEmpty())
            assertFalse(latch.inFlight)
        } finally {
            SplitViewStateRegistry.unregister(windowId)
            state.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `named save dialog ignores overlapping press rather than replaying`(
        @TempDir directory: File,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val windowId = "named-save-ignore-overlap"
        val state = SplitViewState(TabRegistry(), windowId)
        SplitViewStateRegistry.register(windowId, state)

        try {
            val manager = managerIn(directory.absolutePath)
            awaitLoadSettled(manager)

            val latch = SaveInFlightLatch()
            var saveCount = 0

            // Simulate first dialog submission
            assertTrue(latch.press())
            val saveOwner = NamedSaveOwner.capture(windowId)
            val currentSpace = space("named-orig")
            manager.updateCurrentWorkspace(currentSpace)
            latch.begin()
            manager.saveCurrentWorkspace(
                name = "First Custom Name",
                onSaved = { saved ->
                    try {
                        saveOwner.rebind(saved.id)
                        saveCount++
                    } finally {
                        latch.settle {}
                    }
                },
            )

            // Simulate second overlapping submission while first is still in-flight
            val overlappingAccepted = latch.press()
            assertFalse(overlappingAccepted, "overlapping named save press must be ignored")

            awaitMain { saveCount == 1 }
            Thread.sleep(50)
            runCurrent()

            // Exactly 1 save landed; the overlapping submission was NOT replayed
            assertEquals(1, saveCount)
            assertFalse(latch.inFlight)
            assertTrue(latch.press(), "latch is available for future deliberate saves")
        } finally {
            SplitViewStateRegistry.unregister(windowId)
            state.dispose()
            Dispatchers.resetMain()
        }
    }
}
