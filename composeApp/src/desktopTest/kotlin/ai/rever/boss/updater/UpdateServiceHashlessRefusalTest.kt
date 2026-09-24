package ai.rever.boss.updater

import ai.rever.boss.updater.source.GitHubUpdateSource
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.sha256Of
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

/**
 * Pins the hashless-catalog-row refusal's ordering and shape (BossConsole#1374
 * review):
 *
 * 1. The refusal must come BEFORE the clean-slate deletes: a hashless row
 *    refuses on the catalog row alone, so it must not destroy a good,
 *    already-verified staged update - nor its checksum marker, nor a leftover
 *    partial - just to reject a different body it never fetches.
 * 2. The refusal must not fetch the offered bytes at all: there is nothing to
 *    verify them against.
 * 3. The refusal must carry a user-facing reason
 *    ([UpdateDownloadRefusedException]), not a generic download error.
 *
 * [UpdateServiceFallbackE2ETest] drives the same refusal through the whole
 * `downloadUpdate` chain; this one pins [DesktopUpdateService.downloadFrom]'s
 * ordering directly, so moving the refusal below the deletes fails here even
 * if the end-to-end staging assertions stay green.
 */
class UpdateServiceHashlessRefusalTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer
    private lateinit var service: UpdateService

    /** Requests the local server actually served - must stay 0 for a refusal. */
    private var requestsServed: Int = 0

    private val stagedBytes = "a previously verified update payload".repeat(32).toByteArray()
    private val servedBytes = "unverified bytes the hash-less row offered".repeat(32).toByteArray()

    private val stagingDir: File
        get() = File(tempDir.toFile(), "staging")

    @BeforeEach
    fun startServer() {
        requestsServed = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            requestsServed++
            exchange.sendResponseHeaders(200, servedBytes.size.toLong())
            exchange.responseBody.use { it.write(servedBytes) }
        }
        server.start()
        service = UpdateService(GitHubUpdateSource(), stagingDir)
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun assetUrl(): String = "http://127.0.0.1:${server.address.port}/asset"

    @Test
    fun `a hash-less row is refused before the staged verified update is touched`() {
        val version = Version.parse("9.9.12")!!
        val assetName = service.getExpectedAssetName(version)
        val downloadFile = File(stagingDir, assetName)
        val partFile = File(stagingDir, "$assetName.part")
        val stagedSha = sha256OfBytes(stagedBytes)

        // A verified artifact from an earlier attempt: the published file, its
        // checksum marker, and a leftover partial are all present when the
        // hashless row arrives. createRestrictedDir adopts this directory
        // (owner-only), the same way it adopts one from a previous run.
        stagingDir.mkdirs()
        downloadFile.writeBytes(stagedBytes)
        UpdateArtifactIntegrityVet.bindVerifiedChecksum(downloadFile, stagedSha)
        partFile.writeBytes("leftover partial of an earlier attempt".toByteArray())

        val refusal =
            assertThrows<UpdateDownloadRefusedException> {
                runBlocking {
                    service.downloadFrom(
                        url = assetUrl(),
                        assetName = assetName,
                        assetSize = servedBytes.size.toLong(),
                        sha256 = null,
                        onProgress = {},
                    )
                }
            }

        // Nothing was fetched: bytes that could never be verified have no reason
        // to travel. (If the refusal moved below streamToFile this goes red.)
        assertEquals(0, requestsServed, "a hash-less row must be refused without fetching the body")
        // The staged verified artifact and its checksum marker survived the
        // refusal untouched. (If the refusal moved below the clean-slate deletes
        // this goes red: the deletes destroy exactly these.)
        assertEquals(stagedBytes.size.toLong(), downloadFile.length())
        assertEquals(stagedSha, sha256Of(downloadFile))
        assertEquals(stagedSha, UpdateArtifactIntegrityVet.checksumSidecarOf(downloadFile).readText())
        // The leftover partial survived too: the refusal is decided from the
        // catalog row alone and must not run the clean-slate deletes.
        assertTrue(partFile.exists(), "the clean-slate deletes must not run for a refusal")
        // The reason is user-facing: it names the checksum, not a generic failure.
        assertTrue(
            refusal.message!!.contains("checksum"),
            "the refusal's reason must tell the user what could not be verified: ${refusal.message}",
        )
    }

    @Test
    fun `a hash-less row is refused without leaving a new partial behind`() {
        // The refusal must be a clean no-op on disk even when nothing was
        // staged: no fetch happens, so no partial of the offered body may be
        // created by the refusal path itself.
        val version = Version.parse("9.9.13")!!
        val assetName = service.getExpectedAssetName(version)

        val refusal =
            assertThrows<UpdateDownloadRefusedException> {
                runBlocking {
                    service.downloadFrom(
                        url = assetUrl(),
                        assetName = assetName,
                        assetSize = servedBytes.size.toLong(),
                        sha256 = null,
                        onProgress = {},
                    )
                }
            }

        assertFalse(File(stagingDir, "$assetName.part").exists(), "no partial of the refused body may be created")
        assertFalse(File(stagingDir, assetName).exists(), "the refused body must not be staged")
        assertEquals(0, requestsServed, "a hash-less row must be refused without fetching the body")
        assertTrue(
            refusal.message!!.contains("checksum"),
            "the refusal's reason must tell the user what could not be verified: ${refusal.message}",
        )
    }

    /** SHA-256 of [bytes] via the production hashing util, computed off a temp file. */
    private fun sha256OfBytes(bytes: ByteArray): String =
        sha256Of(
            File(tempDir.toFile(), "sha-src-${bytes.contentHashCode()}").apply {
                writeBytes(bytes)
            },
        )
}
