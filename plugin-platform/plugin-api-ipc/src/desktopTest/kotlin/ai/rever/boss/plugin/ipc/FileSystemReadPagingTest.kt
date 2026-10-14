package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import ai.rever.boss.ipc.proto.services.ReadFileRequest
import ai.rever.boss.ipc.proto.services.ReadFileResponse
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemReadPagingTest {
    @Test
    fun `UTF8 characters split across pages are decoded once`() =
        runBlocking {
            val expected = "a".repeat(1_048_575) + "🙂tail"
            val bytes = expected.toByteArray()
            val offsets = mutableListOf<Long>()
            withService({ request ->
                offsets.add(request.offsetBytes)
                val start = request.offsetBytes.toInt()
                val count = minOf(request.maxBytes.toInt(), bytes.size - start)
                ReadFileResponse
                    .newBuilder()
                    .setContent(ByteString.copyFrom(bytes, start, count))
                    .setTotalSizeBytes(bytes.size.toLong())
                    .setTruncated(start + count < bytes.size)
                    .build()
            }) { proxy ->
                assertEquals(expected, proxy.readFile("test.txt").getOrThrow())
            }
            assertEquals(listOf(0L, 1_048_576L), offsets)
        }

    @Test
    fun `changing file size and oversized files fail instead of returning partial text`() =
        runBlocking {
            withService({ request ->
                ReadFileResponse
                    .newBuilder()
                    .setContent(ByteString.copyFromUtf8("a"))
                    .setTotalSizeBytes(if (request.offsetBytes == 0L) 2 else 3)
                    .setTruncated(request.offsetBytes == 0L)
                    .build()
            }) { proxy -> assertTrue(proxy.readFile("test").isFailure) }
            withService({
                ReadFileResponse.newBuilder().setTotalSizeBytes(8_388_609).build()
            }) { proxy -> assertTrue(proxy.readFile("test").isFailure) }
        }

    @Test
    fun `empty partial pages and service errors terminate the read`() =
        runBlocking {
            withService({ ReadFileResponse.newBuilder().setTruncated(true).build() }) { proxy ->
                assertTrue(proxy.readFile("test").isFailure)
            }
            withService({ ReadFileResponse.newBuilder().setErrorMessage("denied").build() }) { proxy ->
                assertEquals("denied", proxy.readFile("test").exceptionOrNull()?.message)
            }
        }

    private suspend fun withService(
        response: (ReadFileRequest) -> ReadFileResponse,
        action: suspend (FileSystemDataProviderProxy) -> Unit,
    ) {
        val service =
            object : FileSystemServiceGrpcKt.FileSystemServiceCoroutineImplBase() {
                override suspend fun readFile(request: ReadFileRequest) = response(request)
            }
        val server =
            ServerBuilder
                .forPort(0)
                .addService(service)
                .build()
                .start()
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        try {
            action(FileSystemDataProviderProxy(channel, channel))
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }
}
