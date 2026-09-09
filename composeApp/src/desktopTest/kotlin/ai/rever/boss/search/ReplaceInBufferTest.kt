package ai.rever.boss.search

import ai.rever.boss.plugin.api.BufferSnapshot
import ai.rever.boss.plugin.api.EditResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * The open-buffer replace path end to end: replaceInBuffer -> changedSpan ->
 * BufferEditRange.ofSpan -> one version-guarded applyEdit. Previously unreachable in a
 * test because the editor was a process-global singleton; the EditorBufferBridge seam
 * makes it fakeable. This is the most delicate arithmetic in the change.
 */
class ReplaceInBufferTest {
    /** A one-file in-memory editor buffer that honours the version guard. */
    private class FakeBuffer(
        var content: String,
    ) : EditorBufferBridge {
        var version = 7L // arbitrary non-zero, to prove the guard is exercised
        var reportedVersion: Long? = null // when set, readBuffer lies (buffer moved since)

        override suspend fun readBuffer(path: String) =
            BufferSnapshot(path = path, content = content, version = reportedVersion ?: version, isModified = false)

        override suspend fun applyEdit(
            path: String,
            startLine: Int,
            startCol: Int,
            endLine: Int,
            endCol: Int,
            newText: String,
            expectedVersion: Long,
        ): EditResult {
            if (expectedVersion != version) {
                return EditResult(applied = false, reason = "stale")
            }
            val start = offset(startLine, startCol)
            val end = offset(endLine, endCol)
            content = content.substring(0, start) + newText + content.substring(end)
            version++
            return EditResult(applied = true, newVersion = version)
        }

        // 1-based line, 1-based col -> absolute offset
        private fun offset(
            line: Int,
            col: Int,
        ): Int {
            var off = 0
            var l = 1
            while (l < line) {
                off = content.indexOf('\n', off) + 1
                l++
            }
            return off + (col - 1)
        }
    }

    private fun serviceOver(
        buffer: FakeBuffer,
        root: String,
    ) = ContentSearchService(projectPathProvider = { root }, bufferBridge = buffer)

    @Test
    fun `replace edits the open buffer, not disk, and expands captures`(
        @TempDir dir: File,
    ) = runBlocking {
        val f = File(dir, "a.kt").apply { writeText("val OLD = 1\nval OLD = 2\n") }
        val buffer = FakeBuffer("val OLD = 1\nval OLD = 2\n")
        val svc = serviceOver(buffer, dir.absolutePath)

        val summary =
            svc.replaceInProject(
                query = "OLD",
                replacement = "NEW",
                files = listOf(f.absolutePath),
                isRegex = false,
                dryRun = false,
            )

        assertEquals(2, summary.totalReplacements)
        assertEquals("val NEW = 1\nval NEW = 2\n", buffer.content)
        // Disk is untouched: the file was open, so the edit went to the buffer.
        assertEquals("val OLD = 1\nval OLD = 2\n", f.readText())
    }

    @Test
    fun `a stale buffer version aborts without touching the buffer`(
        @TempDir dir: File,
    ) = runBlocking {
        val f = File(dir, "b.kt").apply { writeText("OLD OLD\n") }
        // readBuffer reports v7, but the buffer has really moved to v8 (a keystroke since
        // the snapshot). applyEdit(expectedVersion = 7) must see the mismatch and refuse.
        val buffer =
            FakeBuffer("OLD OLD\n").apply {
                version = 8
                reportedVersion = 7
            }
        val svc = serviceOver(buffer, dir.absolutePath)

        val summary =
            svc.replaceInProject(
                query = "OLD",
                replacement = "X",
                files = listOf(f.absolutePath),
                isRegex = false,
                dryRun = false,
            )

        assertEquals("OLD OLD\n", buffer.content, "a stale edit must not be applied")
        assertEquals(0, summary.totalReplacements)
        assertEquals("stale", summary.files.single().error)
    }
}
