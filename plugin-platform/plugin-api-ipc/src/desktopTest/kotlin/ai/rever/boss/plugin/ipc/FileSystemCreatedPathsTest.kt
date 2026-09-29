package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import ai.rever.boss.ipc.proto.services.RenameFileRequest
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-process provider returns `File(parent, name).absolutePath` from createFile,
 * createFolder and rename. The proxy builds that path itself before asking the kernel, so it
 * has to build it the same way: plugins compare these strings against paths the host hands
 * them, such as scan results and open tabs.
 */
class FileSystemCreatedPathsTest {
    private val parent = File(System.getProperty("java.io.tmpdir"), "boss-created-paths").absolutePath

    @Test
    fun `createFile returns the path the in-process provider returns`() =
        runBlocking {
            withService { proxy, sent ->
                val created = proxy.createFile(parent, "a.txt").getOrThrow()

                assertEquals(File(parent, "a.txt").absolutePath, created)
                assertEquals(listOf(created), sent)
            }
        }

    @Test
    fun `createFolder returns the path the in-process provider returns`() =
        runBlocking {
            withService { proxy, sent ->
                val created = proxy.createFolder(parent, "notes").getOrThrow()

                assertEquals(File(parent, "notes").absolutePath, created)
                assertEquals(listOf(created), sent)
            }
        }

    @Test
    fun `a parent passed with a trailing separator is not doubled`() =
        runBlocking {
            withService { proxy, _ ->
                val created = proxy.createFile(parent + File.separator, "a.txt").getOrThrow()

                assertEquals(File(parent, "a.txt").absolutePath, created)
            }
        }

    @Test
    fun `rename returns the path the in-process provider returns`() =
        runBlocking {
            withService { proxy, sent ->
                val renamed = proxy.rename(File(parent, "old.txt").absolutePath, "new.txt").getOrThrow()

                assertEquals(File(parent, "new.txt").absolutePath, renamed)
                assertEquals(listOf(renamed), sent)
            }
        }

    @Test
    fun `rename of a path with no parent fails like the in-process provider`() =
        runBlocking {
            // The in-process provider refuses with "Cannot determine parent directory". The
            // proxy used to send the bare name, which the kernel resolves against its own
            // working directory rather than anywhere the plugin chose.
            withService { proxy, sent ->
                assertTrue(proxy.rename("old.txt", "new.txt").isFailure)
                assertTrue(sent.isEmpty(), "nothing should reach the kernel, but sent $sent")
            }
        }

    private suspend fun withService(action: suspend (FileSystemDataProviderProxy, List<String>) -> Unit) {
        val sent = CopyOnWriteArrayList<String>()
        val service =
            object : FileSystemServiceGrpcKt.FileSystemServiceCoroutineImplBase() {
                override suspend fun createFile(request: CreateFileRequest): Empty {
                    sent.add(request.path)
                    return Empty.getDefaultInstance()
                }

                override suspend fun renameFile(request: RenameFileRequest): Empty {
                    sent.add(request.destinationPath)
                    return Empty.getDefaultInstance()
                }
            }
        val server =
            ServerBuilder
                .forPort(0)
                .addService(service)
                .build()
                .start()
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        try {
            action(FileSystemDataProviderProxy(channel, channel), sent)
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }
}
