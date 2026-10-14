package ai.rever.boss.components.workspaces

import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four recovered Spaces come back under their own names, and nothing on disk is touched.
 *
 * **Driven against a COPY of a real directory.** The fixture is
 * `.claude/jobs/ea6c8e94/tmp/workspaces-copy`, taken from `~/Documents/BOSS/workspaces`, which
 * holds the four files the old auto-save left behind (`Browser_Only`, `Claude_Code`,
 * `Code_Review`, `Gemini` - all carrying a shipped layout's id) plus both session records. The
 * real directory is never read here and never written anywhere.
 *
 * This is the check that has to hold before the naming suffix can go: it is end to end across the
 * seam where both hand-found bugs of this feature lived, load then save then load, rather than a
 * unit test on either half. It skips itself when the fixture is absent, so CI stays green - what
 * it guards is a migration decision, verified on the machine that has the data.
 */
class RecoveredSpacesRoundTripTest {
    private val fixture =
        File(System.getProperty("user.home"), ".claude/jobs/ea6c8e94/tmp/workspaces-copy")

    private fun sums(dir: File): Map<String, String> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .associate { file ->
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                file.name to digest.joinToString("") { "%02x".format(it) }
            }

    /** Every Space the manager's load scan would produce, in scan order, with its file. */
    private fun scan(fileManager: WorkspaceFileManager): List<Pair<String, LayoutWorkspace>> =
        runBlocking {
            fileManager
                .listWorkspaces()
                .filterNot { it.fileName == LAST_SESSION_SET_FILE }
                .mapNotNull { info -> fileManager.loadWorkspace(info.fileName)?.let { info.fileName to it } }
        }

    @Test
    fun `the recovered Spaces load under their own names and the shipped layouts are untouched`() {
        if (!fixture.isDirectory) return
        val before = sums(fixture)
        val fileManager = WorkspaceFileManager(fixture.absolutePath)

        val scanned = scan(fileManager)
        val merged = mergeSavedWorkspaces(PredefinedWorkspaces.allWorkspaces, scanned.map { it.second })

        // The four, by the name a person reads. No suffix: the shipped layout of the same name is
        // in the picker's Templates section, which is where the distinction belongs.
        assertEquals(
            listOf("Code Review", "Browser Only", "Claude Code", "Gemini"),
            merged.filter { it.id.endsWith("-saved") }.map { it.name }.sortedBy { name ->
                listOf("Code Review", "Browser Only", "Claude Code", "Gemini").indexOf(name)
            },
            "the recovered Spaces keep the names their files carry, got ${merged.map { it.name }}",
        )

        // The eight shipped layouts, byte for byte what the app ships.
        PredefinedWorkspaces.allWorkspaces.forEach { shipped ->
            assertEquals(shipped, merged.single { it.id == shipped.id }, "${shipped.name} must be pristine")
        }

        // And the record still loads as the record.
        assertEquals(1, merged.count { it.id == LAST_SESSION_ID }, "the session record is one Space")

        assertEquals(before, sums(fixture), "reading a directory must not write to it")
    }

    /**
     * The other half of the seam: a save lands on the file the Space came FROM, not on a new one
     * derived from its name, and a reload reads back what was written.
     */
    @Test
    fun `saving a recovered Space writes the file it was loaded from`() {
        if (!fixture.isDirectory) return
        val work = File(fixture.parentFile, "workspaces-roundtrip")
        work.deleteRecursively()
        fixture.copyRecursively(work)
        val fileManager = WorkspaceFileManager(work.absolutePath)

        val (legacyFile, loaded) = scan(fileManager).single { it.first == "Gemini.json" }
        assertEquals("workspace-gemini", loaded.id, "the fixture's Gemini carries the shipped id")

        // What the manager does: the legacy path is remembered from the scan, so the Space keeps
        // saving into it rather than into `<id>.json`.
        val edited = loaded.copy(name = "Gemini", description = "edited", timestamp = 9_000)
        assertTrue(fileManager.saveWorkspaceBlocking(edited, legacyFile) != null)

        val fileNames = scan(fileManager).map { it.first }
        assertEquals(
            listOf("Browser_Only.json", "Claude_Code.json", "Code_Review.json", "Gemini.json", "Last_Session.json"),
            fileNames.sorted(),
            "no second file: an upgrade renames and creates nothing, got $fileNames",
        )
        assertEquals("edited", scan(fileManager).single { it.first == "Gemini.json" }.second.description)

        work.deleteRecursively()
    }
}
