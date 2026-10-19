package ai.rever.boss.dashboard

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentFilesDismissalDuringLoadTest {
    private lateinit var workDir: File
    private lateinit var settingsFile: File
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        runBlocking {
            workDir = Files.createTempDirectory("recent-files-dismissal-").toFile()
            settingsFile = workDir.resolve("recent-files.json")
            RecentFilesManager.resetForTesting(settingsFile)
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            RecentFilesManager.resetForTesting(
                BossDirectories.resolve("recent-files.json"),
                reload = false,
            )
        }
        workDir.deleteRecursively()
    }

    @Test
    fun `remove wins over a paused startup read and is persisted`() {
        runBlocking {
            val removed = createRecentFile("removed.kt", lastOpened = 200)
            val retained = createRecentFile("retained.kt", lastOpened = 100)
            writeHistory(removed, retained)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val loading = async { RecentFilesManager.loadAsync(read = pausedRead(started, release)) }
            withTimeout(2_000) { started.await() }
            RecentFilesManager.removeFile(removed.path)
            release.complete(Unit)

            assertTrue(loading.await(), "filtering the decoded snapshot must schedule persistence")
            awaitVisiblePaths(setOf(retained.path))
            RecentFilesManager.flushPendingSaves()
            assertEquals(setOf(retained.path), persistedPaths())
        }
    }

    @Test
    fun `clear wins over a paused read while preserving a later open`() {
        runBlocking {
            val oldA = createRecentFile("old-a.kt", lastOpened = 200)
            val oldB = createRecentFile("old-b.kt", lastOpened = 100)
            val later = createRecentFile("later.kt", lastOpened = 0)
            writeHistory(oldA, oldB)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val loading = async { RecentFilesManager.loadAsync(read = pausedRead(started, release)) }
            withTimeout(2_000) { started.await() }
            RecentFilesManager.clearAll()
            RecentFilesManager.recordFileOpen(later.path)
            awaitVisiblePaths(setOf(later.path))
            release.complete(Unit)

            assertTrue(loading.await(), "clear changes the decoded snapshot and must be persisted")
            awaitVisiblePaths(setOf(later.path))
            RecentFilesManager.flushPendingSaves()
            assertEquals(setOf(later.path), persistedPaths())
        }
    }

    @Test
    fun `remove then reopen keeps the new revision instead of the stale decoded one`() {
        runBlocking {
            val reopened = createRecentFile("reopened.kt", lastOpened = 1)
            val retained = createRecentFile("retained.kt", lastOpened = 2)
            writeHistory(reopened, retained)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val loading = async { RecentFilesManager.loadAsync(read = pausedRead(started, release)) }
            withTimeout(2_000) { started.await() }
            RecentFilesManager.removeFile(reopened.path)
            RecentFilesManager.recordFileOpen(reopened.path, projectPath = workDir.path)
            release.complete(Unit)

            assertTrue(loading.await())
            awaitVisiblePaths(setOf(reopened.path, retained.path))
            val current = RecentFilesManager.recentFiles.value.filter { it.path == reopened.path }
            assertEquals(1, current.size)
            assertTrue(current.single().lastOpened > 1, "the reopened revision must beat the stale disk entry")
            assertEquals(workDir.path, current.single().projectPath)
            RecentFilesManager.flushPendingSaves()
            assertEquals(1, persistedFiles().count { it.path == reopened.path })
        }
    }

    @Test
    fun `one removal is applied to every overlapping load`() {
        runBlocking {
            val removed = createRecentFile("removed.kt", lastOpened = 200)
            val retained = createRecentFile("retained.kt", lastOpened = 100)
            writeHistory(removed, retained)
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val firstRelease = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()

            val first = async { RecentFilesManager.loadAsync(read = pausedRead(firstStarted, firstRelease)) }
            val second = async { RecentFilesManager.loadAsync(read = pausedRead(secondStarted, secondRelease)) }
            withTimeout(2_000) {
                firstStarted.await()
                secondStarted.await()
            }
            RecentFilesManager.removeFile(removed.path)
            firstRelease.complete(Unit)
            assertTrue(first.await())
            secondRelease.complete(Unit)
            assertTrue(second.await())

            awaitVisiblePaths(setOf(retained.path))
            RecentFilesManager.flushPendingSaves()
            assertEquals(setOf(retained.path), persistedPaths())
        }
    }

    private fun createRecentFile(
        name: String,
        lastOpened: Long,
    ): RecentFile {
        val file = workDir.resolve(name).apply { createNewFile() }
        return RecentFile(path = file.path, name = file.name, lastOpened = lastOpened)
    }

    private fun writeHistory(vararg files: RecentFile) {
        settingsFile.writeText(json.encodeToString(RecentFilesData(files.toList())))
    }

    private fun pausedRead(
        started: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ): suspend (File) -> String =
        { source ->
            val captured = source.readText()
            started.complete(Unit)
            release.await()
            captured
        }

    private suspend fun awaitVisiblePaths(expected: Set<String>) {
        withTimeout(2_000) {
            RecentFilesManager.recentFiles.first { files -> files.map { it.path }.toSet() == expected }
        }
    }

    private fun persistedFiles() = json.decodeFromString<RecentFilesData>(settingsFile.readText()).files

    private fun persistedPaths(): Set<String> = persistedFiles().map { it.path }.toSet()
}
