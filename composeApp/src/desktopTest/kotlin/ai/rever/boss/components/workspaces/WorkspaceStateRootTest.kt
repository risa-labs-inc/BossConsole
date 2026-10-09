package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.pathutils.BossDirectories
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceStateRootTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `default workspace storage is inside the portable state root`() {
        val directory = workspaceStateDirectory().canonicalFile

        assertEquals(BossDirectories.resolve("workspaces"), directory)
        assertTrue(BossDirectories.contains(directory))
    }

    @Test
    fun `legacy migration copies records without overwriting state-root data`() {
        val legacy = temporaryDirectory.resolve("legacy").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state").toFile()
        File(legacy, "existing.json").writeText("legacy")
        File(legacy, "missing.json").writeText("copy me")
        File(legacy, "note.txt").writeText("not durable workspace data")
        state.mkdirs()
        File(state, "existing.json").writeText("current")

        migrateLegacyWorkspaceDirectory(legacy, state)

        assertEquals("current", File(state, "existing.json").readText())
        assertEquals("copy me", File(state, "missing.json").readText())
        assertFalse(File(state, "note.txt").exists())
        assertEquals("legacy", File(legacy, "existing.json").readText())
    }

    @Test
    fun `completed legacy migration never resurrects a deleted workspace`() {
        val legacy = temporaryDirectory.resolve("legacy-once").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state-once").toFile()
        File(legacy, "deleted.json").writeText("legacy")

        migrateLegacyWorkspaceDirectory(legacy, state)
        assertTrue(File(state, "deleted.json").delete())

        migrateLegacyWorkspaceDirectory(legacy, state)

        assertFalse(File(state, "deleted.json").exists())
        assertTrue(File(state, ".legacy-documents-import-complete").isFile)
    }

    @Test
    fun `interrupted legacy copy never publishes a partial record or completion marker`() {
        val legacy = temporaryDirectory.resolve("legacy-interrupted").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state-interrupted").toFile()
        val source = File(legacy, "space.json").apply { writeText("complete legacy record") }

        assertFailsWith<IllegalStateException> {
            migrateLegacyWorkspaceDirectory(legacy, state) { from, target ->
                copyLegacyRecordAtomically(from, target) { error("simulated interruption") }
            }
        }

        assertFalse(File(state, source.name).exists())
        assertFalse(File(state, ".legacy-documents-import-complete").exists())

        migrateLegacyWorkspaceDirectory(legacy, state)
        assertEquals("complete legacy record", File(state, source.name).readText())
        assertTrue(File(state, ".legacy-documents-import-complete").isFile)
    }

    @Test
    fun `legacy migration does not follow a record symlink`() {
        val legacy = temporaryDirectory.resolve("legacy-links").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state-links").toFile()
        val outside = temporaryDirectory.resolve("outside.json").toFile().apply { writeText("outside") }
        val link = legacy.toPath().resolve("linked.json")

        runCatching { Files.createSymbolicLink(link, outside.toPath()) }.getOrElse { return }
        migrateLegacyWorkspaceDirectory(legacy, state)

        assertFalse(File(state, "linked.json").exists())
    }
}
