package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Open from File must not be able to overwrite a reserved workspace-store record (#1590).
 *
 * `importWorkspace` saved a Space under whatever id its JSON carried. The MCP create path refuses
 * a slot id and a reserved file name (#926); the import had neither guard. An id of `Space_Themes`
 * therefore overwrote the theme store, `Last_Session_Set` broke session restore, and
 * `last-session` - what an exported Last Session carries - replaced the crash-recovery record.
 *
 * `WorkspaceManager` cannot be driven from a test (its writes run on a `Dispatchers.Main` scope),
 * so, like `IdlessWorkspaceImportTest`, this runs the identity rule the import now applies either
 * side of a real [WorkspaceFileManager] on a temp directory, exactly as the import does. The last
 * test pins that the import applies it.
 *
 * The #1627 save-site gate is different: it fires synchronously, before the Main-scope write, on
 * the file name `fileNameFor` resolved - including a name the load scan recorded into
 * `loadedFileNames`, which `withImportableId`'s id check never consulted. A test Main (the
 * `SaveRebindSeamTest` pattern) drives the real manager far enough to see both sides of it.
 */
class ImportReservedSpaceIdTest {
    private val dir: File = Files.createTempDirectory("import-reserved-space-id").toFile()
    private val fileManager = WorkspaceFileManager(dir.absolutePath)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val layout =
        SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = listOf(TabConfig(type = "terminal", title = "t"))))

    private fun imported(id: String): LayoutWorkspace {
        val json =
            WorkspaceSerializer.serialize(
                LayoutWorkspace(id = id, name = "Shared layout", description = "d", layout = layout),
            )
        // What importWorkspace does: deserialize, then fix the identity.
        return WorkspaceSerializer.deserialize(json).withImportableId()
    }

    @Test
    fun `importing a file that carries the theme store's id leaves the theme store untouched`() {
        val themes = File(dir, SPACE_THEMES_FILE).apply { writeText("""{"the":"theme store"}""") }

        val space = imported("Space_Themes")
        fileManager.saveWorkspaceBlocking(space, WorkspaceFileManagerCommon.fileNameForId(space.id))

        assertEquals("""{"the":"theme store"}""", themes.readText(), "the theme store must be byte-identical")
        assertNotEquals("Space_Themes", space.id, "the import gets a fresh identity instead")
        assertEquals("Shared layout", space.name, "and keeps everything the person actually asked for")
    }

    @Test
    fun `every slot and reserved-record id is re-minted, in any case`() {
        listOf(
            "last-session",
            "Last-Session",
            "LAST-SESSION",
            "Last_Session",
            "Last_Session_Set",
            "space_themes",
            "Space_Themes.json",
            PredefinedWorkspaces.CLAUDE_CODE_ID,
            PredefinedWorkspaces.CLAUDE_CODE_ID.uppercase(),
        ).forEach { reserved ->
            val space = imported(reserved)
            assertNotEquals(reserved, space.id, "'$reserved' must not be imported under its own id")
            assertFalse(isSpaceSlot(space.id), "'$reserved' re-minted to a slot: ${space.id}")
            assertNull(reservedWorkspaceStoreFileName(space.id), "'$reserved' re-minted to a reserved file")
        }
    }

    @Test
    fun `an ordinary id is kept exactly, and a missing one is still minted`() {
        assertEquals("workspace-1788000000000", imported("workspace-1788000000000").id)
        assertTrue(imported("").id.startsWith("workspace-"), "the id-less rule still applies")
    }

    /**
     * The rule is the IMPORT's, not the scan's. The real session record carries `last-session`
     * legitimately, and the load scan fixes identities through withStableId; if this rule were
     * folded into that, every launch would re-mint the record and orphan it.
     */
    @Test
    fun `the load scan's identity rule leaves the real session record alone`() {
        val record = LayoutWorkspace(id = LAST_SESSION_ID, name = "Last Session", description = "d", layout = layout)

        assertEquals(LAST_SESSION_ID, record.withStableId().id)
    }

    @Test
    fun `a slot is recognised whatever its case`() {
        assertTrue(isSpaceSlot("Last-Session"), "on APFS and NTFS this IS last-session.json")
        assertTrue(isSpaceSlot("LAST-SESSION"))
        assertTrue(isSpaceSlot(PredefinedWorkspaces.CLAUDE_CODE_ID.uppercase()))
        assertFalse(isSpaceSlot("workspace-1788000000000"), "a Space of the user's is still a document")
    }

    /** The import has to apply the rule; the tests above would pass if it went back to withStableId. */
    @Test
    fun `importWorkspace applies the import rule, not the scan's`() {
        val relative = "composeApp/src/commonMain/kotlin/ai/rever/boss/components/workspaces/WorkspaceManager.kt"
        val source =
            checkNotNull(
                generateSequence(File(".").absoluteFile) { it.parentFile }
                    .map { File(it, relative) }
                    .firstOrNull { it.isFile }
                    ?.readText(),
            ) { "could not find $relative" }
        val importBody = source.substringAfter("fun importWorkspace(").substringBefore("\n    fun ")

        assertTrue("withImportableId()" in importBody, "importWorkspace must fix the identity with withImportableId")
        assertTrue("uniqueWorkspaceName(" in importBody, "importWorkspace must ensure unique name")
    }

    @Test
    fun `whitespace-only id returns null without throwing`() {
        assertNull(reservedWorkspaceStoreFileName("   "))
        assertNull(reservedWorkspaceStoreFileName("   .json"))
        val space = imported("   ")
        assertTrue(space.id.startsWith("workspace-"), "blank id is re-minted to a stable id")
    }

    @Test
    fun `uniqueWorkspaceName ensures imported name does not duplicate existing Space names`() {
        val taken = setOf("Last Session", "Dev Space", "Dev Space 2")
        assertEquals("Last Session 2", uniqueWorkspaceName("Last Session", taken))
        assertEquals("Dev Space 3", uniqueWorkspaceName("Dev Space", taken))
        assertEquals("New Space", uniqueWorkspaceName("New Space", taken))
    }

    /**
     * Drive the test Main until [predicate] holds, waiting real time for the manager's IO write
     * to land in between. Bounded so a wedged write fails the test instead of hanging it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!predicate()) {
            assertTrue(
                System.currentTimeMillis() < deadline,
                "the manager's Main-scope work did not land within 15 seconds",
            )
            Thread.sleep(20)
            runCurrent()
        }
    }

    /**
     * The gate #1627 asks for, driven through the real manager. `withImportableId` checks the id
     * against `fileNameForId`, but `fileNameFor` answers a name the load scan recorded into
     * `loadedFileNames` first - a file on disk named like a session record whose contents carry
     * an ordinary id passes the identity rule and then saves over the record. The refusal lands
     * before the write is even queued, so the record is byte-identical no matter when Main runs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `import resolved onto a recorded reserved file name is refused before the write`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                // A Space file at the legacy session-record spelling, but carrying an ordinary
                // id: the scan skips only the DOCUMENT records, so it loads as a Space and is
                // recorded - loadedFileNames["victim-space"] = "Last_Session.json".
                val sessionFile = File(dir, LEGACY_LAST_SESSION_FILE)
                sessionFile.writeText(
                    WorkspaceSerializer.serialize(
                        LayoutWorkspace(id = "victim-space", name = "Victim", description = "d", layout = layout),
                    ),
                )
                val untouched = sessionFile.readText()
                val manager = WorkspaceManager(fileManager)
                awaitMain { manager.workspaces.value.any { it.id == "victim-space" } }

                val document =
                    WorkspaceSerializer.serialize(
                        LayoutWorkspace(id = "victim-space", name = "Re-imported", description = "d", layout = layout),
                    )

                assertNull(
                    manager.importWorkspace(document),
                    "the write target resolves to a session record - the import must be refused",
                )
                Thread.sleep(50)
                runCurrent()
                assertEquals(untouched, sessionFile.readText(), "the reserved record is never written")
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * The healthy path through the same site: an id that spells a reserved record is still
     * re-minted by `withImportableId`, and the save then lands under the minted file - the gate
     * refuses the reserved name, not the import.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `import carrying a reserved id saves under the minted name, never the record`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val manager = WorkspaceManager(fileManager)
                // Drain the constructor's load before importing: its unconditional list replace
                // would otherwise drop the imported row if it landed after the save commits it.
                awaitMain { manager.workspaces.value.size >= PredefinedWorkspaces.allWorkspaces.size }

                val document =
                    WorkspaceSerializer.serialize(
                        LayoutWorkspace(id = "Last_Session_Set", name = "Mine", description = "d", layout = layout),
                    )
                val imported = manager.importWorkspace(document)

                assertNotNull(imported, "a reserved id re-mints; the import itself still succeeds")
                assertNotEquals("Last_Session_Set", imported.id)
                awaitMain { File(dir, WorkspaceFileManagerCommon.fileNameForId(imported.id)).exists() }
                assertFalse(
                    File(dir, LAST_SESSION_SET_FILE).exists(),
                    "the session-set record was never written",
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** The import must consult the reserved gate on the write target, before the save. */
    @Test
    fun `importWorkspace gates the resolved file name before saving`() {
        val relative = "composeApp/src/commonMain/kotlin/ai/rever/boss/components/workspaces/WorkspaceManager.kt"
        val source =
            checkNotNull(
                generateSequence(File(".").absoluteFile) { it.parentFile }
                    .map { File(it, relative) }
                    .firstOrNull { it.isFile }
                    ?.readText(),
            ) { "could not find $relative" }
        val importBody = source.substringAfter("fun importWorkspace(").substringBefore("\n    fun ")
        val gate = importBody.indexOf("reservedWorkspaceStoreFileName(")
        val save = importBody.indexOf("fileManager.saveWorkspace(")

        assertTrue(gate >= 0, "importWorkspace must check the write target against the reserved store files")
        assertTrue(gate < save, "the reserved-file check must run before the save it protects")
    }
}
