package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.pathutils.BossDirectories
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceStateRootTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `default workspace storage is inside the portable state root`() {
        val directory = File(WorkspaceFileManager().getDefaultWorkspaceDirectory()).canonicalFile

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
