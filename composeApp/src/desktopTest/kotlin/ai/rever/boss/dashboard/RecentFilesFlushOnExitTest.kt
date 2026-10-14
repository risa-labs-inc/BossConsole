package ai.rever.boss.dashboard

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.auth.UserDataStorage
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The exit-path half of the persistence work #795 started.
 *
 * That PR made recent-files and user-data writes atomic, but its own body said the rest:
 * "The 5-second save debounce still has no flush-on-exit - kill the app within 5s and the
 * last entry is lost. Separate bug, not addressed here." This is that bug. The quit path
 * now calls [RecentFilesManager.flushPendingSaves] (which cancels the pending debounce and
 * writes immediately) and [UserDataStorage.flushPendingSaves] (the symmetric seam: user
 * data writes are synchronous, so its flush only waits out a write already mid-flight).
 *
 * Real time and runBlocking rather than runTest, hermetic temp paths via
 * [RecentFilesManager.resetForTesting] and [UserDataStorage.resetForTesting], and the
 * singletons pointed back at the real files afterwards - the same conventions as
 * [RecentFilesLoadTest], which pins the load side of the same machinery.
 */
class RecentFilesFlushOnExitTest {
    private lateinit var recentFilesFile: File
    private lateinit var userDataDir: File
    private lateinit var srcDir: File
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        runBlocking {
            recentFilesFile =
                Files
                    .createTempDirectory("recent-files-flush-test-")
                    .toFile()
                    .resolve("recent-files.json")
            RecentFilesManager.resetForTesting(recentFilesFile)
        }
        userDataDir = Files.createTempDirectory("user-data-flush-test-").toFile()
        UserDataStorage.resetForTesting(userDataDir)
        srcDir = Files.createTempDirectory("recent-files-flush-src-").toFile()
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            // Point the singletons back at the real files, so tests that run after this
            // class observe the same state the app would.
            RecentFilesManager.resetForTesting(BossDirectories.resolve("recent-files.json"))
        }
        UserDataStorage.resetForTesting(BossDirectories.rootDir)
        recentFilesFile.parentFile?.deleteRecursively()
        userDataDir.deleteRecursively()
        srcDir.deleteRecursively()
    }

    @Test
    fun `flush persists a file opened inside the debounce window without waiting it out`() =
        runBlocking {
            val opened = srcDir.resolve("JustOpened.kt").apply { createNewFile() }

            RecentFilesManager.recordFileOpen(opened.path)

            // recordFileOpen is fire-and-forget on Dispatchers.IO, so first wait until the
            // open is actually recorded, then until the write lands. The bound sits far
            // under SAVE_DEBOUNCE_MS on purpose: with a broken flush the only writer would
            // be the 5-second timer, and this would time out rather than pass slowly.
            withTimeout(2_000) {
                RecentFilesManager.recentFiles.first { files -> files.any { it.path == opened.path } }
                while (!recentFilesFile.exists()) {
                    RecentFilesManager.flushPendingSaves()
                    delay(25)
                }
            }

            val saved = json.decodeFromString<RecentFilesData>(recentFilesFile.readText())
            assertTrue(
                saved.files.any { it.path == opened.path },
                "an entry opened seconds before quitting must be on disk, not lost inside the debounce",
            )
        }

    @Test
    fun `user data saved before quitting is durable once the flush seam has run`() =
        runBlocking {
            val user =
                UserInfo(
                    id = "flush-test-user",
                    email = "flush@example.com",
                    createdAt = "2026-09-17T00:00:00Z",
                )

            UserDataStorage.saveUserData(user)
            UserDataStorage.flushPendingSaves()

            val loaded = UserDataStorage.loadUserData()
            assertNotNull(loaded, "the record must be on disk once the exit-path flush has run")
            assertEquals(user.id, loaded.id)
            assertEquals(user.email, loaded.email)
            assertEquals(user.createdAt, loaded.createdAt)
        }

    @Test
    fun `flushing when nothing is pending leaves recent-files untouched`() =
        runBlocking {
            // What an already-completed save left behind: an entry that is on disk but not
            // in memory (nothing has been recorded this session). A flush that wrote
            // unconditionally would clobber it with the empty in-memory list.
            val persisted =
                listOf(RecentFile(path = "/gone/before-quit.kt", name = "before-quit.kt", lastOpened = 100))
            recentFilesFile.writeText(json.encodeToString(RecentFilesData(persisted)))

            RecentFilesManager.flushPendingSaves()

            assertEquals(
                persisted,
                json.decodeFromString<RecentFilesData>(recentFilesFile.readText()).files,
                "no debounced save is pending, so the flush must be a no-op",
            )
        }

    @Test
    fun `a failing flush logs the error and does not throw`() {
        runBlocking {
            // Schedule a debounced save deterministically: seed a recorded entry, put a
            // different list on disk, and re-run the load - its merge schedules the save
            // before loadAsync returns (the scheduling whose effects RecentFilesLoadTest
            // pins), so there is no async window to race here.
            val fromDisk = RecentFile(path = "/gone/on-disk.kt", name = "on-disk.kt", lastOpened = 100)
            val justOpened = RecentFile(path = "/gone/just-opened.kt", name = "just-opened.kt", lastOpened = 900)
            recentFilesFile.writeText(json.encodeToString(RecentFilesData(listOf(fromDisk))))
            RecentFilesManager.resetForTesting(recentFilesFile, recorded = listOf(justOpened))
            assertTrue(RecentFilesManager.loadAsync(), "the merge differs from disk, so a debounced save is pending")

            // The parent of settingsFile is a regular file, so the atomic write's
            // temp-file step fails with an IOException.
            val blocker = Files.createTempFile("recent-files-flush-blocker-", ".tmp").toFile()
            RecentFilesManager.settingsFile = blocker.resolve("recent-files.json")

            val previousLevel = BossLogger.globalLevel
            BossLogger.setGlobalLevel(LogLevel.WARN)
            try {
                // Must return normally despite the IOException: the exit path runs this as
                // one step among many, and a throw would skip the rest of the sequence.
                RecentFilesManager.flushPendingSaves()

                val failure = BossLogger.getRecentLogs(limit = 100).last { it.component == "RecentFilesManager" }
                assertEquals("Error saving recent files", failure.message)
                assertEquals(LogLevel.WARN, failure.level)
                assertNotNull(failure.error, "the warn must carry the underlying IOException")
            } finally {
                BossLogger.setGlobalLevel(previousLevel)
                blocker.delete()
            }
        }
    }
}
