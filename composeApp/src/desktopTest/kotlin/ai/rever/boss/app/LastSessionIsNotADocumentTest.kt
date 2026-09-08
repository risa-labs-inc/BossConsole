package ai.rever.boss.app

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LAST_SESSION_NAME
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.asLastSession
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.isSpaceSlot
import ai.rever.boss.components.workspaces.isUnsaved
import ai.rever.boss.components.workspaces.isUserOwnedSpace
import ai.rever.boss.components.workspaces.layoutWatcherWrite
import ai.rever.boss.components.workspaces.savedCopyOfSlot
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * That work built in Last Session reads as UNSAVED, and stays that way.
 *
 * **The default state, not an edge case.** Every launch restores Last Session as the current Space
 * (`BossAppStartupEffects` finds the record by [LAST_SESSION_NAME] and loads it), so suppressing
 * the mark there meant the save affordance could never appear on a fresh launch - and the mark
 * cleared itself within the settle window, which is exactly the defect already fixed once for named
 * Spaces, surviving in the one place it is most visible.
 *
 * **The second clause of the headline test is the whole bug.** A test that checks the instant after
 * a tab is added passes against the old code too: `isUnsaved` was briefly true, and then the
 * watcher rewrote the record and it went false again. So the sequence here runs the watcher's write
 * and asks again afterwards.
 */
class LastSessionIsNotADocumentTest {
    private class StubComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = CodeEditorTabType

        @Composable
        override fun Content() {
        }
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(CodeEditorTabType) { config, ctx -> StubComponent(ctx, config) }
        }

    @AfterTest
    fun tearDown() = TabUpdateRegistry.clear()

    private fun newState() = SplitViewState(tabRegistry, windowId = "last-session-doc")

    private fun editor(name: String) =
        EditorTabInfo(
            id = "editor-$name",
            typeId = CodeEditorTabType.typeId,
            title = name,
            filePath = "/tmp/$name",
        )

    /**
     * The workspace directory, keyed the way `WorkspaceManager` keys a write: by **ID**.
     *
     * It was by NAME, which is what the path used to be derived from. That model is wrong now -
     * `WorkspaceFileManagerCommon.fileNameForId` - and keeping it would have quietly made these
     * tests assert against a disk the app does not have.
     */
    private fun disk(record: LayoutWorkspace) = mutableMapOf(record.id to record)

    /**
     * What the window holds after a launch: the record restored as the current Space, and the same
     * layout on screen.
     */
    private fun restoredWindow(): Triple<SplitViewState, LayoutWorkspace, MutableMap<String, LayoutWorkspace>> {
        val state = newState()
        state.getPanel("main")!!.tabsComponent.addTab(editor("restored"))
        val record = asLastSession(extractCurrentWorkspace(state, projectPath = PROJECT)).copy(timestamp = 1_000)
        return Triple(state, record, disk(record))
    }

    /** The manager's per-window set, recomputed the way `reportUnsaved` recomputes it. */
    private fun unsavedIds(
        live: LayoutWorkspace,
        current: LayoutWorkspace,
        disk: Map<String, LayoutWorkspace>,
    ): Set<String> = if (isUnsaved(live, disk[current.id])) setOf(current.id) else emptySet()

    // ==================== the headline ====================

    /**
     * The test that fails before this change, and only because of its second half.
     */
    @Test
    fun `on Last Session a tab added reads unsaved, and still does after the watcher's interval`() {
        val (state, record, disk) = restoredWindow()

        // Nothing has been touched yet: the record really does match the screen.
        val atRestore = extractCurrentWorkspace(state, projectPath = PROJECT)
        assertFalse(isUnsaved(atRestore, disk[record.id]), "the FILE matches, which is what fooled the old rule")

        state.getPanel("main")!!.tabsComponent.addTab(editor("added"))
        val live = extractCurrentWorkspace(state, projectPath = PROJECT)

        assertTrue(
            spaceIsUnsaved(record.id, unsavedIds(live, record, disk)),
            "adding a tab in Last Session is unsaved work",
        )

        // Now the watcher settles and rewrites the record, which is what used to clear the mark.
        val write = layoutWatcherWrite(current = record, live = live, now = 2_000)
        disk[write.record.id] = write.record
        assertFalse(
            isUnsaved(live, disk[record.id]),
            "the record now matches the screen again - the manager is right about the file",
        )

        assertTrue(
            spaceIsUnsaved(record.id, unsavedIds(live, record, disk)),
            "and the mark must STILL be there: the record is a slot, not a document",
        )
    }

    @Test
    fun `Last Session reads unsaved even before anything is touched`() {
        // Unconditional, and stable: it stays until the user saves. The earlier argument against
        // lighting a control on open was about a mark that appears and vanishes by itself.
        val (_, record, disk) = restoredWindow()

        assertTrue(spaceIsUnsaved(record.id, emptySet()))
        assertTrue(spaceIsUnsaved(LAST_SESSION_ID, emptySet()))
        assertEquals(1, disk.size, "and the record is on disk, which is precisely not the point")
    }

    @Test
    fun `a window with no Space at all still reads saved`() {
        // The real flicker case, unchanged: the watcher writes the first change out as Last
        // Session and the branch above takes over from there.
        assertFalse(spaceIsUnsaved(null, emptySet()))
        assertFalse(spaceIsUnsaved(null, setOf(LAST_SESSION_ID)))
    }

    @Test
    fun `a named Space is still answered from the manager's set`() {
        // The Last Session branch must not swallow the ordinary rule.
        assertTrue(spaceIsUnsaved("workspace-1", setOf("workspace-1")))
        assertFalse(spaceIsUnsaved("workspace-1", emptySet()))
    }

    // ==================== pressing save ====================

    @Test
    fun `saving from Last Session yields a Space with a different id and name`() {
        val (_, record, disk) = restoredWindow()

        val saved =
            savedCopyOfSlot(
                current = record,
                id = "workspace-1788000000000",
                now = 1_788_000_000_000,
                takenNames = disk.keys,
            )

        assertNotEquals(LAST_SESSION_ID, saved.id, "a slot's id must not be reused")
        assertNotEquals(LAST_SESSION_NAME, saved.name, "the record's own name says nothing about the layout")
        assertEquals("Workspace 1788000000", saved.name, "the convention the save path already uses")
        assertEquals(record.layout, saved.layout, "carrying the layout that was on screen")
    }

    /**
     * The reserved-name refusal is GONE, deliberately, and this is what replaced it.
     *
     * It existed because `loadAllWorkspaces` resolved the record BY NAME, so a Space also called
     * "Last Session" made which one restored a matter of scan order. Every one of those lookups is
     * keyed on `LAST_SESSION_ID` now - the restore, the watcher, and both record writers - so a
     * Space merely CALLED that is an ordinary Space, and refusing the name would be refusing a
     * name for no reason.
     */
    @Test
    fun `a Space may now be called Last Session, because the record is resolved by id`() {
        val (_, record, disk) = restoredWindow()

        val saved =
            savedCopyOfSlot(
                current = record,
                id = "workspace-1788000000000",
                now = 1_788_000_000_000,
                takenNames = disk.keys,
                requestedName = LAST_SESSION_NAME,
            )

        assertEquals(LAST_SESSION_NAME, saved.name, "the typed name is honoured")
        assertNotEquals(LAST_SESSION_ID, saved.id, "and it is a document, not the slot")
        assertTrue(isUserOwnedSpace(saved.id))
        assertFalse(isSpaceSlot(saved.id), "so the next save writes IT rather than making another copy")
    }

    @Test
    fun `a name the user typed still wins`() {
        val (_, record, _) = restoredWindow()

        val saved =
            savedCopyOfSlot(
                current = record,
                id = "workspace-1788000000000",
                now = 1_788_000_000_000,
                takenNames = emptySet(),
                requestedName = "Reading",
            )

        assertEquals("Reading", saved.name)
    }

    @Test
    fun `the record and the shipped layouts are the slots, and a saved Space is not`() {
        assertTrue(isSpaceSlot(LAST_SESSION_ID))
        PredefinedWorkspaces.allIds.forEach { assertTrue(isSpaceSlot(it), "$it is a shipped layout") }
        assertFalse(isSpaceSlot("workspace-1788000000000"), "a Space of the user's is a document")
        assertFalse(isSpaceSlot("workspace-gemini-saved"), "and so is an adopted legacy file")
    }

    // ==================== and the record goes on working ====================

    /**
     * The trade this must not make: swapping a cosmetic bug for the loss of crash recovery.
     */
    @Test
    fun `after saving out of Last Session the record is still written, and the Space is a document`() {
        val (state, record, disk) = restoredWindow()
        state.getPanel("main")!!.tabsComponent.addTab(editor("added"))
        val live = extractCurrentWorkspace(state, projectPath = PROJECT)

        val saved = savedCopyOfSlot(record, "workspace-1788000000000", 1_788_000_000_000, disk.keys)
        disk[saved.id] = saved.copy(layout = live.layout)

        // The window is now in a named Space. The watcher keeps going.
        state.getPanel("main")!!.tabsComponent.addTab(editor("later"))
        val afterwards = extractCurrentWorkspace(state, projectPath = PROJECT)
        val write = layoutWatcherWrite(current = saved, live = afterwards, now = 3_000)
        disk[write.record.id] = write.record

        assertEquals(
            afterwards.layout,
            disk[LAST_SESSION_ID]?.layout,
            "the recovery record must still track the screen, or this traded one bug for a worse one",
        )
        assertEquals(saved.id, write.current.id, "and the window stays in its own Space")

        // And that Space is an ordinary document: marked by the manager's set, not unconditionally.
        assertTrue(spaceIsUnsaved(saved.id, unsavedIds(afterwards, saved, disk)), "unsaved, because it changed")
        assertFalse(spaceIsUnsaved(saved.id, emptySet()), "and answerable as saved once it is written")
    }

    private companion object {
        const val PROJECT = "/tmp/last-session-doc"
    }
}
