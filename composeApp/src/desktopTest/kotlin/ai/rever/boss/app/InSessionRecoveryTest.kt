package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LAST_SESSION_NAME
import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.components.workspaces.isRestorable
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which recovery files a hard kill leaves behind, and who is allowed to have written them.
 *
 * The multi-Space set used to be written only at a clean shutdown, and restore reads it in
 * preference to `Last_Session.json`. So after a session that restored a set, worked, and was then
 * killed, the next launch brought back the clean shutdown BEFORE it and the watcher wrote those
 * older layouts over the fresh record. And every window's watcher wrote the record, so a secondary
 * window's layout could replace the primary's. [writeInSessionRecovery] is the watcher's write:
 * the session record's owner keeps both files current, as one uninterruptible pair that cannot
 * interleave with the shutdown write, and no other window writes either.
 *
 * Each "session" is its own [WorkspaceManager] over one directory, which is what a process restart
 * is to these files, and the coordinator is wired to it the way `LastSessionCoordinator.instance`
 * is wired to the app's. What is NOT asserted here is the seam in `BossAppStartupEffects`: that
 * the watcher passes the same window id the window registered with. Both are the one `windowId`
 * local of one composable, so it is checked by reading rather than by a test.
 */
class InSessionRecoveryTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun directory(): File = Files.createTempDirectory("in-session-recovery").toFile().also { dirs += it }

    private fun session(dir: File) = WorkspaceManager(fileManager = WorkspaceFileManager(dir.absolutePath))

    private fun space(
        id: String,
        tab: String,
    ) = LayoutWorkspace(
        id = id,
        name = id,
        description = "d",
        layout =
            SplitConfig.SinglePanel(
                PanelConfig(id = "panel-$id", tabs = listOf(TabConfig(type = "terminal", title = tab))),
            ),
        timestamp = 1_700_000_000_000,
        projectPath = "/tmp/proj",
    )

    private fun record(tab: String) = space(LAST_SESSION_ID, tab).copy(name = LAST_SESSION_NAME)

    private fun set(vararg tabs: Pair<String, String>) =
        LastSessionSet(activeWorkspaceId = tabs.last().first, spaces = tabs.map { (id, tab) -> space(id, tab) })

    private fun titles(set: LastSessionSet?) =
        set?.spaces?.map {
            (it.layout as SplitConfig.SinglePanel)
                .panel.tabs
                .single()
                .title
        }

    /** Session 1 ran two Spaces and quit cleanly: the coordinator wrote both files. */
    private fun cleanShutdown(dir: File) {
        val first = session(dir)
        assertTrue(first.saveLastSessionBlocking(space("a", tab = "old a")))
        assertTrue(first.saveLastSessionSetBlocking(set("b" to "old b", "a" to "old a")))
    }

    private suspend fun recordedTitle(manager: WorkspaceManager): String {
        // The manager loads its list asynchronously at construction; wait for the record.
        val record =
            withTimeout(10_000) {
                manager.workspaces.first { list -> list.any { it.id == LAST_SESSION_ID } }
            }.single { it.id == LAST_SESSION_ID }
        return (record.layout as SplitConfig.SinglePanel)
            .panel.tabs
            .single()
            .title
    }

    /**
     * Records the order the recovery files are written in, and holds the FIRST write that reaches
     * it until [release] - which is how a test stands in the middle of a pair.
     */
    private class Gate(
        held: Boolean,
    ) {
        val order = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (held) 1 else 0)
        private val first = AtomicBoolean(true)

        fun pass(file: String) {
            order += file
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "the held write was never released" }
            }
        }
    }

    /** The coordinator as `LastSessionCoordinator.instance` wires it, over [manager]. */
    private fun wired(
        manager: WorkspaceManager,
        gate: Gate = Gate(held = false),
    ) = LastSessionCoordinator(
        save = {
            gate.pass("shutdown record")
            manager.saveLastSessionBlocking(it)
        },
        saveSet = {
            gate.pass("set")
            manager.saveLastSessionSetBlocking(it)
        },
        saveRecord = {
            gate.pass("record")
            manager.writeLastSessionRecordBlocking(it)
        },
    )

    private fun LastSessionCoordinator.window(
        id: String,
        primary: Boolean,
    ) = apply { register(id, primary) { record("unused") } }

    /** Waits until [thread] is blocked on a monitor inside the coordinator, or has finished. */
    private fun awaitBlockedInCoordinatorOrDone(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

        fun blockedInCoordinator() =
            thread.state == Thread.State.BLOCKED &&
                thread.stackTrace.any { it.className == LastSessionCoordinator::class.java.name }
        while (thread.state != Thread.State.TERMINATED && !blockedInCoordinator()) {
            check(System.nanoTime() < deadline) { "the shutdown write neither waited nor finished" }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `the owner keeps the set as fresh as the record, so a kill restores the session that died`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)

            // Session 2 restores, works in both Spaces, and its primary window's watcher records
            // it - then the process is killed, so no shutdown write happens.
            val second = session(dir)
            val wrote =
                writeInSessionRecovery(
                    windowId = "primary",
                    record = record(tab = "new a"),
                    set = { set("b" to "new b", "a" to "new a") },
                    coordinator = wired(second).window("primary", primary = true),
                    manager = second,
                )
            assertTrue(wrote)

            // Session 3 restores session 2's Spaces, both of them, not session 1's.
            val third = session(dir)
            assertEquals(listOf("new b", "new a"), titles(third.loadLastSessionSet()?.takeIf { isRestorable(it) }))
            assertEquals("new a", recordedTitle(third))
        }

    @Test
    fun `a secondary window writes neither recovery file`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)
            val manager = session(dir)

            val wrote =
                writeInSessionRecovery(
                    windowId = "secondary",
                    record = record(tab = "secondary layout"),
                    set = { error("a window that does not own the record must not even build a set") },
                    coordinator = wired(manager).window("primary", primary = true).window("secondary", primary = false),
                    manager = manager,
                )

            assertFalse(wrote)
            val next = session(dir)
            assertEquals(listOf("old b", "old a"), titles(next.loadLastSessionSet()))
            assertEquals("old a", recordedTitle(next))
        }

    @Test
    fun `once the primary has closed, the window a shutdown would write for owns the record`() =
        runBlocking<Unit> {
            val dir = directory()
            val manager = session(dir)
            val coordinator = wired(manager).window("primary", primary = true).window("secondary", primary = false)
            assertFalse(coordinator.onWindowDisposed("primary"), "a close with another window open writes nothing")

            assertTrue(coordinator.ownsSessionRecord("secondary"))
            assertTrue(writeInSessionRecovery("secondary", record("second"), { null }, coordinator, manager))
            assertEquals("second", recordedTitle(session(dir)))
        }

    @Test
    fun `nobody writes while a live window is protecting a refused restore`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)
            val manager = session(dir)
            val coordinator = wired(manager)
            coordinator.register("primary", isFirstWindow = true, canSave = { false }) { record("unused") }
            coordinator.window("secondary", primary = false)

            for (window in listOf("primary", "secondary")) {
                assertFalse(coordinator.ownsSessionRecord(window))
                val wrote =
                    writeInSessionRecovery(
                        windowId = window,
                        record = record(tab = "$window layout"),
                        set = { error("no window may build a set while the restore is protected") },
                        coordinator = coordinator,
                        manager = manager,
                    )
                assertFalse(wrote)
            }

            val next = session(dir)
            assertEquals(listOf("old b", "old a"), titles(next.loadLastSessionSet()))
            assertEquals("old a", recordedTitle(next))
        }

    @Test
    fun `a window no longer running two Spaces removes the earlier set instead of letting it outrank the record`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)
            val manager = session(dir)

            val coordinator = wired(manager).window("primary", primary = true)
            writeInSessionRecovery("primary", record("only a"), { null }, coordinator, manager)

            val next = session(dir)
            assertNull(next.loadLastSessionSet())
            assertEquals("only a", recordedTitle(next))
        }

    @Test
    fun `closing the window part-way through the pair still lands both files, the set first`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)
            val manager = session(dir)
            val gate = Gate(held = true)
            val coordinator = wired(manager, gate).window("primary", primary = true)

            // The window closes while its watcher is writing: Compose cancels the watcher before
            // the coordinator hears of the close, so the cancellation lands between the two files.
            val watcher =
                launch(Dispatchers.Default) {
                    val live = set("b" to "new b", "a" to "new a")
                    writeInSessionRecovery("primary", record("new a"), { live }, coordinator, manager)
                }
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "the watcher never started its pair")
            watcher.cancel()
            gate.release.countDown()
            watcher.join()

            // Both halves landed. The set went first, so even a kill between the two would have
            // left the file restore prefers fresh, rather than a fresh record beside a stale set.
            assertEquals(listOf("set", "record"), gate.order)
            val next = session(dir)
            assertEquals(listOf("new b", "new a"), titles(next.loadLastSessionSet()))
            assertEquals("new a", recordedTitle(next))
        }

    @Test
    fun `an in-session write that arrives after the shutdown write adds nothing`() =
        runBlocking<Unit> {
            val dir = directory()
            val manager = session(dir)
            val coordinator = wired(manager)
            // The user closed a Space and quit, so the shutdown write deletes the set.
            coordinator.register("primary", isFirstWindow = true, extractSet = { null }) { record("at exit") }
            assertTrue(coordinator.saveOnProcessExit())

            val live = set("b" to "late b", "a" to "late a")
            val wrote = writeInSessionRecovery("primary", record("late"), { live }, coordinator, manager)

            assertFalse(wrote)
            val next = session(dir)
            assertNull(next.loadLastSessionSet(), "the set the shutdown deleted must stay deleted")
            assertEquals("at exit", recordedTitle(next))
        }

    @Test
    fun `a shutdown that arrives mid-pair waits for it, then writes over it`() =
        runBlocking<Unit> {
            val dir = directory()
            val manager = session(dir)
            val gate = Gate(held = true)
            val coordinator = wired(manager, gate)
            coordinator.register("primary", isFirstWindow = true, extractSet = { null }) { record("at exit") }

            val watcher =
                launch(Dispatchers.Default) {
                    val live = set("b" to "settled b", "a" to "settled a")
                    writeInSessionRecovery("primary", record("settled"), { live }, coordinator, manager)
                }
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "the watcher never started its pair")

            // Cmd+Q now: the shutdown hook runs on its own thread while the watcher is mid-pair.
            val hookWrote = AtomicBoolean(false)
            val hook = thread { hookWrote.set(coordinator.saveOnProcessExit()) }
            awaitBlockedInCoordinatorOrDone(hook)
            gate.release.countDown()
            watcher.join()
            hook.join()

            assertTrue(hookWrote.get())
            assertEquals(listOf("set", "record", "shutdown record", "set"), gate.order)
            val next = session(dir)
            assertNull(next.loadLastSessionSet(), "the watcher's set must not land after the shutdown deleted it")
            assertEquals("at exit", recordedTitle(next))
        }

    @Test
    fun `a write that throws is logged and answered false, and the watcher's next write still lands`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)
            val manager = session(dir)
            val failures = AtomicInteger(1)
            val coordinator =
                LastSessionCoordinator(
                    save = { manager.saveLastSessionBlocking(it) },
                    saveSet = {
                        // The reachable throw: the set's serialization runs outside the file write's catch.
                        check(failures.getAndDecrement() <= 0) { "serialization failed" }
                        manager.saveLastSessionSetBlocking(it)
                    },
                    saveRecord = { manager.writeLastSessionRecordBlocking(it) },
                ).window("primary", primary = true)
            val live = set("b" to "new b", "a" to "new a")

            // Answered, not thrown: a throw would end the window's layout watcher for good.
            assertFalse(writeInSessionRecovery("primary", record("lost"), { live }, coordinator, manager))
            assertTrue(writeInSessionRecovery("primary", record("new a"), { live }, coordinator, manager))

            val next = session(dir)
            assertEquals(listOf("new b", "new a"), titles(next.loadLastSessionSet()))
            assertEquals("new a", recordedTitle(next))
        }

    @Test
    fun `a set that cannot be built skips the write instead of ending the watcher`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)
            val manager = session(dir)
            val coordinator = wired(manager).window("primary", primary = true)

            val unreadable = { error("live state unreadable") }
            val wrote = writeInSessionRecovery("primary", record("unused"), unreadable, coordinator, manager)

            // Nothing half-written: no record without the set it belongs with.
            assertFalse(wrote)
            val next = session(dir)
            assertEquals(listOf("old b", "old a"), titles(next.loadLastSessionSet()))
            assertEquals("old a", recordedTitle(next))
        }

    @Test
    fun `a close that lands before the write is dispatched still records the window's last change`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)
            val manager = session(dir)
            val coordinator = wired(manager).window("primary", primary = true)

            // The window closes after its watcher has built the set and before the pair is
            // dispatched to IO: the one moment a cancel can reach this write at all.
            lateinit var watcher: Job
            watcher =
                launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    val live = set("b" to "new b", "a" to "new a")
                    val build = {
                        watcher.cancel()
                        live
                    }
                    writeInSessionRecovery("primary", record("new a"), build, coordinator, manager)
                }
            watcher.start()
            watcher.join()

            val next = session(dir)
            assertEquals(listOf("new b", "new a"), titles(next.loadLastSessionSet()))
            assertEquals("new a", recordedTitle(next))
        }

    @Test
    fun `a write whose window closed while it waited for the lock is refused`() =
        runBlocking<Unit> {
            val dir = directory()
            val manager = session(dir)
            val gate = Gate(held = true)
            val coordinator =
                wired(manager, gate).window("primary", primary = true).window("secondary", primary = false)

            // The primary's first write holds the lock, mid-pair.
            val first =
                launch(Dispatchers.Default) {
                    writeInSessionRecovery("primary", record("first"), { null }, coordinator, manager)
                }
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "the first write never started")
            // Its next settle passes the ownership check, since the primary is still open, and builds
            // its set.
            val built = CountDownLatch(1)
            val second =
                async(Dispatchers.Default) {
                    val build = {
                        built.countDown()
                        null
                    }
                    writeInSessionRecovery("primary", record("second"), build, coordinator, manager)
                }
            assertTrue(built.await(10, TimeUnit.SECONDS), "the second write never passed the ownership check")

            // The primary closes before the second write gets the lock; the secondary owns the record now.
            assertFalse(coordinator.onWindowDisposed("primary"))
            gate.release.countDown()
            first.join()

            assertFalse(second.await(), "a write for a window that has closed must not land")
            assertEquals("first", recordedTitle(session(dir)))
        }
}
