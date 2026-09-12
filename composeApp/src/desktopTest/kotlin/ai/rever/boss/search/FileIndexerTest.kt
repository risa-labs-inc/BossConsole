package ai.rever.boss.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileIndexerTest {
    @Test
    fun `a later request waits for the active scan and then publishes its own files`() =
        runBlocking {
            val indexer = FileIndexer()
            val firstScanStarted = CompletableDeferred<Unit>()
            val releaseFirstScan = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            indexer.scanForTest = { projectPath ->
                calls += projectPath
                if (projectPath == "project-a") {
                    firstScanStarted.complete(Unit)
                    releaseFirstScan.await()
                }
                listOf(indexedFile(projectPath))
            }

            val first = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-a") }
            firstScanStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-b") }

            assertFalse(second.isCompleted, "the second request must wait rather than be dropped")
            assertEquals(listOf("project-a"), calls)

            releaseFirstScan.complete(Unit)
            first.await()
            second.await()

            assertEquals(listOf("project-a", "project-b"), calls)
            assertEquals("project-b", indexer.indexedPath.value)
            assertEquals(listOf("project-b/file.kt"), indexer.indexedFiles.value.map { it.path })
        }

    @Test
    fun `cancelling an active scan propagates and publishes no stale metadata`() =
        runBlocking {
            val indexer = FileIndexer()
            val scanStarted = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            indexer.scanForTest = {
                scanStarted.complete(Unit)
                neverRelease.await()
                listOf(indexedFile("stale"))
            }

            val indexing = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-a") }
            scanStarted.await()
            indexing.cancel()

            assertFailsWith<CancellationException> { indexing.await() }
            assertEquals(emptyList(), indexer.indexedFiles.value)
            assertNull(indexer.indexedPath.value)
        }

    @Test
    fun `cancelling a queued request prevents it from running or publishing`() =
        runBlocking {
            val indexer = FileIndexer()
            val firstScanStarted = CompletableDeferred<Unit>()
            val releaseFirstScan = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            indexer.scanForTest = { projectPath ->
                calls += projectPath
                if (projectPath == "project-a") {
                    firstScanStarted.complete(Unit)
                    releaseFirstScan.await()
                }
                listOf(indexedFile(projectPath))
            }

            val first = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-a") }
            firstScanStarted.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-b") }

            queued.cancel()
            assertFailsWith<CancellationException> { queued.await() }

            releaseFirstScan.complete(Unit)
            first.await()

            assertEquals(listOf("project-a"), calls)
            assertEquals("project-a", indexer.indexedPath.value)
        }

    @Test
    fun `cancelling the lock holder releases the queued request`() =
        runBlocking {
            val indexer = FileIndexer()
            val firstScanStarted = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            indexer.scanForTest = { projectPath ->
                calls += projectPath
                if (projectPath == "project-a") {
                    firstScanStarted.complete(Unit)
                    neverRelease.await()
                }
                listOf(indexedFile(projectPath))
            }

            val first = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-a") }
            firstScanStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-b") }

            first.cancel()
            assertFailsWith<CancellationException> { first.await() }
            second.await()

            assertEquals(listOf("project-a", "project-b"), calls)
            assertEquals("project-b", indexer.indexedPath.value)
        }

    @Test
    fun `a non cancellation scan failure releases the queued request`() =
        runBlocking {
            val indexer = FileIndexer()
            val firstScanStarted = CompletableDeferred<Unit>()
            val releaseFailure = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            indexer.scanForTest = { projectPath ->
                calls += projectPath
                if (projectPath == "project-a") {
                    firstScanStarted.complete(Unit)
                    releaseFailure.await()
                    throw IllegalStateException("scan failed")
                }
                listOf(indexedFile(projectPath))
            }

            val first = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-a") }
            firstScanStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-b") }

            assertFalse(second.isCompleted, "the second request must remain queued behind the failed scan")
            releaseFailure.complete(Unit)
            first.await()
            second.await()

            assertEquals(listOf("project-a", "project-b"), calls)
            assertEquals("project-b", indexer.indexedPath.value)
            assertEquals(listOf("project-b/file.kt"), indexer.indexedFiles.value.map { it.path })
        }

    @Test
    fun `rapid A B C requests serialize and publish the last completed project`() =
        runBlocking {
            val indexer = FileIndexer()
            val firstScanStarted = CompletableDeferred<Unit>()
            val releaseFirstScan = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            indexer.scanForTest = { projectPath ->
                calls += projectPath
                if (projectPath == "project-a") {
                    firstScanStarted.complete(Unit)
                    releaseFirstScan.await()
                }
                listOf(indexedFile(projectPath))
            }

            val a = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-a") }
            firstScanStarted.await()
            val b = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-b") }
            val c = async(start = CoroutineStart.UNDISPATCHED) { indexer.indexProject("project-c") }

            releaseFirstScan.complete(Unit)
            a.await()
            b.await()
            c.await()

            assertEquals(listOf("project-a", "project-b", "project-c"), calls)
            assertEquals("project-c", indexer.indexedPath.value)
            assertEquals(listOf("project-c/file.kt"), indexer.indexedFiles.value.map { it.path })
        }

    private fun indexedFile(project: String) =
        IndexedFile(
            name = "file.kt",
            path = "$project/file.kt",
            relativePath = "file.kt",
        )
}
