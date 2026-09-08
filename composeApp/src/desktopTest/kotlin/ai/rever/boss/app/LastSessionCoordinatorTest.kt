package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for who writes the single app-level "Last Session" workspace (Issue #19).
 *
 * Two regressions are pinned here:
 * - a window closing while others stay open must NOT write (the reported bug:
 *   closing a secondary window overwrote the primary's session)
 * - *something* must write on the exits that never dispose a Compose composition
 *   (macOS Cmd+Q, quit-for-update, SIGTERM), or the symptom survives entirely
 */
class LastSessionCoordinatorTest {
    private fun layoutNamed(name: String) =
        LayoutWorkspace(
            id = "ignored",
            name = name,
            description = "ignored",
            layout =
                SplitConfig.SinglePanel(
                    panel = PanelConfig(id = "panel-$name", tabs = listOf(TabConfig(type = "terminal", title = name))),
                ),
        )

    private class RecordingSave {
        val saved = CopyOnWriteArrayList<String>()

        fun save(layout: LayoutWorkspace): Boolean {
            saved.add(layout.name)
            return true
        }
    }

    private fun coordinatorWith(recorder: RecordingSave) = LastSessionCoordinator(recorder::save)

    private fun LastSessionCoordinator.registerWindow(
        windowId: String,
        isFirstWindow: Boolean = false,
        layoutName: String = windowId,
    ) = register(windowId, isFirstWindow) { layoutNamed(layoutName) }

    @Test
    fun `a window closing while others stay open does not write`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)
        coordinator.registerWindow("primary", isFirstWindow = true)
        coordinator.registerWindow("secondary")

        val wrote = coordinator.onWindowDisposed("secondary")

        assertFalse(wrote, "A secondary window closing must not write the app-level session")
        assertTrue(recorder.saved.isEmpty(), "Nothing should have been written, got ${recorder.saved}")
        assertEquals(1, coordinator.liveWindowCount)
    }

    @Test
    fun `the last window closing writes its own layout`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)
        coordinator.registerWindow("primary", isFirstWindow = true, layoutName = "primary-layout")
        coordinator.registerWindow("secondary", layoutName = "secondary-layout")

        coordinator.onWindowDisposed("secondary")
        val wrote = coordinator.onWindowDisposed("primary")

        assertTrue(wrote)
        assertEquals(listOf("primary-layout"), recorder.saved)
        assertEquals(0, coordinator.liveWindowCount)
    }

    @Test
    fun `disposing the same window twice writes once`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)
        coordinator.registerWindow("only")

        assertTrue(coordinator.onWindowDisposed("only"))
        assertFalse(coordinator.onWindowDisposed("only"))
        assertEquals(1, recorder.saved.size)
    }

    @Test
    fun `an unknown window never writes`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)
        coordinator.registerWindow("only")

        assertFalse(coordinator.onWindowDisposed("never-registered"))
        assertTrue(recorder.saved.isEmpty())
        assertEquals(1, coordinator.liveWindowCount, "An unknown id must not disturb tracking")
    }

    /**
     * The reason the live-window set is concurrent: on quit, windows dispose from
     * the Compose applier thread while the JVM shutdown hook may already be
     * running. Exactly one of them may write.
     */
    @Test
    fun `two windows disposing concurrently produce exactly one write`() {
        repeat(50) {
            val recorder = RecordingSave()
            val coordinator = coordinatorWith(recorder)
            coordinator.registerWindow("a")
            coordinator.registerWindow("b")

            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val writes = AtomicInteger(0)
            listOf("a", "b").forEach { id ->
                Thread {
                    start.await()
                    if (coordinator.onWindowDisposed(id)) writes.incrementAndGet()
                    done.countDown()
                }.start()
            }
            start.countDown()

            assertTrue(done.await(5, TimeUnit.SECONDS), "Disposals did not finish")
            assertEquals(1, writes.get(), "Exactly one disposal may claim the write")
            assertEquals(1, recorder.saved.size, "Exactly one write may reach disk, got ${recorder.saved}")
        }
    }

    /**
     * macOS Cmd+Q (JDK default QuitStrategy.NORMAL_EXIT -> System.exit),
     * ApplicationRestarter's exitProcess paths and SIGTERM never dispose a
     * composition, so the shutdown hook is the only thing that runs.
     */
    @Test
    fun `process exit writes when no window ever disposed`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)
        coordinator.registerWindow("primary", isFirstWindow = true, layoutName = "primary-layout")

        val wrote = coordinator.saveOnProcessExit()

        assertTrue(wrote, "The shutdown-hook path must write when no dispose happened")
        assertEquals(listOf("primary-layout"), recorder.saved)
    }

    /**
     * New windows deliberately start fresh (#129), so persisting a secondary
     * window's near-empty layout on quit would reproduce #19 by another route.
     */
    @Test
    fun `process exit prefers the primary window's layout`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)
        coordinator.registerWindow("secondary-1", layoutName = "secondary-1-layout")
        coordinator.registerWindow("primary", isFirstWindow = true, layoutName = "primary-layout")
        coordinator.registerWindow("secondary-2", layoutName = "secondary-2-layout")

        coordinator.saveOnProcessExit()

        assertEquals(listOf("primary-layout"), recorder.saved)
    }

    @Test
    fun `process exit does not write again after a window dispose already saved`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)
        coordinator.registerWindow("only", layoutName = "only-layout")

        assertTrue(coordinator.onWindowDisposed("only"))
        assertFalse(coordinator.saveOnProcessExit(), "The hook must not double-write against the dispose path")
        assertEquals(listOf("only-layout"), recorder.saved)
    }

    @Test
    fun `process exit with no windows at all writes nothing`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)

        assertFalse(coordinator.saveOnProcessExit())
        assertTrue(recorder.saved.isEmpty())
    }

    /**
     * The app outlives its windows on macOS: after the last window closed and was
     * saved, opening a new window starts a session that must be saveable again.
     */
    @Test
    fun `a newly registered window re-arms the save`() {
        val recorder = RecordingSave()
        val coordinator = coordinatorWith(recorder)
        coordinator.registerWindow("first", layoutName = "first-layout")
        coordinator.onWindowDisposed("first")

        coordinator.registerWindow("second", layoutName = "second-layout")
        val wrote = coordinator.onWindowDisposed("second")

        assertTrue(wrote, "A later session must be saveable after an earlier one was written")
        assertEquals(listOf("first-layout", "second-layout"), recorder.saved)
    }

    @Test
    fun `a failing save does not consume the write claim`() {
        val attempts = AtomicInteger(0)
        val coordinator =
            LastSessionCoordinator(
                save = { _ ->
                    if (attempts.incrementAndGet() == 1) error("disk on fire") else true
                },
            )
        coordinator.registerWindow("primary", isFirstWindow = true)

        assertFalse(coordinator.saveOnProcessExit(), "A throwing save must be reported as not written")
        assertTrue(coordinator.saveOnProcessExit(), "A failed attempt must not consume the one write claim")
        assertEquals(2, attempts.get())
    }

    @Test
    fun `the multi-Space record is written by the same window, under the same claim`() {
        // The whole point of this class is that ONE window produces the app-level session record.
        // Two records describing one session must therefore be produced together: a second writer
        // for the set would reintroduce #19 by another route, with a secondary window's set beside
        // a primary window's single record.
        val recorder = RecordingSave()
        val sets = CopyOnWriteArrayList<String>()
        val coordinator =
            LastSessionCoordinator(
                save = recorder::save,
                saveSet = { set ->
                    sets.add(set?.spaces?.joinToString(",") { it.name } ?: "none")
                    true
                },
            )
        coordinator.register(
            windowId = "primary",
            isFirstWindow = true,
            extractSet = {
                LastSessionSet(
                    activeWorkspaceId = "b",
                    spaces = listOf(layoutNamed("a").copy(id = "a"), layoutNamed("b").copy(id = "b")),
                )
            },
        ) { layoutNamed("primary-layout") }
        coordinator.register(windowId = "secondary", isFirstWindow = false) { layoutNamed("secondary-layout") }

        assertFalse(coordinator.onWindowDisposed("secondary"), "a secondary window writes neither record")
        assertTrue(sets.isEmpty(), "including the set, got $sets")

        assertTrue(coordinator.onWindowDisposed("primary"))
        assertEquals(listOf("primary-layout"), recorder.saved)
        assertEquals(listOf("a,b"), sets, "one set, written by the window that wrote the single record")
    }

    @Test
    fun `a window running one Space asks for the set to be deleted`() {
        // A stale set would WIN on restore, so "no set" has to mean "remove the one there is"
        // rather than "leave it alone". The null comes from `sessionSetOf`, which refuses to build
        // a set for fewer than two Spaces.
        val sets = CopyOnWriteArrayList<String>()
        val coordinator =
            LastSessionCoordinator(
                save = { true },
                saveSet = { set ->
                    sets.add(set?.spaces?.size?.toString() ?: "deleted")
                    true
                },
            )
        coordinator.register(windowId = "primary", isFirstWindow = true, extractSet = { null }) { layoutNamed("one") }

        assertTrue(coordinator.saveOnProcessExit())
        assertEquals(listOf("deleted"), sets)
    }
}
