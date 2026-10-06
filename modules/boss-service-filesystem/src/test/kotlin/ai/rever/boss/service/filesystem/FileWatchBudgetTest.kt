package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import ai.rever.boss.ipc.proto.services.WatchFileChangesRequest
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileWatchBudgetTest {
    @Test
    fun `stream capacity is shared across simultaneous service instances and reusable`() = exercise(8, 1)

    @Test
    fun `directory capacity is shared across simultaneous service instances and reusable`() = exercise(2, 64)

    private fun exercise(
        streams: Int,
        directories: Int,
    ) = runBlocking {
        val root = Files.createTempDirectory("shared-watches-").toRealPath()
        // Permit counts are process-wide, so every assertion uses a delta captured here
        // rather than an absolute total: another suite in the same JVM may still be
        // releasing asynchronously cancelled watches.
        val streamsBefore = WatchResources.streams.availablePermits()
        val directoriesBefore = WatchResources.directories.availablePermits()
        val fullDirectoryCount = directoriesBefore - streams * directories
        try {
            AuthenticatedFileService(FileSystemServiceImpl()).use { transport ->
                val service =
                    AuthenticatedFileService.stub(transport.channelFor("watch-host", ProcessAuthority.HOST))
                supervisorScope {
                    val jobs = mutableListOf<Job>()
                    try {
                        repeat(streams) { index ->
                            val path = tree(root.resolve("watch-$index"), directories)
                            jobs += launch { service.watchFileChanges(request(path)).collect() }
                        }
                        withTimeout(30_000) {
                            while (WatchResources.directories.availablePermits() != fullDirectoryCount) {
                                delay(10)
                            }
                        }
                        val refused =
                            assertFailsWith<StatusException> {
                                withTimeout(5000) { service.watchFileChanges(request(root)).first() }
                            }
                        assertEquals(Status.Code.RESOURCE_EXHAUSTED, refused.status.code)
                        jobs.removeAt(0).cancelAndJoin()
                        // Client cancellation reaches the server asynchronously; wait for the
                        // cancelled stream's permits to be released before the replacement can
                        // register, or it is refused with RESOURCE_EXHAUSTED inside the
                        // supervisorScope launch (silently) and the wait below times out
                        // without naming the cause.
                        withTimeout(30_000) {
                            while (
                                WatchResources.streams.availablePermits() != streamsBefore - streams + 1 ||
                                WatchResources.directories.availablePermits() !=
                                directoriesBefore - (streams - 1) * directories
                            ) {
                                delay(10)
                            }
                        }
                        val replacement = tree(root.resolve("replacement"), directories)
                        jobs += launch { service.watchFileChanges(request(replacement)).collect() }
                        withTimeout(30_000) {
                            while (WatchResources.directories.availablePermits() != fullDirectoryCount) {
                                delay(10)
                            }
                        }
                    } finally {
                        jobs.forEach { it.cancelAndJoin() }
                    }
                }
                // Client cancellation reaches the server asynchronously; wait for the release.
                withTimeout(30_000) {
                    while (WatchResources.streams.availablePermits() != streamsBefore) delay(10)
                }
                assertEquals(streamsBefore, WatchResources.streams.availablePermits())
                assertEquals(directoriesBefore, WatchResources.directories.availablePermits())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `partial recursive registration failure releases all handles and permits`() =
        runBlocking {
            val root = Files.createTempDirectory("watch-registration-failure-").toRealPath()
            val streamsBefore = WatchResources.streams.availablePermits()
            val directoriesBefore = WatchResources.directories.availablePermits()
            try {
                AuthenticatedFileService(FileSystemServiceImpl()).use { transport ->
                    val service =
                        AuthenticatedFileService.stub(transport.channelFor("watch-host", ProcessAuthority.HOST))
                    val path = tree(root.resolve("too-large"), 130)
                    val refused =
                        assertFailsWith<StatusException> {
                            withTimeout(30_000) { service.watchFileChanges(request(path)).first() }
                        }
                    assertEquals(Status.Code.RESOURCE_EXHAUSTED, refused.status.code)
                    assertEquals(streamsBefore, WatchResources.streams.availablePermits())
                    assertEquals(directoriesBefore, WatchResources.directories.availablePermits())
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private fun tree(
        path: Path,
        count: Int,
    ): Path {
        Files.createDirectory(path)
        repeat(count - 1) { Files.createDirectory(path.resolve("child-$it")) }
        return path
    }

    private fun request(path: Path) =
        WatchFileChangesRequest
            .newBuilder()
            .setPath(path.toString())
            .setRecursive(true)
            .build()
}
