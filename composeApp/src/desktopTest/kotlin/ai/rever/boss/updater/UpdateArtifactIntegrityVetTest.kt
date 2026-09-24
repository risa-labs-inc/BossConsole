package ai.rever.boss.updater

import ai.rever.boss.updater.source.GitHubUpdateSource
import ai.rever.boss.utils.sha256Of
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fail-closed integrity pins for the desktop app's own self-update path: what was
 * verified at download time must still hold at the install boundary, and an update
 * whose manifest cannot vouch for its bytes must never be staged or run.
 *
 * The download path used to verify only when the catalog row happened to carry a
 * sha256, staged whatever it downloaded directly under the install name, and the
 * installer then executed those bytes with the user's privileges - msiexec on
 * Windows, the jar-overwrite fallback that replaces the running jar in place -
 * without re-checking anything. A GitHub-only catalog row (no hashes) installed
 * with NO integrity check at all, and anything replaced in the staging directory
 * between download and the Install click was equally invisible. Now the verified
 * checksum is bound at download and re-verified before a single installer command
 * runs, and a hashless manifest is refused outright - the posture the plugin lane
 * calls UpdateJarIdentityVet (#947) and the engine lane EngineArchiveIntegrityVet.
 */
class UpdateArtifactIntegrityVetTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer

    /** The bytes served for a download - tests rewrite this per case. */
    private var servedBytes: ByteArray = "legitimate installer payload".repeat(32).toByteArray()

    /** Requests the local server actually served - must stay 0 for a refused download. */
    private var requestsServed: Int = 0

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun url(): String = "http://127.0.0.1:${server.address.port}/asset"

    /** The per-test staging directory downloads land in (never the shared app dir). */
    private fun stagingDir(): File = File(tempDir.toFile(), "staging")

    /** SHA-256 of [bytes] via the production hashing util, computed off a temp file. */
    private fun sha256OfBytes(bytes: ByteArray): String =
        sha256Of(
            File(tempDir.toFile(), "sha-src-${bytes.contentHashCode()}").apply { writeBytes(bytes) },
        )

    /** A staged artifact named like a real release asset, carrying installer-ish bytes. */
    private fun stagedArtifact(): File {
        val staging = File(tempDir.toFile(), "staging").also { it.mkdirs() }
        return File(staging, "BOSS-999.0.0-amd64.jar").also { it.writeBytes(servedBytes) }
    }

    /** Serve the current [servedBytes] at /asset. */
    private fun serveAsset() {
        server.createContext("/asset") { exchange ->
            requestsServed++
            exchange.sendResponseHeaders(200, servedBytes.size.toLong())
            exchange.responseBody.use { it.write(servedBytes) }
        }
    }

    // ==================== The vet itself ====================

    @Test
    fun `an artifact whose bytes still match the bound checksum passes the vet`() {
        val artifact = stagedArtifact()
        UpdateArtifactIntegrityVet.bindVerifiedChecksum(artifact, sha256Of(artifact))

        UpdateArtifactIntegrityVet.requireVerifiedChecksum(artifact)
    }

    @Test
    fun `an artifact tampered after download is refused by the vet`() {
        val artifact = stagedArtifact()
        UpdateArtifactIntegrityVet.bindVerifiedChecksum(artifact, sha256Of(artifact))
        artifact.appendBytes("swapped in the staging directory".toByteArray())

        val refusal =
            assertThrows<SecurityException> {
                UpdateArtifactIntegrityVet.requireVerifiedChecksum(artifact)
            }
        assertTrue(
            refusal.message?.contains("no longer matches its verified checksum") == true,
            "the refusal must name the checksum mismatch, got: ${refusal.message}",
        )
    }

    @Test
    fun `an artifact with no bound checksum is refused by the vet`() {
        val artifact = stagedArtifact()

        val refusal =
            assertThrows<SecurityException> {
                UpdateArtifactIntegrityVet.requireVerifiedChecksum(artifact)
            }
        assertTrue(
            refusal.message?.contains("no verified checksum is bound") == true,
            "a staged artifact nobody vouched for must be refused, got: ${refusal.message}",
        )
    }

    @Test
    fun `a malformed checksum marker is refused by the vet`() {
        val artifact = stagedArtifact()
        UpdateArtifactIntegrityVet.checksumSidecarOf(artifact).writeText("not-a-hash")

        val refusal =
            assertThrows<SecurityException> {
                UpdateArtifactIntegrityVet.requireVerifiedChecksum(artifact)
            }
        assertTrue(
            refusal.message?.contains("malformed") == true,
            "a corrupt marker must be refused, got: ${refusal.message}",
        )
    }

    // ==================== The install boundary ====================

    @Test
    fun `the install boundary refuses a staged artifact that was tampered after download`() {
        val artifact = stagedArtifact()
        UpdateArtifactIntegrityVet.bindVerifiedChecksum(artifact, sha256Of(artifact))
        val tampered = "swapped after verification".toByteArray()
        artifact.writeBytes(tampered)

        val result = runBlocking { UpdateInstaller.installUpdate(artifact.absolutePath, stagingDir()) }

        assertTrue(result is InstallResult.Error, "a tampered artifact must not install, got: $result")
        val refusal = result as InstallResult.Error
        assertTrue(
            refusal.message.contains("no longer matches its verified checksum"),
            "the refusal must name the checksum mismatch, got: ${refusal.message}",
        )
        // A refusal happens before any installer command runs: the staged bytes are
        // left exactly as they were, and nothing consumed or replaced them.
        assertEquals(tampered.size.toLong(), artifact.length())
    }

    @Test
    fun `the install boundary refuses an artifact nobody vouched for`() {
        val artifact = stagedArtifact()

        val result = runBlocking { UpdateInstaller.installUpdate(artifact.absolutePath, stagingDir()) }

        assertTrue(result is InstallResult.Error, "an unverified artifact must not install, got: $result")
        val refusal = result as InstallResult.Error
        assertTrue(
            refusal.message.contains("no verified checksum is bound"),
            "a hashless-era leftover must be refused, got: ${refusal.message}",
        )
        assertTrue(
            artifact.exists(),
            "the refused artifact is left untouched; the previous install keeps running",
        )
    }

    // ==================== The download gate ====================

    @Test
    fun `a marker binding failure removes the published artifact and partial marker`() {
        serveAsset()
        val assetName = "BOSS-999.0.0-Universal.dmg"
        val service =
            UpdateService(
                GitHubUpdateSource(),
                stagingDir(),
                bindVerifiedChecksum = { artifact, _ ->
                    UpdateArtifactIntegrityVet.checksumSidecarOf(artifact).writeText("partial")
                    throw SecurityException("simulated marker write failure")
                },
            )

        val path =
            runBlocking {
                service.downloadFrom(url(), assetName, servedBytes.size.toLong(), sha256OfBytes(servedBytes)) {}
            }

        assertNull(path)
        val artifact = File(stagingDir(), assetName)
        assertTrue(!artifact.exists(), "a published artifact without a marker must be discarded")
        assertTrue(!UpdateArtifactIntegrityVet.checksumSidecarOf(artifact).exists())
    }

    @Test
    fun `an outside artifact with a valid marker is rejected before integrity vetting`() {
        stagingDir().mkdirs()
        val outside = File(tempDir.toFile(), "BOSS-999.0.0-amd64.jar").also { it.writeBytes(servedBytes) }
        UpdateArtifactIntegrityVet.bindVerifiedChecksum(outside, sha256Of(outside))

        val result = runBlocking { UpdateInstaller.installUpdate(outside.absolutePath, stagingDir()) }

        assertTrue(result is InstallResult.Error)
        assertTrue((result as InstallResult.Error).message.contains("outside the staging directory"))
        assertTrue(outside.exists())
    }

    @Test
    fun `a hash-less manifest is refused rather than staged unverified`() {
        serveAsset()
        val service = UpdateService(GitHubUpdateSource(), stagingDir())
        val assetName = "BOSS-999.0.0-Universal.dmg"

        // A hash-less row is refused as its own typed answer with a user-facing
        // reason (UpdateDownloadRefusedException) instead of a null that
        // downstream flattens into a generic "Failed to download update".
        assertThrows<UpdateDownloadRefusedException> {
            runBlocking {
                service.downloadFrom(url(), assetName, servedBytes.size.toLong(), null) {}
            }
        }

        assertEquals(0, requestsServed, "a hash-less manifest must be refused without fetching the body")
        val staging = stagingDir()
        assertTrue(
            !File(staging, assetName).exists(),
            "nothing may be published under the install name",
        )
        assertTrue(
            !File(staging, "$assetName.part").exists(),
            "the refused body must not remain as a partial",
        )
        assertTrue(
            !UpdateArtifactIntegrityVet.checksumSidecarOf(File(staging, assetName)).exists(),
            "no checksum marker may exist for a refused download",
        )
    }

    @Test
    fun `a verified download is published with its checksum bound for the install boundary`() {
        serveAsset()
        val service = UpdateService(GitHubUpdateSource(), stagingDir())
        val assetName = "BOSS-999.0.0-Universal.dmg"

        val path =
            runBlocking {
                service.downloadFrom(
                    url(),
                    assetName,
                    servedBytes.size.toLong(),
                    sha256OfBytes(servedBytes),
                ) {}
            }

        assertNotNull(path, "a body matching the catalog hash must be staged for install")
        val staged = File(path)
        assertEquals(servedBytes.size.toLong(), staged.length(), "the published bytes must be the served bytes")
        assertTrue(
            !File(stagingDir(), "$assetName.part").exists(),
            "the partial must be gone once the download is published",
        )
        val marker = UpdateArtifactIntegrityVet.checksumSidecarOf(staged)
        assertTrue(marker.exists(), "the verified checksum must be bound beside the published artifact")
        assertEquals(sha256Of(staged), marker.readText(), "the marker must carry the verified checksum")
    }

    @Test
    fun `a download in flight never appears under the install name`() {
        val head = servedBytes.copyOfRange(0, 16)
        val wroteHead = CountDownLatch(1)
        val holdOpen = CountDownLatch(1)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, servedBytes.size.toLong())
            exchange.responseBody.use {
                it.write(head)
                it.flush()
                wroteHead.countDown()
                holdOpen.await(10, TimeUnit.SECONDS)
                it.write(servedBytes.copyOfRange(head.size, servedBytes.size))
            }
        }
        val service = UpdateService(GitHubUpdateSource(), stagingDir())
        val assetName = "BOSS-999.0.0-Universal.dmg"
        val part = File(stagingDir(), "$assetName.part")
        val staged = File(stagingDir(), assetName)

        val path =
            runBlocking {
                val download =
                    async(Dispatchers.IO) {
                        service.downloadFrom(
                            url(),
                            assetName,
                            servedBytes.size.toLong(),
                            sha256OfBytes(servedBytes),
                        ) {}
                    }
                assertTrue(wroteHead.await(10, TimeUnit.SECONDS), "the server must have sent the head bytes")
                withTimeout(10_000) {
                    while (!part.exists() || part.length() < head.size) {
                        delay(20)
                    }
                }
                assertEquals(
                    head.size.toLong(),
                    part.length(),
                    "only the head bytes have arrived so far",
                )
                assertTrue(
                    !staged.exists(),
                    "a partial download must not sit under the name the installer runs",
                )
                holdOpen.countDown()
                download.await()
            }

        assertNotNull(path, "the completed download must be published once it verifies")
        assertEquals(servedBytes.size.toLong(), File(path).length(), "the published bytes must be complete")
        assertTrue(
            !File(stagingDir(), "$assetName.part").exists(),
            "the partial must be gone once the download is published",
        )
    }
}
