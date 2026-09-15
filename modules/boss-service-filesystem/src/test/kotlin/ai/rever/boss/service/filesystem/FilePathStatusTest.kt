package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import ai.rever.boss.ipc.proto.services.RenameFileRequest
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FilePathStatusTest {
    @Test
    fun `traversal and root entry refusals retain actionable wire statuses`() =
        runBlocking {
            val root = Files.createTempDirectory("path-status-").toRealPath()
            val service = FileSystemServiceImpl(FileAccess(FilePathPolicy(emptyList())))
            val server =
                NettyServerBuilder
                    .forAddress(InetSocketAddress("127.0.0.1", 0))
                    .addService(service)
                    .build()
                    .start()
            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
            try {
                val stub = FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub(channel)
                val source = Files.writeString(root.resolve("source"), "retained")
                val traversal = root.resolve("child/../source").toString()
                val destination = root.resolve("destination")
                val create =
                    assertFailsWith<StatusException> {
                        stub.createFile(CreateFileRequest.newBuilder().setPath(traversal).build())
                    }
                assertEquals(Status.Code.INVALID_ARGUMENT, create.status.code)
                assertEquals("Parent traversal components are not allowed: $traversal", create.status.description)
                val rename =
                    assertFailsWith<StatusException> {
                        stub.renameFile(
                            RenameFileRequest
                                .newBuilder()
                                .setSourcePath(traversal)
                                .setDestinationPath(destination.toString())
                                .build(),
                        )
                    }
                assertEquals(Status.Code.INVALID_ARGUMENT, rename.status.code)
                assertEquals("retained", Files.readString(source))
                assertFalse(Files.exists(destination))
                val rootEntry =
                    assertFailsWith<StatusException> {
                        stub.deleteFile(DeleteFileRequest.newBuilder().setPath(root.root.toString()).build())
                    }
                assertEquals(Status.Code.INVALID_ARGUMENT, rootEntry.status.code)
                assertEquals("An entry must have a parent directory", rootEntry.status.description)
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
                root.toFile().deleteRecursively()
            }
        }
}
