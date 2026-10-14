package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Space file that carries no `id` must still get one - once - and keep it.
 *
 * `LayoutWorkspace.id` defaults to "", and hand-written or agent-authored JSON
 * commonly omits the field. Blank resolved through
 * `WorkspaceFileManagerCommon.fileNameForId` to the literal file `.json`, so a
 * second id-less import atomically destroyed the first. The load scan's answer
 * - mint a timestamp id every launch - was the other half of the bug: the same
 * file got a new identity each time, so session-set ids and preserved-state
 * keys recorded under it never matched again.
 *
 * `WorkspaceManager` itself cannot be driven from a test (its writes run on a
 * `Dispatchers.Main` scope), so this exercises the two pieces it now calls -
 * [withStableId] and [WorkspaceFileManagerCommon.fileNameForId] - either side
 * of a real [WorkspaceFileManager] on a temp directory, exactly as the import
 * and load paths do.
 */
class IdlessWorkspaceImportTest {
    private fun layout(vararg titles: String) =
        SplitConfig.SinglePanel(
            PanelConfig(id = "main", tabs = titles.map { TabConfig(type = "terminal", title = it) }),
        )

    private fun space(
        id: String,
        name: String,
        layout: SplitConfig,
    ) = LayoutWorkspace(id = id, name = name, description = "d", layout = layout, timestamp = 1_000)

    /** The shape a hand-written file takes: an `id` key that is absent, not empty. */
    private fun idlessJson(
        name: String,
        layout: SplitConfig,
    ): String {
        val serialized = WorkspaceSerializer.serialize(space("", name, layout))
        val obj = Json.parseToJsonElement(serialized).jsonObject
        return JsonObject(obj - "id").toString()
    }

    private fun tempFileManager(): Pair<WorkspaceFileManager, File> {
        val dir = Files.createTempDirectory("idless-workspace-import").toFile()
        return WorkspaceFileManager(dir.absolutePath) to dir
    }

    /** Everything the manager's load scan would read back off disk. */
    private fun reload(fileManager: WorkspaceFileManager): List<LayoutWorkspace> =
        runBlocking {
            fileManager
                .listWorkspaces()
                .mapNotNull { fileManager.loadWorkspace(it.fileName) }
        }

    @Test
    fun `two id-less imports persist as two distinct files`() {
        val (fileManager, dir) = tempFileManager()

        // What importWorkspace does with each file: deserialize, mint an id for a blank one,
        // then save under that id.
        val first = WorkspaceSerializer.deserialize(idlessJson("First", layout("a"))).withStableId()
        val second = WorkspaceSerializer.deserialize(idlessJson("Second", layout("b"))).withStableId()

        assertNotEquals(first.id, second.id, "two id-less imports must not mint the same identity")
        assertNotNull(fileManager.saveWorkspaceBlocking(first, WorkspaceFileManagerCommon.fileNameForId(first.id)))
        assertNotNull(fileManager.saveWorkspaceBlocking(second, WorkspaceFileManagerCommon.fileNameForId(second.id)))

        val files = runBlocking { fileManager.listWorkspaces() }.map { it.fileName }.toSet()
        assertEquals(
            setOf(
                WorkspaceFileManagerCommon.fileNameForId(first.id),
                WorkspaceFileManagerCommon.fileNameForId(second.id),
            ),
            files,
            "each import is its own file - no shared .json",
        )
        dir.deleteRecursively()
    }

    @Test
    fun `a minted id survives a reload instead of being re-minted`() {
        val (fileManager, dir) = tempFileManager()
        val imported = WorkspaceSerializer.deserialize(idlessJson("Imported", layout("x"))).withStableId()
        fileManager.saveWorkspaceBlocking(imported, WorkspaceFileManagerCommon.fileNameForId(imported.id))

        // The second half of the round trip: the load scan reads the id back out of the file,
        // so withStableId leaves it alone rather than minting again.
        val reloaded = reload(fileManager).single()

        assertEquals(imported.id, reloaded.id, "the id written at import is the id a relaunch sees")
        assertTrue(reloaded.id.isNotBlank())
        assertEquals(imported, reloaded.withStableId(), "a reload must not move the identity again")
        dir.deleteRecursively()
    }

    @Test
    fun `the file literally named dot-json is never written but a legacy one is adopted`() {
        val (fileManager, dir) = tempFileManager()
        val idless = space("", "Idless", layout("x"))

        // Neither the derived name nor an explicit one may produce it.
        assertFailsWith<IllegalArgumentException> { WorkspaceFileManagerCommon.fileNameForId("") }
        assertFailsWith<IllegalArgumentException> { WorkspaceFileManagerCommon.fileNameForId("   ") }
        assertNull(fileManager.saveWorkspaceBlocking(idless, ".json"))
        assertFalse(File(dir, ".json").exists())

        // One left behind by an older build IS listed - that file is a real Space (the last
        // id-less import), and filtering it out would orphan it silently. The load scan's
        // adoption, reproduced here: mint a stable id, save under <id>.json, remove the
        // nameless file.
        File(dir, ".json").writeText(WorkspaceSerializer.serialize(idless))
        val listed = runBlocking { fileManager.listWorkspaces() }.single { it.fileName == ".json" }
        val legacy = runBlocking { fileManager.loadWorkspace(listed.fileName) }!!.withStableId()
        val adoptedName = WorkspaceFileManagerCommon.fileNameForId(legacy.id)
        assertNotNull(fileManager.saveWorkspaceBlocking(legacy, adoptedName))
        assertTrue(runBlocking { fileManager.deleteWorkspace(".json") })

        val after = reload(fileManager)
        assertEquals(listOf(legacy.id), after.map { it.id }, "the adopted Space survives under its minted id")
        assertFalse(File(dir, ".json").exists(), "the nameless file is gone after adoption")
        dir.deleteRecursively()
    }

    @Test
    fun `the random suffix, not the clock, keeps same-millisecond mints distinct`() {
        val ids = (1..1000).map { mintWorkspaceId() }.toSet()
        assertEquals(1000, ids.size, "every mint is unique even inside one millisecond")
        assertTrue(ids.none { it.contains("--") }, "no negative-hex double hyphen")
    }
}
