package ai.rever.boss.components.workspaces

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rebind seam the success-only save commits introduced, driven through the real
 * `WorkspaceManager.saveCurrentWorkspace` callbacks against a controlled directory:
 *
 *  - the success callback fires exactly once, and only AFTER the manager has committed the saved
 *    Space to its list (which is what makes a rebind safe - it can never name an id the list
 *    lacks);
 *  - a failed write fires NO success callback, only the failure callback;
 *  - saving out of the `last-session` slot mints an ordinary Space, the window rebinds to it, and
 *    the second save updates that Space in place rather than minting a third;
*  - `SaveInFlightLatch` supports both coalescing unnamed saves and ignoring overlapping named
*    saves, and a throwing queued callback never leaves the latch wedged.
 *
 * The write itself runs on the manager's IO thread, so each test drives the test Main with
 * `runCurrent()` in a bounded real-time wait rather than virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveRebindSeamTest {
    private fun managerIn(directory: String) = WorkspaceManager(fileManager = WorkspaceFileManager(directory))

    /**
     * Drive the test Main until [predicate] holds, waiting real time for the manager's IO write to
     * land in between. Bounded so a wedged write fails the test instead of hanging it.
     */
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

    /**
     * Wait for the manager's constructor load to land. `WorkspaceManager.init` launches
     * `loadAllWorkspaces`, which ends with an UNCONDITIONAL `_workspaces.value = merge...` -
     * if that lands after a save has committed its row, it would drop the row and the
     * assertions below would fail or NPE. Saving before the load settles would make a green
     * run a coin flip rather than a fact, so every test drains it first.
     */
    private fun TestScope.awaitLoadSettled(manager: WorkspaceManager) {
        awaitMain { manager.workspaces.value.size >= PredefinedWorkspaces.allWorkspaces.size }
    }

    private fun space(
        id: String,
        name: String,
    ) = LayoutWorkspace(
        id = id,
        name = name,
        description = "$name description",
        layout =
            SinglePanel(
                PanelConfig(
                    id = "$id-panel",
                    tabs = emptyList(),
                ),
            ),
        timestamp = 1L,
        projectPath = "/projects/$id",
    )

    @Test
    fun `success callback fires once, after the manager has committed the saved Space`(
        @TempDir directory: File,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = managerIn(directory.absolutePath)
            awaitLoadSettled(manager)
            val window = SplitViewState(TabRegistry(), "save-rebind-window")
            val current = space("space-a", "Space A")
            manager.updateCurrentWorkspace(current)

            var savedCount = 0
            var committedWhenSaved = false
            manager.saveCurrentWorkspace(
                name = null,
                onSaved = { saved ->
                    savedCount++
                    committedWhenSaved = manager.savedCopyOf(saved.id) != null
                    window.rebindCurrentWorkspace(saved.id)
                },
            )

            awaitMain { savedCount == 1 }
            Thread.sleep(50)
            runCurrent()
            assertEquals(1, savedCount, "the success callback fires exactly once")
            assertTrue(committedWhenSaved, "onSaved must run only after the list holds the saved id")
            assertEquals(current.id, window.currentWorkspaceId, "the window rebinds to the saved Space")
            assertNotNull(manager.savedCopyOf(current.id))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a failed write fires no success callback, only the failure callback`(
        @TempDir base: File,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val current = space("space-b", "Space B")

            // Make the write fail hermetically: point the manager at a directory whose
            // parent is a FILE, so the atomic sibling write cannot happen.
            val unwritable = File(base, "unwritable").apply { writeText("not a directory") }
            val failingManager =
                managerIn(File(unwritable, "nested").absolutePath)
            awaitLoadSettled(failingManager)
            failingManager.updateCurrentWorkspace(current)

            var savedCount = 0
            var failedName: String? = null
            failingManager.saveCurrentWorkspace(
                name = null,
                onSaved = { savedCount++ },
                onFailed = { failedName = it },
            )

            awaitMain { failedName != null }
            Thread.sleep(50)
            runCurrent()
            assertEquals(0, savedCount, "no success callback for a write that never landed")
            assertEquals("Space B", failedName, "the caller hears which save failed")
            assertNull(failingManager.savedCopyOf(current.id), "a failed save must not enter the list")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `saving out of the last-session slot rebinds, and the second save updates in place`(
        @TempDir directory: File,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = managerIn(directory.absolutePath)
            awaitLoadSettled(manager)
            val window = SplitViewState(TabRegistry(), "slot-rebind-window")

            val lastSession = space(LAST_SESSION_ID, "Last Session")
            manager.updateCurrentWorkspace(lastSession)

            manager.saveCurrentWorkspace(
                name = null,
                onSaved = { saved -> window.rebindCurrentWorkspace(saved.id) },
            )
            awaitMain { window.currentWorkspaceId != null }
            val firstId = window.currentWorkspaceId
            assertNotNull(firstId)
            assertFalse(isSpaceSlot(firstId), "the save must leave the slot for an ordinary Space")

            // Second save, as the menu does once rebound: in-place update, no third Space.
            manager.updateCurrentWorkspace(manager.savedCopyOf(firstId)!!.copy(timestamp = 2L))
            var secondSaved: LayoutWorkspace? = null
            manager.saveCurrentWorkspace(
                name = null,
                onSaved = { secondSaved = it },
            )
            awaitMain { secondSaved != null }
            Thread.sleep(50)
            runCurrent()

            assertEquals(firstId, secondSaved!!.id, "the second save updates the same Space")
            assertEquals(1, manager.workspaces.value.count { it.id == firstId })
            assertEquals(firstId, window.currentWorkspaceId, "the window still points at its own Space")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `unnamed save coalesces overlapping presses into one rerun`() {
        val latch = SaveInFlightLatch()
        var started = 0

        assertTrue(latch.press())
        started++
        latch.begin()

        assertFalse(latch.press())
        assertFalse(latch.press())

        latch.settle {
            started++
            latch.begin()
        }

        assertEquals(2, started, "several overlapping presses produce one rerun")
        assertTrue(latch.inFlight, "the rerun owns the next in-flight save")

        latch.settle {}
        assertFalse(latch.inFlight)
    }

    @Test
    fun `named save can ignore an overlapping press without wedging`() {
        val latch = SaveInFlightLatch()

        assertTrue(latch.press())
        latch.begin()
        assertFalse(latch.press())

        // A named save must not replay the queued press because that could mint "Name 2".
        latch.settle {}

        assertFalse(latch.inFlight)
        assertTrue(latch.press(), "a later deliberate named save remains available")
    }

    @Test
    fun `throwing queued callback leaves the latch open`() {
        val latch = SaveInFlightLatch()

        assertTrue(latch.press())
        latch.begin()
        assertFalse(latch.press())

        assertFailsWith<IllegalStateException> {
            latch.settle { throw IllegalStateException("queued callback failed") }
        }

        assertFalse(latch.inFlight)
        assertTrue(latch.press(), "callback failure must not wedge future saves")
    }
}
