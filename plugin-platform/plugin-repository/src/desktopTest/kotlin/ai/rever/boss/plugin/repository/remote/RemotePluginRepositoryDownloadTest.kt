package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.loader.PluginSignatureVerifier
import ai.rever.boss.plugin.loader.PluginStoreTrust
import ai.rever.boss.plugin.repository.DownloadException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test of the [RemotePluginRepository.downloadPlugin] WIRING —
 * that both the cache-hit path and the fresh-download path actually invoke
 * signature enforcement, and that verification happens BEFORE caching. The
 * policy function itself is covered by [RemotePluginRepositorySignatureTest];
 * this suite exists so a refactor that drops or reorders one of the two
 * call sites cannot pass the build.
 */
class RemotePluginRepositoryDownloadTest {
    private val tempDir = createTempDirectory("dl-wiring-test").toFile()
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
    private val jarBytes = "fake jar bytes for wiring test".toByteArray()
    private val jarSha256 =
        MessageDigest
            .getInstance("SHA-256")
            .digest(jarBytes)
            .joinToString("") { "%02x".format(it) }

    private var server: HttpServer? = null
    private var serverUrl: String = ""

    @BeforeTest
    fun setup() {
        PluginStoreConfig.initialize("http://127.0.0.1:1/functions/v1", "test-anon-key")
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/jar") { exchange ->
                    exchange.sendResponseHeaders(200, jarBytes.size.toLong())
                    exchange.responseBody.use { it.write(jarBytes) }
                }
                start()
            }
        serverUrl = "http://127.0.0.1:${server!!.address.port}/jar"
    }

    @AfterTest
    fun cleanup() {
        server?.stop(0)
        PluginStoreConfig.clear()
        tempDir.deleteRecursively()
    }

    private fun signAnchor(version: String): String {
        val anchor = PluginStoreTrust.versionAnchor(pluginId, version, jarSha256)
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
        sha256 = jarSha256,
        version = version,
        size = jarBytes.size.toLong(),
        versionId = UUID.randomUUID().toString(),
        signature = signature,
    )

    private fun target(name: String) = File(tempDir, name).absolutePath

    @Test
    fun `cache removed after lookup falls back to a verified download`() =
        runBlocking<Unit> {
            val source = File(tempDir, "source.jar").apply { writeBytes(jarBytes) }
            cache.cacheJar(pluginId, "1.0.0", source)
            val signature = signAnchor("1.0.0")
            var copies = 0
            val repository =
                RemotePluginRepository(
                    downloadCache = cache,
                    storeVerifier = verifier,
                    downloadInfoProvider = { _, _ -> downloadInfo("1.0.0", signature) },
                    copyCachedJar = { cached, destination ->
                        copies++
                        Files.delete(cached.toPath())
                        cached.copyTo(destination, overwrite = true)
                    },
                )
            val installed = repository.downloadPlugin(pluginId, "1.0.0", target("lost-cache.jar")).getOrThrow()
            assertEquals(1, copies)
            assertTrue(File(installed).readBytes().contentEquals(jarBytes))
            assertEquals(signature, PluginSignatureSidecar.read(installed))
        }

    @Test
    fun `fresh download with valid signature succeeds, caches, and writes the sidecar`() =
        runBlocking<Unit> {
            val sig = signAnchor("1.0.0")
            val result =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", target("fresh-ok.jar"))

            val path = result.getOrThrow()
            assertTrue(File(path).readBytes().contentEquals(jarBytes))
            assertNotNull(cache.getCachedJar(pluginId, "1.0.0", jarSha256))
            // Sidecar written beside the installed JAR for load-time verification.
            assertEquals(sig, PluginSignatureSidecar.read(path))
        }

    @Test
    fun `rejected fresh download preserves the installed jar and sidecar`() =
        runBlocking<Unit> {
            val installed = File(target("installed.jar")).apply { writeText("working plugin") }
            val installedSignature = "signature for working plugin"
            PluginSignatureSidecar.persist(installed.absolutePath, installedSignature)

            val result =
                repositoryReturning(downloadInfo("2.0.0", signAnchor("1.0.0")))
                    .downloadPlugin(pluginId, "2.0.0", installed.absolutePath)

            assertIs<DownloadException>(result.exceptionOrNull())
            assertEquals("working plugin", installed.readText())
            assertEquals(installedSignature, PluginSignatureSidecar.read(installed.absolutePath))
            assertFalse(tempDir.listFiles().orEmpty().any { it.name.endsWith(".part") })
        }

    @Test
    fun `failed cache copy and fallback leave the installed jar intact`() =
        runBlocking<Unit> {
            val seed = File(tempDir, "cached-source.jar").apply { writeBytes(jarBytes) }
            cache.cacheJar(pluginId, "1.0.0", seed)
            val installed = File(target("cache-copy-target.jar")).apply { writeText("working plugin") }
            val repository =
                RemotePluginRepository(
                    downloadCache = cache,
                    storeVerifier = verifier,
                    downloadInfoProvider = { _, _ ->
                        downloadInfo("1.0.0", signAnchor("1.0.0")).copy(
                            downloadUrl = "http://127.0.0.1:1/unreachable",
                        )
                    },
                    copyCachedJar = { _, destination ->
                        destination.writeText("partial replacement")
                        throw IOException("simulated cache copy failure")
                    },
                )

            val result = repository.downloadPlugin(pluginId, "1.0.0", installed.absolutePath)

            assertTrue(result.isFailure)
            assertEquals("working plugin", installed.readText())
            assertFalse(tempDir.listFiles().orEmpty().any { it.name.endsWith(".part") })
        }

    @Test
    fun `verified download replaces a target symlink instead of following it`() =
        runBlocking<Unit> {
            val outside = File(tempDir, "outside-sentinel.jar").apply { writeText("do not overwrite") }
            val target = File(tempDir, "linked-target.jar")
            val symlinkCreated = runCatching { Files.createSymbolicLink(target.toPath(), outside.toPath()) }.isSuccess
            if (!symlinkCreated) return@runBlocking

            val signature = signAnchor("1.0.0")
            val path =
                repositoryReturning(downloadInfo("1.0.0", signature))
                    .downloadPlugin(pluginId, "1.0.0", target.absolutePath)
                    .getOrThrow()

            assertFalse(Files.isSymbolicLink(target.toPath()))
            assertTrue(File(path).readBytes().contentEquals(jarBytes))
            assertEquals("do not overwrite", outside.readText())
        }

    @Test
    fun `linked cache entry does not block a verified download or touch its target`() =
        runBlocking<Unit> {
            val outside = File(tempDir, "sentinel.jar").apply { writeText("sentinel") }
            val cached = cache.cacheJar(pluginId, "1.0.0", outside)
            Files.delete(cached.toPath())
            Files.createSymbolicLink(cached.toPath(), outside.toPath())
            val sig = signAnchor("1.0.0")
            val path =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", target("linked-ok.jar"))
                    .getOrThrow()
            assertTrue(File(path).readBytes().contentEquals(jarBytes))
            assertEquals(sig, PluginSignatureSidecar.read(path))
            assertEquals("sentinel", outside.readText())
        }

    @Test
    fun `cache promotion failure preserves a successful verified download`() =
        runBlocking<Unit> {
            val seed = File(tempDir, "seed.jar").apply { writeBytes(jarBytes) }
            val cached = cache.cacheJar(pluginId, "1.0.0", seed)
            Files.delete(cached.toPath())
            cached.mkdir()
            val sentinel = File(cached, "sentinel").apply { writeText("sentinel") }
            val sig = signAnchor("1.0.0")
            val path =
                repositoryReturning(downloadInfo("1.0.0", sig))
                    .downloadPlugin(pluginId, "1.0.0", target("promotion-ok.jar"))
                    .getOrThrow()
            assertTrue(File(path).readBytes().contentEquals(jarBytes))
            assertEquals(sig, PluginSignatureSidecar.read(path))
            assertEquals("sentinel", sentinel.readText())
        }

    @Test
    fun `unsupported cache version syntax still downloads and verifies`() =
        runBlocking<Unit> {
            val version = "1.0.0 (beta)"
            val sig = signAnchor(version)
            val path =
                repositoryReturning(downloadInfo(version, sig))
                    .downloadPlugin(pluginId, version, target("syntax-ok.jar"))
                    .getOrThrow()
            assertTrue(File(path).readBytes().contentEquals(jarBytes))
            assertEquals(sig, PluginSignatureSidecar.read(path))
        }

    @Test
    fun `unavailable cache does not prevent repository construction or verified download`() =
        runBlocking<Unit> {
            val blocked = File(tempDir, "not-a-directory").apply { writeText("sentinel") }
            val sig = signAnchor("1.0.0")
            val repository =
                RemotePluginRepository(
                    downloadCache = PluginDownloadCache(File(blocked, "cache")),
                    storeVerifier = verifier,
                    downloadInfoProvider = { _, _ -> downloadInfo("1.0.0", sig) },
                )
            val path = repository.downloadPlugin(pluginId, "1.0.0", target("uncached-ok.jar")).getOrThrow()
            assertTrue(File(path).readBytes().contentEquals(jarBytes))
            assertEquals(sig, PluginSignatureSidecar.read(path))
            assertEquals("sentinel", blocked.readText())
        }

    @Test
    fun `unsigned download writes no sidecar`() =
        runBlocking<Unit> {
            val path =
                repositoryReturning(downloadInfo("1.0.0", signature = null))
                    .downloadPlugin(pluginId, "1.0.0", target("fresh-unsigned.jar"))
                    .getOrThrow()
            assertNull(PluginSignatureSidecar.read(path))
        }

    @Test
    fun `fresh download with invalid signature fails and is never cached`() =
        runBlocking<Unit> {
            // Signature legitimately covers a DIFFERENT version's anchor —
            // the substitution scenario.
            val result =
                repositoryReturning(downloadInfo("2.0.0", signAnchor("1.0.0")))
                    .downloadPlugin(pluginId, "2.0.0", target("fresh-bad.jar"))

            assertIs<DownloadException>(result.exceptionOrNull())
            assertFalse(File(target("fresh-bad.jar")).exists())
            // Verify-before-cache: the tampered artifact must not poison the cache.
            assertNull(cache.getCachedJar(pluginId, "2.0.0", jarSha256))
        }

    @Test
    fun `cache hit still verifies signature and purges poisoned entry`() =
        runBlocking<Unit> {
            // Seed the cache the way a warn-window download would have.
            val seed = File(tempDir, "seed.jar").apply { writeBytes(jarBytes) }
            cache.cacheJar(pluginId, "3.0.0", seed)
            assertNotNull(cache.getCachedJar(pluginId, "3.0.0", jarSha256))

            val result =
                repositoryReturning(downloadInfo("3.0.0", signAnchor("9.9.9")))
                    .downloadPlugin(pluginId, "3.0.0", target("cached-bad.jar"))

            assertIs<DownloadException>(result.exceptionOrNull())
            assertFalse(File(target("cached-bad.jar")).exists())
            // The poisoned cache entry is gone, not just bypassed.
            assertNull(cache.getCachedJar(pluginId, "3.0.0", jarSha256))
        }

    @Test
    fun `cache hit with valid signature succeeds without re-downloading and writes the sidecar`() =
        runBlocking<Unit> {
            server!!.stop(0) // prove the cache path never touches the network
            val seed = File(tempDir, "seed-ok.jar").apply { writeBytes(jarBytes) }
            cache.cacheJar(pluginId, "4.0.0", seed)

            val sig = signAnchor("4.0.0")
            val result =
                repositoryReturning(downloadInfo("4.0.0", sig))
                    .downloadPlugin(pluginId, "4.0.0", target("cached-ok.jar"))

            assertEquals(target("cached-ok.jar"), result.getOrThrow())
            assertTrue(File(target("cached-ok.jar")).readBytes().contentEquals(jarBytes))
            // The cache-hit path persists the sidecar too, not just fresh downloads.
            assertEquals(sig, PluginSignatureSidecar.read(target("cached-ok.jar")))
        }
}
