package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the layout watcher writes when the layout changes, and what it must leave alone.
 *
 * The watcher used to write the named Space you were working in every two seconds, which made
 * "unsaved" a state that cleared itself before anyone could press a save button - the feature not
 * working. It writes the Last Session record instead, and a named Space is written by an explicit
 * save alone.
 *
 * **The disk is modelled as a map keyed by NAME, because that is how `WorkspaceManager` keys a
 * write**: it saves to `generateFileName(workspace.name)` and replaces the list entry whose name
 * matches. So "which file did that write land on" is answerable here, which is the whole question -
 * and the answer used to be "Foo.json".
 */
class LayoutWatcherWriteTest {
    private fun layout(vararg titles: String) =
        SplitConfig.SinglePanel(
            PanelConfig(id = "main", tabs = titles.map { TabConfig(type = "terminal", title = it) }),
        )

    /** A named Space as it sits on disk, saved when it held one tab. */
    private val fooOnDisk =
        LayoutWorkspace(
            id = "workspace-foo",
            name = "Foo",
            description = "Saved workspace",
            layout = layout("one"),
            timestamp = 1_000,
            projectPath = "/tmp/proj",
        )

    /** The same window after the user opened a second tab. What `extractCurrentWorkspace` returns. */
    private val live =
        LayoutWorkspace(
            id = "workspace-1788000000000",
            name = "Current",
            description = "Current layout workspace",
            layout = layout("one", "two"),
            timestamp = 1_788_000_000_000,
            projectPath = "/tmp/proj",
        )

    private val lastSessionOnDisk =
        LayoutWorkspace(
            id = LAST_SESSION_ID,
            name = LAST_SESSION_NAME,
            description = "Automatically saved session",
            layout = layout("stale"),
            timestamp = 500,
        )

    /** The workspace directory, keyed by name the way `WorkspaceManager` writes it. */
    private fun disk() =
        mutableMapOf(
            fooOnDisk.name to fooOnDisk,
            lastSessionOnDisk.name to lastSessionOnDisk,
        )

    private fun MutableMap<String, LayoutWorkspace>.applyWrite(record: LayoutWorkspace) {
        this[record.name] = record
    }

    private companion object {
        const val NOW = 1_788_000_000_500
    }

    // ==================== the headline: a named Space stays unsaved ====================

    /**
     * The test that would have failed before this change, and the reason the feature exists. The
     * old watcher's write was `loadedConfig.copy(layout = live.layout, …)`, which lands on
     * `Foo.json` - so the Space read as saved again two seconds after every edit.
     */
    @Test
    fun `a named Space is still unsaved after the watcher has written`() {
        val disk = disk()
        val write = layoutWatcherWrite(current = fooOnDisk, live = live, now = NOW)

        disk.applyWrite(write.record)

        assertEquals(
            fooOnDisk,
            disk["Foo"],
            "the watcher must not touch the file of the Space the user is working in",
        )
        assertTrue(
            isUnsaved(live, disk["Foo"]),
            "so the Space is still unsaved, and the save button is still there to be pressed",
        )
    }

    /**
     * The other half, and what "explicit" means: the expression `BossAppMenuActionEffects` and the
     * vertical bar's save button both go through - the loaded Space carrying the live layout.
     */
    @Test
    fun `an explicit save is what makes a named Space clean`() {
        val disk = disk()

        // What the save path writes: the Space's own identity, the layout that is on screen.
        disk.applyWrite(fooOnDisk.copy(layout = live.layout, timestamp = NOW))

        assertFalse(isUnsaved(live, disk["Foo"]), "an explicit save clears the mark")
    }

    @Test
    fun `the mark survives any number of watcher intervals`() {
        // The watcher fires on every settled change, so once is not the test: the state has to be
        // stable under repetition, which is what "until the user presses save" means.
        val disk = disk()
        repeat(5) { interval ->
            disk.applyWrite(layoutWatcherWrite(fooOnDisk, live, NOW + interval).record)
            assertTrue(isUnsaved(live, disk["Foo"]), "still unsaved after ${interval + 1} intervals")
        }
    }

    // ==================== Last Session is not weakened ====================

    /**
     * Verified specifically, because it is the only thing between an unsaved layout and a crash:
     * the multi-Space set is written at shutdown, which a hard kill never reaches.
     */
    @Test
    fun `the watcher refreshes the Last Session record while the user works in a named Space`() {
        val disk = disk()

        disk.applyWrite(layoutWatcherWrite(fooOnDisk, live, NOW).record)

        val record = assertNotNull(disk[LAST_SESSION_NAME])
        assertEquals(live.layout, record.layout, "the recovery record holds the layout on screen")
        assertEquals(LAST_SESSION_ID, record.id)
        assertEquals(NOW, record.timestamp)
    }

    @Test
    fun `this is stronger than what it replaced, not weaker`() {
        // Before, working in a named Space wrote THAT Space and left the recovery record at
        // whatever it held when the window last had no Space. The stale layout was the exposure.
        val disk = disk()
        assertEquals(layout("stale"), disk[LAST_SESSION_NAME]?.layout)

        disk.applyWrite(layoutWatcherWrite(fooOnDisk, live, NOW).record)

        assertEquals(live.layout, disk[LAST_SESSION_NAME]?.layout)
    }

    @Test
    fun `a window already in Last Session keeps its existing behaviour`() {
        val write = layoutWatcherWrite(current = lastSessionOnDisk, live = live, now = NOW)

        assertEquals(write.record, write.current, "it adopts the record, exactly as it did before")
        assertEquals(LAST_SESSION_ID, write.current.id)
    }

    @Test
    fun `a window with no Space at all adopts Last Session`() {
        // How a fresh window comes to be "in" Last Session, which is also how the fresh-install
        // fallback timeout learns that a restore is under way.
        val write = layoutWatcherWrite(current = null, live = live, now = NOW)

        assertEquals(LAST_SESSION_ID, write.current.id)
        assertEquals(LAST_SESSION_NAME, write.current.name)
        assertEquals(live.layout, write.current.layout)
    }

    // ==================== what the manager should hold ====================

    /**
     * The dependency that is easy to lose with the write. `WorkspaceDataProvider` gives a plugin no
     * way to reach the split tree, so Top of Mind's Save button saves whatever the manager holds as
     * current - which the watcher's `updateCurrentWorkspace` is what keeps live. Dropping that
     * along with the file write would have made that button silently save the layout as of load
     * time.
     */
    @Test
    fun `the current Space keeps its identity and takes the live layout`() {
        val write = layoutWatcherWrite(current = fooOnDisk, live = live, now = NOW)

        assertEquals("workspace-foo", write.current.id, "a named Space is never renamed by the watcher")
        assertEquals("Foo", write.current.name)
        assertEquals(fooOnDisk.description, write.current.description)
        assertEquals(live.layout, write.current.layout, "carrying the layout that is on screen")
        assertEquals(NOW, write.current.timestamp)
    }

    @Test
    fun `the record is Last Session whichever Space is on screen`() {
        listOf(null, fooOnDisk, lastSessionOnDisk).forEach { current ->
            val write = layoutWatcherWrite(current, live, NOW)
            assertEquals(LAST_SESSION_ID, write.record.id, "for current = ${current?.name}")
            assertEquals(LAST_SESSION_NAME, write.record.name)
            assertEquals(live.layout, write.record.layout)
        }
    }
}
