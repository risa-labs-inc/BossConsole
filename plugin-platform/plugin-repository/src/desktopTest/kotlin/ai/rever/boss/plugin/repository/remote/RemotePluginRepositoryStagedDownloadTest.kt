package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.loader.PluginSignatureVerifier
import ai.rever.boss.plugin.loader.PluginStoreTrust
import ai.rever.boss.plugin.repository.DownloadException
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the [RemotePluginRepository.downloadPlugin] staging-paths
 * guarantee: a download MUST NOT overwrite the live JAR until its bytes
 * have been verified, and a rejected/interrupted download MUST leave both
 * the previously installed JAR and its `.sig` sidecar untouched.
 *
 * The wiring tests in [RemotePluginRepositoryDownloadTest] already cover
 * the happy path and signature verification; this suite pins the staging
 * shape itself so a regression that re-introduces in-place overwrites
 * cannot pass the build.
 */
class RemotePluginRepositoryStagedDownloadTest {
    private val tempDir = createTempDirectory("dl-staging-test").toFile()
    private val cache = PluginDownloadCache(File(tempDir, "cache"))

    private val keyPair: KeyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply {
                initialize(2048)
            }.generateKeyPair()

    private val verifier =
        PluginSignatureVerifier(
            mapOf(
                "test-store" to (
                    "-----BEGIN PUBLIC KEY-----\n" +
                        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
                        "\n-----END PUBLIC KEY-----"
                ),
            ),
        )

    private val pluginId = "test.plugin"
    private val goodBytes = "fake jar bytes for staging test".toByteArray()
    private val goodSha256 =
        MessageDigest
            .getInstance("SHA-256")
            .digest(goodBytes)
            .joinToString("") { "%02x".format(it) }

    private var server: HttpServer? = null
    private var serverUrl: String = ""

    @BeforeTest
    fun setup() {
        PluginStoreConfig.initialize("http://127.0.0.1:1/functions/v1", "test-anon-key")
    }

    @AfterTest
    fun cleanup() {
        server?.stop(0)
        PluginStoreConfig.clear()
        tempDir.deleteRecursively()
    }

    private fun startServer(handler: HttpHandler): String {
        val http =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/jar", handler)
                start()
            }
        server = http
        return "http://127.0.0.1:${http.address.port}/jar"
    }

    private fun signAnchor(version: String): String {
        val anchor = PluginStoreTrust.versionAnchor(pluginId, version, goodSha256)
        val sig =
            Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(anchor.toByteArray(Charsets.UTF_8))
            }
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun repositoryReturning(info: DownloadInfoResponse) =
        RemotePluginRepository(
            downloadCache = cache,
            storeVerifier = verifier,
            downloadInfoProvider = { _, _ -> info },
        )

    private fun downloadInfo(
        version: String,
        signature: String?,
    ) = DownloadInfoResponse(
        downloadUrl = serverUrl,
        sha256 = goodSha256,
        version = version,
        size = goodBytes.size.toLong(),
        versionId = UUID.randomUUID().toString(),
        signature = signature,
    )

    private fun target(name: String) = File(tempDir, name).absolutePath

    private fun partSiblings(targetPath: String): List<File> {
        val parent = File(targetPath).parentFile ?: tempDir
        return (parent.listFiles() ?: emptyArray()).filter { it.name.endsWith(".part") }
    }

    private fun writeLive(
        targetPath: String,
        contents: String,
        sidecar: String? = null,
    ) {
        File(targetPath).writeText(contents)
        if (sidecar != null) {
            PluginSignatureSidecar.persist(targetPath, sidecar)
        }
    }

    @Test
    fun `successful download replaces live jar and leaves no part file behind`() =
        runBlocking {
            serverUrl =
                startServer { exchange ->
                    exchange.sendResponseHeaders(200, goodBytes.size.toLong())
                    exchange.responseBody.use { it.write(goodBytes) }
                }
            val sig = signAnchor("1.0.0")
            val path =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", target("ok.jar"))
                    .getOrThrow()
            assertEquals(target("ok.jar"), path)
            assertTrue(File(path).readBytes().contentEquals(goodBytes))
            assertEquals(sig, PluginSignatureSidecar.read(path))
            // No staging residue left behind in the plugin directory.
            assertTrue(partSiblings(path).isEmpty(), "a .part file was left behind on success")
        }

    @Test
    fun `interrupted download leaves a previously installed live jar and sidecar untouched`() =
        runBlocking {
            // Server returns 0 bytes successfully - ktor's channel sees
            // isClosedForRead immediately, the staged file is empty, and
            // the SHA-256 check fails. Any path that hands the staging
            // shape a failed download must leave the live JAR alone.
            serverUrl =
                startServer { exchange ->
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { /* no bytes */ }
                }
            val previous = "previously installed live jar bytes"
            val previousSidecar = "previously-written-signature"
            writeLive(target("interrupted.jar"), previous, previousSidecar)

            val sig = signAnchor("1.0.0")
            val result =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", target("interrupted.jar"))

            assertIs<DownloadException>(result.exceptionOrNull())
            // The previously installed JAR and its sidecar are intact.
            assertEquals(previous, File(target("interrupted.jar")).readText())
            assertEquals(previousSidecar, PluginSignatureSidecar.read(target("interrupted.jar")))
            // No staging residue was promoted into the live path.
            val parts = partSiblings(target("interrupted.jar"))
            assertTrue(parts.isEmpty(), "a .part file was left behind after a failed download: $parts")
        }

    @Test
    fun `download with bad signature leaves live jar and sidecar untouched`() =
        runBlocking {
            serverUrl =
                startServer { exchange ->
                    exchange.sendResponseHeaders(200, goodBytes.size.toLong())
                    exchange.responseBody.use { it.write(goodBytes) }
                }
            val previous = "previously installed live jar bytes"
            val previousSidecar = "previously-written-signature"
            writeLive(target("badsig.jar"), previous, previousSidecar)

            // Signature legitimately covers a DIFFERENT anchor — the
            // substitution scenario, so enforceStoreSignature throws.
            val result =
                repositoryReturning(downloadInfo("1.0.0", signAnchor("9.9.9")))
                    .downloadPlugin(pluginId, "1.0.0", target("badsig.jar"))

            assertIs<DownloadException>(result.exceptionOrNull())
            assertEquals(previous, File(target("badsig.jar")).readText())
            assertEquals(previousSidecar, PluginSignatureSidecar.read(target("badsig.jar")))
            assertTrue(partSiblings(target("badsig.jar")).isEmpty())
        }

    @Test
    fun `download with bad hash leaves live jar untouched and discards the part file`() =
        runBlocking {
            serverUrl =
                startServer { exchange ->
                    // The server returns bytes whose SHA-256 doesn't match
                    // downloadInfo.sha256, simulating a tampered download.
                    val tampered = "tampered bytes that do not hash to the advertised digest".toByteArray()
                    exchange.sendResponseHeaders(200, tampered.size.toLong())
                    exchange.responseBody.use { it.write(tampered) }
                }
            val previous = "previously installed live jar bytes"
            val previousSidecar = "previously-written-signature"
            writeLive(target("badhash.jar"), previous, previousSidecar)

            val sig = signAnchor("1.0.0")
            val result =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", target("badhash.jar"))

            assertIs<DownloadException>(result.exceptionOrNull())
            assertEquals(previous, File(target("badhash.jar")).readText())
            assertEquals(previousSidecar, PluginSignatureSidecar.read(target("badhash.jar")))
            assertTrue(partSiblings(target("badhash.jar")).isEmpty())
        }

    @Test
    fun `download replaces a symlink at the target path rather than following it`() =
        runBlocking {
            serverUrl =
                startServer { exchange ->
                    exchange.sendResponseHeaders(200, goodBytes.size.toLong())
                    exchange.responseBody.use { it.write(goodBytes) }
                }
            val sig = signAnchor("1.0.0")

            // Sentinel lives outside the plugin directory; a naive
            // in-place write would follow the symlink and overwrite the
            // sentinel. The fix replaces the symlink entry itself.
            val outsideDir = File(tempDir, "outside").also { it.mkdirs() }
            val sentinel = File(outsideDir, "sentinel.jar").apply { writeText("sentinel bytes") }
            val linkedFile = File(tempDir, "linked.jar")
            // The test fixture directory is fresh per test, so no pre-existing
            // file at this path to clean up before placing the symlink.
            Files.createSymbolicLink(linkedFile.toPath(), sentinel.toPath())
            val linkedPath = linkedFile.absolutePath

            val path =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", linkedPath)
                    .getOrThrow()
            assertEquals(linkedPath, path)
            assertEquals(goodBytes.toString(Charsets.UTF_8), File(linkedPath).readText())
            // The sentinel itself was NOT modified.
            assertEquals("sentinel bytes", sentinel.readText())
            assertEquals(sig, PluginSignatureSidecar.read(linkedPath))
        }

    @Test
    fun `cache hit does not overwrite the live jar if the cache copy itself fails`() =
        runBlocking {
            val seed = File(tempDir, "seed.jar").apply { writeBytes(goodBytes) }
            cache.cacheJar(pluginId, "1.0.0", seed)
            val previous = "previously installed live jar bytes"
            val previousSidecar = "previously-written-signature"
            writeLive(target("cache-broken.jar"), previous, previousSidecar)

            val sig = signAnchor("1.0.0")
            // Force copyCachedJar to throw - the cache hit path must
            // surface the failure and leave the live path untouched.
            val broken =
                RemotePluginRepository(
                    downloadCache = cache,
                    storeVerifier = verifier,
                    downloadInfoProvider = { _, _ -> downloadInfo("1.0.0", sig) },
                    copyCachedJar = { _, _ -> throw java.io.IOException("simulated cache copy failure") },
                )
            val result = broken.downloadPlugin(pluginId, "1.0.0", target("cache-broken.jar"))
            assertTrue(result.isFailure)
            assertEquals(previous, File(target("cache-broken.jar")).readText())
            assertEquals(previousSidecar, PluginSignatureSidecar.read(target("cache-broken.jar")))
        }

    @Test
    fun `cache hit promotes the cached jar over an existing live jar`() =
        runBlocking {
            // Network is never reached - proves the cache hit path also
            // stages into .part before promoting over the live jar.
            serverUrl =
                startServer { exchange ->
                    exchange.sendResponseHeaders(500, 0)
                    exchange.close()
                }
            val seed = File(tempDir, "seed.jar").apply { writeBytes(goodBytes) }
            cache.cacheJar(pluginId, "1.0.0", seed)

            val previous = "previously installed live jar bytes"
            val previousSidecar = "previously-written-signature"
            writeLive(target("cache-hit.jar"), previous, previousSidecar)

            val sig = signAnchor("1.0.0")
            val path =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", target("cache-hit.jar"))
                    .getOrThrow()
            assertEquals(target("cache-hit.jar"), path)
            assertTrue(File(path).readBytes().contentEquals(goodBytes))
            assertEquals(sig, PluginSignatureSidecar.read(path))
            assertTrue(partSiblings(path).isEmpty())
        }

    @Test
    fun `failed staging allocation clears the download progress entry`() =
        runBlocking {
            // No server needed - the failure is at staging allocation, before
            // any network call. A target under a parent that does not exist
            // makes Files.createTempFile throw NoSuchFileException. The bug
            // it pins: downloadProgress[pluginId] is set BEFORE the staging
            // allocation, so a failing allocation used to leave a stale flow
            // registered under that id - silently suppressing every later
            // progress report for the plugin.
            val impossiblePath = File(tempDir, "missing-parent/never.jar").absolutePath

            val sig = signAnchor("1.0.0")
            val repo = repositoryReturning(downloadInfo("1.0.0", sig))
            val result = repo.downloadPlugin(pluginId, "1.0.0", impossiblePath)

            assertTrue(result.isFailure)
            assertEquals(null, repo.getDownloadProgress(pluginId))
        }

    @Test
    fun `cache hit with SHA-256 mismatch between cached and staged bytes leaves live jar untouched`() =
        runBlocking {
            // Cache file is valid at lookup (its hash matches downloadInfo.sha256),
            // but the bytes that actually arrive in the staged file are NOT those
            // bytes - simulating a cache file that was replaced between the
            // lookup-time hash check and the copy. The pre-fix code promoted the
            // staged bytes without re-checking, so a tampered cache landed at the
            // live JAR. The fix rehashes the staged bytes and refuses to promote
            // on a mismatch.
            val seed = File(tempDir, "seed.jar").apply { writeBytes(goodBytes) }
            cache.cacheJar(pluginId, "1.0.0", seed)

            val previous = "previously installed live jar bytes"
            val previousSidecar = "previously-written-signature"
            writeLive(target("cache-tampered.jar"), previous, previousSidecar)

            val sig = signAnchor("1.0.0")
            val tampered = "tampered cache bytes that hash to something else".toByteArray()
            val corrupting =
                RemotePluginRepository(
                    downloadCache = cache,
                    storeVerifier = verifier,
                    downloadInfoProvider = { _, _ -> downloadInfo("1.0.0", sig) },
                    // Simulate the cache file being replaced between the
                    // getCachedJar hash check and this copy: overwrite the
                    // source first, then copy. The staged file ends up
                    // holding the tampered bytes whose hash differs from
                    // downloadInfo.sha256.
                    copyCachedJar = { source, target ->
                        source.writeBytes(tampered)
                        source.copyTo(target, overwrite = true)
                    },
                )

            val result =
                corrupting.downloadPlugin(pluginId, "1.0.0", target("cache-tampered.jar"))

            // The mismatch is a security failure, surfaced as a DownloadException.
            assertIs<DownloadException>(result.exceptionOrNull())
            // The previously installed JAR and its sidecar are intact.
            assertEquals(previous, File(target("cache-tampered.jar")).readText())
            assertEquals(previousSidecar, PluginSignatureSidecar.read(target("cache-tampered.jar")))
            // No staging residue was promoted into the live path.
            assertTrue(partSiblings(target("cache-tampered.jar")).isEmpty())
        }

    @Test
    fun `truncated fresh download leaves live jar untouched`() =
        runBlocking {
            // Server advertises the full Content-Length but closes the response
            // body partway through, so the streamed bytes are fewer than the
            // staged file is supposed to hold. The pre-fix code promoted the
            // truncated bytes regardless; the fix hashes the staged bytes and
            // refuses to promote on a mismatch. (Distinct from the empty-body
            // test above: that one was honest about the size, this one lies.)
            serverUrl =
                startServer { exchange ->
                    exchange.sendResponseHeaders(200, goodBytes.size.toLong())
                    val truncatedLength = goodBytes.size / 2
                    exchange.responseBody.use { it.write(goodBytes, 0, truncatedLength) }
                }
            val previous = "previously installed live jar bytes"
            val previousSidecar = "previously-written-signature"
            writeLive(target("truncated.jar"), previous, previousSidecar)

            val sig = signAnchor("1.0.0")
            val result =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", target("truncated.jar"))

            assertIs<DownloadException>(result.exceptionOrNull())
            assertEquals(previous, File(target("truncated.jar")).readText())
            assertEquals(previousSidecar, PluginSignatureSidecar.read(target("truncated.jar")))
            assertTrue(partSiblings(target("truncated.jar")).isEmpty())
        }
}
