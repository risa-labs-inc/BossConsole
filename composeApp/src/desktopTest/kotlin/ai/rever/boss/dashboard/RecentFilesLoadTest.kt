package ai.rever.boss.dashboard

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives [RecentFilesManager.loadAsync] end to end against a hermetic temp file (see
 * [RecentFilesManager.resetForTesting]); [RecentFilesMergeTest] keeps the merge rule itself
 * pure, and this one pins what the load does with the merge result.
 *
 * The regression pinned here: the save the startup load schedules must be due to a merge that
 * actually changed what is on disk. When the no-op baseline was the pre-merge in-memory list -
 * empty until the load - every ordinary launch counted as "changed" and scheduled a rewrite of
 * recent-files.json with its own contents.
 */
class RecentFilesLoadTest {
    private lateinit var workDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @BeforeTest
    fun setUp() {
        runBlocking {
            workDir = Files.createTempDirectory("recent-files-load-test-").toFile()
            tempFile = File.createTempFile("recent-files-test-", ".json").apply { delete() }
            RecentFilesManager.resetForTesting(tempFile)
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            // Restore the singleton's ordinary task-local path without starting work that can
            // outlive this test. The Test task redirects user.home away from the developer's home.
            RecentFilesManager.resetForTesting(
                BossDirectories.resolve("recent-files.json"),
                reload = false,
            )
            tempFile.delete()
        }
        workDir.deleteRecursively()
    }

    private fun recentFile(
        file: File,
        lastOpened: Long,
    ) = RecentFile(path = file.path, name = file.name, lastOpened = lastOpened)

    @Test
    fun `an ordinary launch loads the decoded list and schedules no save`() =
        runBlocking {
            val a = workDir.resolve("a.kt").apply { createNewFile() }
            val b = workDir.resolve("b.kt").apply { createNewFile() }
            val onDisk = listOf(recentFile(a, 300), recentFile(b, 200))
            tempFile.writeText(json.encodeToString(RecentFilesData(onDisk)))
            val mtimeBefore = tempFile.lastModified()

            val persist = RecentFilesManager.loadAsync()

            assertFalse(
                persist,
                "nothing was recorded while the load ran, so the decoded file is already the merged state",
            )
            assertEquals(
                onDisk,
                RecentFilesManager.recentFiles.value,
                "the decoded entries must reach the displayed list",
            )
            assertEquals(mtimeBefore, tempFile.lastModified(), "no save means no rewrite of recent-files.json")
        }

    @Test
    fun `a file recorded while the load was in flight schedules a save`() =
        runBlocking {
            val a = workDir.resolve("a.kt").apply { createNewFile() }
            tempFile.writeText(json.encodeToString(RecentFilesData(listOf(recentFile(a, 100)))))
            val b = workDir.resolve("b.kt").apply { createNewFile() }
            // Re-run the reset with a seeded recorded list: the file on disk stays as written,
            // while the in-memory list holds the "opened during startup" entry.
            RecentFilesManager.resetForTesting(tempFile, recorded = listOf(recentFile(b, 900)))

            val persist = RecentFilesManager.loadAsync()

            assertTrue(persist, "the merge changed what is on disk, so the save is due")
            assertEquals(
                listOf(recentFile(b, 900), recentFile(a, 100)),
                RecentFilesManager.recentFiles.value,
                "the in-flight open and the decoded history must both survive the merge",
            )
        }

    @Test
    fun `a missing file loads nothing and schedules nothing`() =
        runBlocking {
            assertFalse(RecentFilesManager.loadAsync())
            assertEquals(emptyList(), RecentFilesManager.recentFiles.value)
        }
}
