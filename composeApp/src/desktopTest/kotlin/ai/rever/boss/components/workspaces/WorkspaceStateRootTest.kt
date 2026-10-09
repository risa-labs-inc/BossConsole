package ai.rever.boss.components.workspaces

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
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
        val stateRoot = temporaryDirectory.resolve("portable-root")
        val directory = workspaceStateDirectory { relative -> stateRoot.resolve(relative).toFile() }.canonicalFile

        assertEquals(stateRoot.resolve("workspaces").toFile().canonicalFile, directory)
    }

    @Test
    fun `legacy migration copies records without overwriting state-root data`() {
        val legacy = temporaryDirectory.resolve("legacy").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state").toFile()
        File(legacy, "existing.json").writeText("legacy")
        File(legacy, "missing.json").writeText("copy me")
        File(legacy, "note.txt").writeText("durable workspace document")
        state.mkdirs()
        File(state, "existing.json").writeText("current")

        migrateLegacyWorkspaceDirectory(legacy, state)

        assertEquals("current", File(state, "existing.json").readText())
        assertEquals("copy me", File(state, "missing.json").readText())
        assertEquals("durable workspace document", File(state, "note.txt").readText())
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
    fun `current-state writer winning publish race is never overwritten by legacy data`() {
        val legacy = temporaryDirectory.resolve("legacy-race").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state-race").toFile()
        File(legacy, "space.json").writeText("legacy record")

        migrateLegacyWorkspaceDirectory(legacy, state) { from, target ->
            copyLegacyRecordAtomically(from, target) {
                // Deterministically land a current-state write after the migration's pre-check
                // and durable temp copy, at the exact boundary where publication races.
                target.writeText("current record")
            }
        }

        assertEquals("current record", File(state, "space.json").readText())
        assertTrue(File(state, ".legacy-documents-import-complete").isFile)
    }

    @Test
    fun `unsupported hard links fall back without replacing a concurrent target`() {
        val state = temporaryDirectory.resolve("fallback-publish").also { Files.createDirectories(it) }
        val temporary = state.resolve("record.tmp").also { Files.writeString(it, "legacy record") }
        val target = state.resolve("record.json").also { Files.writeString(it, "current record") }

        assertFailsWith<java.nio.file.FileAlreadyExistsException> {
            publishTemporaryNoOverwrite(temporary, target) { _, _ ->
                throw UnsupportedOperationException("simulated no hard-link support")
            }
        }

        assertEquals("current record", Files.readString(target))
        assertTrue(Files.exists(temporary))
    }

    @Test
    fun `unsupported hard links publish a missing target through no-replace move`() {
        val state = temporaryDirectory.resolve("fallback-success").also { Files.createDirectories(it) }
        val temporary = state.resolve("record.tmp").also { Files.writeString(it, "legacy record") }
        val target = state.resolve("record.json")

        publishTemporaryNoOverwrite(temporary, target) { _, _ ->
            throw UnsupportedOperationException("simulated no hard-link support")
        }

        assertEquals("legacy record", Files.readString(target))
        assertFalse(Files.exists(temporary))
    }

    @Test
    fun `directory force failure does not cause deleted records to be resurrected`() {
        val legacy = temporaryDirectory.resolve("legacy-force").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state-force").toFile()
        File(legacy, "deleted.json").writeText("legacy")

        migrateLegacyWorkspaceDirectory(legacy, state, forceDirectory = { throw IOException("unsupported") })
        assertTrue(File(state, "deleted.json").delete())

        migrateLegacyWorkspaceDirectory(legacy, state)

        assertFalse(File(state, "deleted.json").exists())
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
