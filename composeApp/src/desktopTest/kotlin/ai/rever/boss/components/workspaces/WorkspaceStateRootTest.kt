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
        File(legacy, "note.txt").writeText("unrelated document")
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

        var failure: Exception? = null
        migrateLegacyWorkspaceDirectory(
            legacy,
            state,
            onFailedRecord = { _, error -> failure = error },
        ) { from, target ->
            copyLegacyRecordAtomically(from, target) { error("simulated interruption") }
        }

        assertEquals("simulated interruption", failure?.message)
        assertFalse(File(state, source.name).exists())
        assertFalse(File(state, ".legacy-documents-import-complete").exists())

        migrateLegacyWorkspaceDirectory(legacy, state)
        assertEquals("complete legacy record", File(state, source.name).readText())
        assertTrue(File(state, ".legacy-documents-import-complete").isFile)
    }

    @Test
    fun `partial migration receipts prevent deleted records from being resurrected on retry`() {
        val legacy = temporaryDirectory.resolve("legacy-partial").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state-partial").toFile()
        File(legacy, "copied.json").writeText("copied")
        File(legacy, "failed.json").writeText("retry me")

        migrateLegacyWorkspaceDirectory(legacy, state) { source, target ->
            if (source.name == "failed.json") throw IOException("simulated failure")
            copyLegacyRecordAtomically(source, target)
        }
        assertTrue(File(state, "copied.json").delete())
        assertFalse(File(state, ".legacy-documents-import-complete").exists())

        migrateLegacyWorkspaceDirectory(legacy, state)

        assertFalse(File(state, "copied.json").exists())
        assertEquals("retry me", File(state, "failed.json").readText())
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
    fun `temporary cleanup failure never invalidates a published record`() {
        val state = temporaryDirectory.resolve("cleanup-published").toFile().apply { mkdirs() }
        val source = temporaryDirectory.resolve("cleanup-source.json").toFile().apply { writeText("legacy") }
        val target = File(state, "record.json")

        copyLegacyRecordAtomically(source, target, deleteTemporary = { throw IOException("denied") })

        assertEquals("legacy", target.readText())
    }

    @Test
    fun `temporary cleanup failure never masks publication failure`() {
        val state = temporaryDirectory.resolve("cleanup-failed").toFile().apply { mkdirs() }
        val source = temporaryDirectory.resolve("cleanup-failed-source.json").toFile().apply { writeText("legacy") }
        val target = File(state, "record.json")

        val failure =
            assertFailsWith<IllegalStateException> {
                copyLegacyRecordAtomically(
                    source,
                    target,
                    beforePublish = { error("publication failed") },
                    deleteTemporary = { throw IOException("cleanup failed") },
                )
            }

        assertEquals("publication failed", failure.message)
        assertFalse(target.exists())
    }

    @Test
    fun `legacy migration does not follow a record symlink`() {
        val legacy = temporaryDirectory.resolve("legacy-links").toFile().apply { mkdirs() }
        val state = temporaryDirectory.resolve("state-links").toFile()
        val outside = temporaryDirectory.resolve("outside.json").toFile().apply { writeText("outside") }
        val link = legacy.toPath().resolve("linked.json")

        runCatching { Files.createSymbolicLink(link, outside.toPath()) }.getOrElse { return }
        val skipped = mutableListOf<String>()
        migrateLegacyWorkspaceDirectory(legacy, state, onSkippedRecord = { skipped += it.name })

        assertFalse(File(state, "linked.json").exists())
        assertEquals(listOf("linked.json"), skipped)
    }
}
