package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    }
}
