package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.services.ReadFileRequest
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import ai.rever.boss.ipc.proto.services.WriteFileRequest
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNoException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemPathPolicyTest {
    @Test
    fun `existing links and missing descendants cannot enter blocked roots`() {
        val root = Files.createTempDirectory("path-policy-").toRealPath()
        try {
            val blocked = Files.createDirectory(root.resolve("blocked"))
            val allowed = Files.createDirectory(root.resolve("blocked-neighbor"))
            val policy = FileSystemPathPolicy(listOf(blocked))
            policy.validate(allowed.resolve("new.txt").toString())
            assertPermissionDenied { policy.resolve(blocked.resolve("missing/child")) }
            val link = root.resolve("alias")
            try {
                Files.createSymbolicLink(link, blocked)
            } catch (e: Exception) {
                assumeNoException("Symbolic links unavailable", e)
            }
            assertPermissionDenied { policy.resolve(link.resolve("new/child")) }
            assertPermissionDenied { policy.resolve(link) }
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `invalid path and traversal return descriptive invalid argument`() {
        val policy = FileSystemPathPolicy(emptyList())
        assertDenied { policy.validate("/tmp/../blocked") }
        assertDenied { policy.validate("bad\u0000path") }
    }

    @Test
    fun `ordinary dotted names are not parent traversal`() {
        val root = Files.createTempDirectory("path-policy-dots-").toRealPath()
        try {
            val policy = FileSystemPathPolicy(emptyList())
            for (name in listOf("report..txt", "..hidden", "a..b")) {
                policy.validate(root.resolve(name).toString())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `host RPC validation returns invalid argument instead of unknown`() =
        runBlocking {
            AuthenticatedFileService(FileSystemServiceImpl()).use { transport ->
                val stub = AuthenticatedFileService.stub(transport.channelFor("host", ProcessAuthority.HOST))
                val path = "invalid/../path"
                val read =
                    assertFailsWith<StatusException> {
                        stub.readFile(ReadFileRequest.newBuilder().setPath(path).build())
                    }
                val write =
                    assertFailsWith<StatusException> {
                        stub.writeFile(WriteFileRequest.newBuilder().setPath(path).build())
                    }
                val scan =
                    assertFailsWith<StatusException> {
                        stub.scanDirectory(ScanDirectoryRequest.newBuilder().setPath(path).build())
                    }
                listOf(read, write, scan).forEach { assertEquals(Status.Code.INVALID_ARGUMENT, it.status.code) }
            }
        }

    private fun assertDenied(block: () -> Unit) {
        val failure = assertFailsWith<StatusRuntimeException>(block = block)
        assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
    }

    private fun assertPermissionDenied(block: () -> Unit) {
        val failure = assertFailsWith<FilePathDeniedException>(block = block)
        assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
    }
}
