package ai.rever.boss.updater

import ai.rever.boss.updater.source.UpdateSource
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.sha256Of
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path

/**
 * End-to-end regression tests for the GitHub-fallback checksum (BossConsole#797).
 *
 * [UpdateFallbackChecksumTest] pins `downloadFrom`'s verification contract; these
 * drive the whole `downloadUpdate` chain through the CALL SITE that used to pass
 * `sha256 = null`: the primary URL is a dead port so the primary download fails,
 * the injected fake fallback source resolves the asset on a local HTTP server, and
 * the staged bytes are checked against the catalog hash on disk. Restoring
 * `sha256 = null` at the fallback call site stages the mismatching body, which
 * fails the first test here - the contract tests alone would stay green.
 *
 * The fake-fallback-source seam follows the design @Mihir-Rabari proposed in
 * #803 of the same #797 cluster; the injected staging dir keeps every byte the
 * tests stage inside their own temp dir.
 */
class UpdateServiceFallbackE2ETest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer

    /** The bytes served for the download - tests rewrite this per case. */
    private var servedBytes: ByteArray = ByteArray(0)

    private val goodBytes = "legitimate installer payload".repeat(64).toByteArray()
    private val tamperedBytes = "these bytes came from nowhere in the release pipeline".repeat(8).toByteArray()

    private val stagingDir: File
        get() = File(tempDir.toFile(), "staging")

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, servedBytes.size.toLong())
            exchange.responseBody.use { it.write(servedBytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun assetUrl(): String = "http://127.0.0.1:${server.address.port}/asset"

    /** A port with nothing listening on it, so the primary download fails fast. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    /** SHA-256 of [bytes] via the production hashing util, computed off a temp file. */
    private fun sha256OfBytes(bytes: ByteArray): String =
        sha256Of(
            File(tempDir.toFile(), "sha-src-${bytes.contentHashCode()}").apply {
                writeBytes(bytes)
            },
        )

    /** Fake fallback [UpdateSource] whose release is supplied lazily per test. */
    private class FakeFallbackSource(
        private val release: () -> GitHubRelease?,
    ) : UpdateSource {
        override val name: String = "fake-github"

        override suspend fun listReleases(): List<GitHubRelease> = listOfNotNull(release())

        override suspend fun getReleaseByTag(tag: String): GitHubRelease? = release()
    }

    /** The GitHub release the fake source serves, offering [assetName] at [downloadUrl]. */
    private fun fallbackRelease(
        version: Version,
        assetName: String,
        downloadUrl: String,
        size: Long,
    ): GitHubRelease =
        GitHubRelease(
            tag_name = "v$version",
            name = "v$version",
            body = "",
            published_at = "2026-09-17T00:00:00Z",
            assets =
                listOf(
                    GitHubAsset(
                        name = assetName,
                        browser_download_url = downloadUrl,
                        size = size,
                    ),
                ),
        )

    /** A catalog row whose primary URL is a dead port, so only the fallback can serve it. */
    private fun catalogRow(
        version: Version,
        assetName: String,
        size: Long,
        sha256: String?,
    ): UpdateInfo =
        UpdateInfo(
            available = true,
            currentVersion = Version.parse("9.0.0")!!,
            latestVersion = version,
            releaseNotes = "",
            downloadUrl = "http://127.0.0.1:${deadPort()}/primary-asset",
            assetSize = size,
            assetName = assetName,
            sha256 = sha256,
        )

    @Test
    fun `a fallback body that mismatches the catalog sha256 is discarded, not staged`() {
        servedBytes = tamperedBytes
        val version = Version.parse("9.9.9")!!
        var release: GitHubRelease? = null
        val service = UpdateService(FakeFallbackSource { release }, stagingDir)
        val assetName = service.getExpectedAssetName(version)
        release = fallbackRelease(version, assetName, assetUrl(), tamperedBytes.size.toLong())

        val path =
            runBlocking {
                service.downloadUpdate(
                    catalogRow(version, assetName, tamperedBytes.size.toLong(), sha256OfBytes(goodBytes)),
                ) {}
            }

        assertNull(path, "a mismatching fallback body must not reach the installer")
        assertFalse(
            File(stagingDir, assetName).exists(),
            "the mismatched body must not remain in the staging directory",
        )
    }

    @Test
    fun `a fallback body that matches the catalog sha256 is staged with the served bytes`() {
        servedBytes = goodBytes
        val version = Version.parse("9.9.10")!!
        var release: GitHubRelease? = null
        val service = UpdateService(FakeFallbackSource { release }, stagingDir)
        val assetName = service.getExpectedAssetName(version)
        release = fallbackRelease(version, assetName, assetUrl(), goodBytes.size.toLong())

        val path =
            runBlocking {
                service.downloadUpdate(
                    catalogRow(version, assetName, goodBytes.size.toLong(), sha256OfBytes(goodBytes)),
                ) {}
            }

        assertNotNull(path, "a matching fallback body must be staged for install")
        val staged = File(path)
        assertEquals(goodBytes.size.toLong(), staged.length())
        assertEquals(sha256OfBytes(goodBytes), sha256Of(staged))
    }

    @Test
    fun `a fallback body from a hash-less catalog row is refused, not staged`() {
        // A plain GitHub-only catalog row has no hash to describe the asset with,
        // and the body it offers feeds an elevated install. The #797-era posture
        // let it stage (and later install) with NO integrity check at all; the
        // download now refuses bytes nobody vouched for instead - as its own
        // user-facing refusal, before anything on disk is touched.
        servedBytes = goodBytes
        val version = Version.parse("9.9.11")!!
        var release: GitHubRelease? = null
        val service = UpdateService(FakeFallbackSource { release }, stagingDir)
        val assetName = service.getExpectedAssetName(version)
        release = fallbackRelease(version, assetName, assetUrl(), goodBytes.size.toLong())

        val refusal =
            assertThrows<UpdateDownloadRefusedException> {
                runBlocking {
                    service.downloadUpdate(catalogRow(version, assetName, goodBytes.size.toLong(), null)) {}
                }
            }

        // The reason is user-facing: it names the missing checksum, not a
        // generic "Failed to download update".
        assertTrue(
            refusal.message!!.contains("checksum"),
            "the refusal's reason must tell the user what could not be verified: ${refusal.message}",
        )
        assertFalse(
            File(stagingDir, assetName).exists(),
            "the hash-less body must not remain in the staging directory",
        )
        assertFalse(
            File(stagingDir, "$assetName.part").exists(),
            "no partial of the refused body may survive",
        )
    }
}
