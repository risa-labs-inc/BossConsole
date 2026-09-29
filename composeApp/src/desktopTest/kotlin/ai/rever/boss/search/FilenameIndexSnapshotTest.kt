package ai.rever.boss.search

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilenameIndexSnapshotTest {
    @Test
    fun `file budget keeps usable filenames with an explicit warning`(
        @TempDir root: File,
    ) = runBlocking {
        repeat(3) { File(root, "Source$it.kt").writeText("test") }
        val result = ProjectFileDiscovery.discover(root.path, maxFiles = 2)
        assertTrue(result.budgetExceeded)
        assertTrue(result.incompleteReason != null)
        assertEquals(2, filenameIndexFrom(result).size)
    }

    @Test
    fun `invalid ignore policy still rejects partial filenames`() {
        assertFailsWith<ProjectDiscoveryIncompleteException> {
            filenameIndexFrom(ProjectDiscoveryResult(emptyList(), "Unreadable ignore rules"))
        }
    }

    @Test
    fun `nested worktrees do not exhaust the selected projects file budget`(
        @TempDir root: File,
    ) = runBlocking {
        val copies = File(root, ".worktrees/copy").apply { mkdirs() }
        repeat(4) { File(copies, "Duplicate$it.kt").writeText("test") }
        File(root, "Actual.kt").writeText("test")
        val result = ProjectFileDiscovery.discover(root.path, maxFiles = 2)
        assertEquals(null, result.incompleteReason)
        assertEquals(listOf("Actual.kt"), filenameIndexFrom(result).map { it.name })
        // A worktree selected as the project itself is still indexed normally.
        assertEquals(4, ProjectFileDiscovery.discover(copies.path).files.size)
    }
}
