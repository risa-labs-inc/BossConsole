package ai.rever.boss.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClosedFileReplacementCoordinatorTest {
    @Test
    fun `a stale transaction cannot overwrite a completed replacement`(
        @TempDir dir: File,
    ) {
        runBlocking {
            val coordinator = ClosedFileReplacementCoordinator()
            val file = File(dir, "document.txt").apply { writeText("alpha beta") }

            runOverlappingReplacements(
                coordinator = coordinator,
                first = Replacement(file, "alpha", "ALPHA"),
                second = Replacement(file, "beta", "BETA"),
            )

            assertEquals("ALPHA BETA", file.readText())
        }
    }

    @Test
    fun `replacement order does not change the combined result`(
        @TempDir dir: File,
    ) {
        runBlocking {
            val coordinator = ClosedFileReplacementCoordinator()
            val file = File(dir, "document.txt").apply { writeText("alpha beta") }

            runOverlappingReplacements(
                coordinator = coordinator,
                first = Replacement(file, "beta", "BETA"),
                second = Replacement(file, "alpha", "ALPHA"),
            )

            assertEquals("ALPHA BETA", file.readText())
        }
    }

    @Test
    fun `canonical aliases share one admission point`(
        @TempDir dir: File,
    ) {
        runBlocking {
            val coordinator = ClosedFileReplacementCoordinator()
            val file = File(dir, "document.txt").apply { writeText("alpha beta") }
            val nested = File(dir, "nested").apply { mkdir() }
            val alias = File(nested, "../document.txt")

            assertEquals(file.canonicalPath, alias.canonicalPath)

            runOverlappingReplacements(
                coordinator = coordinator,
                first = Replacement(file, "alpha", "ALPHA"),
                second = Replacement(alias, "beta", "BETA"),
            )

            assertEquals("ALPHA BETA", file.readText())
        }
    }

    @Test
    fun `independent files can be updated concurrently`(
        @TempDir dir: File,
    ) {
        runBlocking {
            val coordinator = ClosedFileReplacementCoordinator()
            val firstFile = File(dir, "first.txt")
            val secondFile = File(dir, "second.txt")
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.withFile(firstFile) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }

            firstEntered.await()

            var secondEntered = false
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.withFile(secondFile) {
                        secondEntered = true
                    }
                }

            try {
                assertTrue(
                    secondEntered,
                    "coordination for one file must not block an independent file",
                )
            } finally {
                releaseFirst.complete(Unit)
                first.await()
                second.await()
            }
        }
    }

    @Test
    fun `cancelling a queued transaction does not strand admission`(
        @TempDir dir: File,
    ) {
        runBlocking {
            val coordinator = ClosedFileReplacementCoordinator()
            val file = File(dir, "document.txt")
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.withFile(file) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }

            firstEntered.await()

            var cancelledEntered = false
            val cancelled =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.withFile(file) {
                        cancelledEntered = true
                    }
                }

            cancelled.cancelAndJoin()
            assertFalse(
                cancelledEntered,
                "a transaction cancelled while queued must not enter",
            )

            releaseFirst.complete(Unit)
            first.await()

            var retryEntered = false
            coordinator.withFile(file) {
                retryEntered = true
            }

            assertTrue(retryEntered)
            assertEquals(0, coordinator.trackedFileCount)
        }
    }

    @Test
    fun `operation failure releases admission for retry`(
        @TempDir dir: File,
    ) {
        runBlocking {
            val coordinator = ClosedFileReplacementCoordinator()
            val file = File(dir, "document.txt")

            val failure =
                runCatching {
                    coordinator.withFile<Unit>(file) {
                        throw IOException("write failed")
                    }
                }

            assertIs<IOException>(failure.exceptionOrNull())

            var retryEntered = false
            coordinator.withFile(file) {
                retryEntered = true
            }

            assertTrue(retryEntered)
            assertEquals(0, coordinator.trackedFileCount)
        }
    }

    @Test
    fun `a third caller cannot split admission during waiter handoff`(
        @TempDir dir: File,
    ) = runBlocking {
        val coordinator = ClosedFileReplacementCoordinator()
        val file = File(dir, "handoff.txt")
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withFile(file) { releaseFirst.await() }
            }
        val second =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withFile(file) {
                    secondEntered.complete(Unit)
                    releaseSecond.await()
                }
            }
        releaseFirst.complete(Unit)
        first.await()
        secondEntered.await()
        var thirdEntered = false
        val third =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withFile(file) { thirdEntered = true }
            }
        try {
            assertFalse(thirdEntered, "a new caller bypassed the admitted waiter")
            assertEquals(1, coordinator.trackedFileCount)
        } finally {
            releaseSecond.complete(Unit)
            second.await()
            third.await()
        }
        assertTrue(thirdEntered)
        assertEquals(0, coordinator.trackedFileCount)
    }

    @Test
    fun `directory symlink aliases share admission`(
        @TempDir dir: File,
    ) = runBlocking {
        val coordinator = ClosedFileReplacementCoordinator()
        val file = File(dir, "document.txt").apply { writeText("alpha beta") }
        val link = runCatching { Files.createSymbolicLink(File(dir, "link").toPath(), dir.toPath()) }
        assumeTrue(link.isSuccess, "directory symlinks unavailable on this platform")
        runOverlappingReplacements(
            coordinator,
            Replacement(file, "alpha", "ALPHA"),
            Replacement(File(link.getOrThrow().toFile(), "document.txt"), "beta", "BETA"),
        )
        assertEquals("ALPHA BETA", file.readText())
        assertEquals(0, coordinator.trackedFileCount)
    }

    @Test
    fun `case aliases share admission on case insensitive volumes`(
        @TempDir dir: File,
    ) = runBlocking {
        val coordinator = ClosedFileReplacementCoordinator()
        val file = File(dir, "document.txt").apply { writeText("alpha beta") }
        val alias = File(dir, "DOCUMENT.TXT")
        assumeTrue(alias.exists(), "case-sensitive volume")
        runOverlappingReplacements(
            coordinator,
            Replacement(file, "alpha", "ALPHA"),
            Replacement(alias, "beta", "BETA"),
        )
        assertEquals("ALPHA BETA", file.readText())
        assertEquals(0, coordinator.trackedFileCount)
    }

    private suspend fun CoroutineScope.runOverlappingReplacements(
        coordinator: ClosedFileReplacementCoordinator,
        first: Replacement,
        second: Replacement,
    ) {
        val firstRead = CompletableDeferred<Unit>()
        val resumeFirst = CompletableDeferred<Unit>()

        val firstJob =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withFile(first.file) {
                    val snapshot = first.file.readText()
                    firstRead.complete(Unit)
                    resumeFirst.await()
                    first.file.writeText(
                        snapshot.replace(first.target, first.replacement),
                    )
                }
            }

        firstRead.await()

        val secondJob =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withFile(second.file) {
                    val snapshot = second.file.readText()
                    second.file.writeText(
                        snapshot.replace(second.target, second.replacement),
                    )
                }
            }

        resumeFirst.complete(Unit)
        firstJob.await()
        secondJob.await()
    }

    private data class Replacement(
        val file: File,
        val target: String,
        val replacement: String,
    )
}
