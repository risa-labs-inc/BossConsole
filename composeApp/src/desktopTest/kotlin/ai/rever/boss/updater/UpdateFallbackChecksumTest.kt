package ai.rever.boss.updater

import ai.rever.boss.updater.source.GitHubUpdateSource
import ai.rever.boss.utils.sha256Of
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Regression tests for the GitHub-fallback checksum (BossConsole#797).
 *
 * The fallback download used to pass `sha256 = null`, so the one path that
 * runs precisely because a CDN was already misbehaving installed the staged
 * installer with NO integrity check — and the installer later runs elevated.
 * The fallback fetches the same asset of the same version the UpdateInfo row
 * describes, so the catalog's hash must bind those bytes exactly as it binds
 * the primary download's.
 *
 * These tests drive [UpdateService.downloadFrom] directly against a local
 * HTTP server, pinning the verification contract both download paths share:
 * a mismatching body is discarded and reported; a matching body is staged.
 */
class UpdateFallbackChecksumTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer
    private lateinit var service: UpdateService

    /** The bytes served for the download - tests rewrite this per case. */
    private var servedBytes: ByteArray = "initial".toByteArray()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, servedBytes.size.toLong())
            exchange.responseBody.use { it.write(servedBytes) }
        }
        server.start()
        service = UpdateService(GitHubUpdateSource(), stagingDir())
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun url(): String = "http://127.0.0.1:${server.address.port}/asset"

    /** The per-test staging directory the service stages into (never the shared app dir). */
    private fun stagingDir(): File = File(tempDir.toFile(), "staging")

    @Test
    fun `a fallback body whose hash mismatches the catalog is discarded, not staged`() {
        runBlocking {
            val expectedSha = sha256OfDifferentBytes()

            val result =
                service.downloadFrom(
                    url = url(),
                    assetName = "BOSS-9.9.9-Universal.dmg",
                    assetSize = servedBytes.size.toLong(),
                    sha256 = expectedSha,
                    onProgress = {},
                )

            assertNull(result, "a mismatching download must be discarded, not returned for install")
            val staged = File(stagingDir(), "BOSS-9.9.9-Universal.dmg")
            assertTrue(!staged.exists(), "the mismatched body must not remain in the staging directory")
        }
    }

    @Test
    fun `a fallback body whose hash matches the catalog is staged`() {
        runBlocking {
            val servedFile = File.createTempFile("served-bytes", ".bin", tempDir.toFile())
            servedFile.writeBytes(servedBytes)
            val expectedSha = sha256Of(servedFile)

            val result =
                service.downloadFrom(
                    url = url(),
                    assetName = "BOSS-9.9.9-Universal.dmg",
                    assetSize = servedBytes.size.toLong(),
                    sha256 = expectedSha,
                    onProgress = {},
                )

            assertNotNull(result, "a matching download must be staged for install")
            val staged = File(result)
            assertTrue(staged.exists() && staged.length() == servedBytes.size.toLong())
        }
    }

    /** The sha256Of helper used by the service, applied to unrelated bytes. */
    private fun sha256OfDifferentBytes(): String {
        val other = "definitely not the served bytes".toByteArray()
        val file = File.createTempFile("other-bytes", ".bin", tempDir.toFile())
        file.writeBytes(other)
        return sha256Of(file)
    }
}
