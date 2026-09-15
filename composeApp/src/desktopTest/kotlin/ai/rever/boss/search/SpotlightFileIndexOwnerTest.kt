package ai.rever.boss.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpotlightFileIndexOwnerTest {
    @Test
    fun `completed index is reused and force reindex scans again`() =
        runTest {
            var scans = 0
            val owner =
                SpotlightFileIndexOwner(backgroundScope) {
                    FileIndexer { path ->
                        scans++
                        listOf(file(path))
                    }
                }
            val first = owner.indexerFor("/first")
            first.indexProject("/first")
            val reopened = owner.indexerFor("/first")
            reopened.indexProject("/first")
            assertSame(first, reopened)
            assertEquals(1, scans)
            reopened.indexProject("/first", forceReindex = true)
            assertEquals(2, scans)

            val replacement = owner.indexerFor("/replacement")
            assertNotSame(first, replacement)
            assertEquals(emptyList(), first.indexedFiles.value)
            replacement.indexProject("/replacement")
            assertEquals(listOf("/replacement/file.kt"), replacement.indexedFiles.value.map { it.path })
        }

    @Test
    fun `closing and reopening a dialog during a scan retains a single live scan`() =
        runTest {
            var scans = 0
            val release = CompletableDeferred<Unit>()
            val owner =
                SpotlightFileIndexOwner(backgroundScope) {
                    FileIndexer { path ->
                        scans++
                        release.await()
                        listOf(file(path))
                    }
                }
            val dialog = launch { owner.ensureIndexed("/project") }
            runCurrent()
            dialog.cancelAndJoin()
            assertTrue(owner.indexerFor("/project").isIndexing.value)
            owner.ensureIndexed("/project")
            runCurrent()
            assertEquals(1, scans)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf(file("/project")), owner.indexerFor("/project").indexedFiles.value)
            owner.ensureIndexed("/project")
            runCurrent()
            assertEquals(1, scans)
            assertFalse(owner.indexerFor("/project").isIndexing.value)
        }

    @Test
    fun `project replacement cancels an unfinished scan without publishing its files`() =
        runTest {
            val cancelled = CompletableDeferred<Unit>()
            val owner =
                SpotlightFileIndexOwner(backgroundScope) {
                    FileIndexer { path ->
                        if (path == "/first") {
                            try {
                                CompletableDeferred<Unit>().await()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        }
                        listOf(file(path))
                    }
                }
            val first = owner.indexerFor("/first")
            owner.ensureIndexed("/first")
            runCurrent()
            owner.ensureIndexed("/second")
            runCurrent()
            assertTrue(cancelled.isCompleted)
            assertFalse(first.isIndexing.value)
            assertEquals(emptyList(), first.indexedFiles.value)
            assertEquals(listOf(file("/second")), owner.indexerFor("/second").indexedFiles.value)
        }

    @Test
    fun `window scope cancellation stops its retained scan`() =
        runTest {
            val windowJob = Job(backgroundScope.coroutineContext[Job])
            val scope = CoroutineScope(backgroundScope.coroutineContext + windowJob)
            val owner =
                SpotlightFileIndexOwner(scope) {
                    FileIndexer {
                        CompletableDeferred<Unit>().await()
                        emptyList()
                    }
                }
            val indexer = owner.indexerFor("/project")
            owner.ensureIndexed("/project")
            runCurrent()
            assertTrue(indexer.isIndexing.value)
            windowJob.cancel()
            runCurrent()
            assertFalse(indexer.isIndexing.value)
            assertEquals(emptyList(), indexer.indexedFiles.value)
        }

    @Test
    fun `refresh after completed scans sees file creation rename and deletion`() =
        runBlocking {
            val root = Files.createTempDirectory("spotlight-refresh").toFile()
            try {
                val original = root.resolve("original.txt").apply { writeText("original") }
                val owner = SpotlightFileIndexOwner(this)
                val indexer = owner.indexerFor(root.path)

                suspend fun refresh() {
                    owner.ensureIndexed(root.path, refresh = true)
                    coroutineContext.job.children
                        .toList()
                        .joinAll()
                }
                refresh()
                assertEquals(listOf("original.txt"), indexer.indexedFiles.value.map { it.name })
                root.resolve("created.txt").writeText("created")
                refresh()
                assertEquals(
                    setOf("original.txt", "created.txt"),
                    indexer.indexedFiles.value
                        .map { it.name }
                        .toSet(),
                )
                assertTrue(original.renameTo(root.resolve("renamed.txt")))
                refresh()
                assertEquals(
                    setOf("renamed.txt", "created.txt"),
                    indexer.indexedFiles.value
                        .map { it.name }
                        .toSet(),
                )
                assertTrue(root.resolve("created.txt").delete())
                assertTrue(root.resolve("renamed.txt").delete())
                refresh()
                assertEquals(emptyList(), indexer.indexedFiles.value)
                assertSame(indexer, owner.indexerFor(root.path))
            } finally {
                root.deleteRecursively()
            }
        }

    private fun file(path: String) = IndexedFile("file.kt", "$path/file.kt", "file.kt")
}
