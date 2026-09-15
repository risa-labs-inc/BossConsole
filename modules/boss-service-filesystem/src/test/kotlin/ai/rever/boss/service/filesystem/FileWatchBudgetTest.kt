package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.WatchFileChangesRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
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
        try {
            supervisorScope {
                val jobs = mutableListOf<Job>()
                try {
                    repeat(streams) { index ->
                        val path = tree(root.resolve("watch-$index"), directories)
                        jobs += launch { FileSystemServiceImpl().watchFileChanges(request(path)).collect() }
                    }
                    withTimeout(30_000) {
                        while (WatchResources.directories.availablePermits() != 128 - streams * directories) delay(10)
                    }
                    val refused =
                        assertFailsWith<StatusRuntimeException> {
                            withTimeout(5000) { FileSystemServiceImpl().watchFileChanges(request(root)).first() }
                        }
                    assertEquals(Status.Code.RESOURCE_EXHAUSTED, refused.status.code)
                    jobs.removeAt(0).cancelAndJoin()
                    val replacement = tree(root.resolve("replacement"), directories)
                    jobs += launch { FileSystemServiceImpl().watchFileChanges(request(replacement)).collect() }
                    withTimeout(30_000) {
                        while (WatchResources.directories.availablePermits() != 128 - streams * directories) delay(10)
                    }
                } finally {
                    jobs.forEach { it.cancelAndJoin() }
                }
            }
            assertEquals(8, WatchResources.streams.availablePermits())
            assertEquals(128, WatchResources.directories.availablePermits())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `partial recursive registration failure releases all handles and permits`() =
        runBlocking {
            val root = Files.createTempDirectory("watch-registration-failure-").toRealPath()
            try {
                val path = tree(root.resolve("too-large"), 130)
                val refused =
                    assertFailsWith<StatusRuntimeException> {
                        withTimeout(30_000) { FileSystemServiceImpl().watchFileChanges(request(path)).first() }
                    }
                assertEquals(Status.Code.RESOURCE_EXHAUSTED, refused.status.code)
                assertEquals(8, WatchResources.streams.availablePermits())
                assertEquals(128, WatchResources.directories.availablePermits())
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
