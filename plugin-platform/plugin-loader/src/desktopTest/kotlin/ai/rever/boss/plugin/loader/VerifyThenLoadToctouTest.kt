package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.Plugin
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression test for the verify-then-load TOCTOU: the loader used to open the
 * plugin JAR three separate times — manifest read, sha256 anchor, classloader
 * open — so a local writer could swap bytes at the path between opens and get
 * identity of A, digest of B, execution of C. The load now stages the JAR into
 * a private copy first ([StagedPluginJar]) and every step operates on that
 * snapshot; the original path is never re-opened.
 *
 * [DynamicPluginLoaderImpl.verifyToLoadHook] reproduces the swap inside the
 * exact window the bug lived in: after signature verification, before the
 * classloader is built.
 */
class VerifyThenLoadToctouTest {
    private val tempFiles = mutableListOf<File>()

    private val keyPair: KeyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

    private fun testLoader() =
        DynamicPluginLoaderImpl(
            signatureVerifier =
                PluginSignatureVerifier(
                    mapOf(
                        "test-store" to (
                            "-----BEGIN PUBLIC KEY-----\n" +
                                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
                                "\n-----END PUBLIC KEY-----"
                        ),
                    ),
                ),
        )

    @BeforeTest
    fun resetSharedState() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
    }

    @AfterTest
    fun cleanup() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
        tempFiles.forEach { it.delete() }
    }

    /**
     * A plugin jar whose manifest resolves [StagedProbePlugin]. When
     * [includeClassBytes] the jar also carries the compiled class, so the
     * child-first loader DEFINES it from the jar (observable via
     * [PluginClassLoader.definedClassNamed]); without it the name resolves
     * from the parent test classpath instead — that difference is what tells
     * us which bytes the classloader actually read.
     */
    private fun probeJar(
        includeClassBytes: Boolean,
        pluginId: String = FIXTURE_ID,
        extraManifest: String = "",
    ): File {
        val jar = File.createTempFile("verify-load-toctou", ".jar")
        tempFiles.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "TOCTOU Probe",
                  "version": "1.0.0",
                  "apiVersion": "1.0.0",
                  "mainClass": "${StagedProbePlugin::class.java.name}"
                  $extraManifest
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
            if (includeClassBytes) {
                val entryName = StagedProbePlugin::class.java.name.replace('.', '/') + ".class"
                val bytes =
                    javaClass.classLoader
                        .getResourceAsStream(entryName)
                        .use { requireNotNull(it) { "test classpath is missing $entryName" }.readBytes() }
                out.putNextEntry(JarEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun writeSidecar(jar: File) {
        val anchor = PluginStoreTrust.versionAnchor(FIXTURE_ID, "1.0.0", FileHashing.sha256(jar))
        val sig =
            Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(anchor.toByteArray(Charsets.UTF_8))
            }
        tempFiles.add(File(PluginSignatureSidecar.pathFor(jar.absolutePath)))
        PluginSignatureSidecar.write(jar.absolutePath, Base64.getEncoder().encodeToString(sig.sign()))
    }

    @Test
    fun `a byte-swap at the original path after verification cannot reach the classloader`() =
        runBlocking<Unit> {
            val loader = testLoader()
            val original = probeJar(includeClassBytes = true)
            val swapped = probeJar(includeClassBytes = false)
            writeSidecar(original)

            var stagedSeen: File? = null
            // The swap lands in exactly the old window: the sidecar verified
            // against jar A's bytes, then the classloader opened the path again
            // and would have executed jar B.
            loader.verifyToLoadHook = { jarPath, staged ->
                swapped.copyTo(File(jarPath), overwrite = true)
                stagedSeen = staged
            }

            val plugin = loader.loadPlugin(original.absolutePath).getOrThrow()
            val classLoader = plugin.classLoader as PluginClassLoader

            // Executed bytes are the staged copy: the probe class was DEFINED
            // by the plugin loader from the jar, not resolved from the parent
            // test classpath — which is where a manifest-only swap would have
            // sent it.
            assertTrue(
                classLoader.definedClassNamed(StagedProbePlugin::class.java.name),
                "main class must be defined from the verified staged bytes, not the swapped file",
            )
            assertSame(classLoader, plugin.instance.javaClass.classLoader)
            // The recorded jar path stays the real plugin path for
            // persistence/reload, not the temp copy.
            assertEquals(original.canonicalPath, File(plugin.jarPath).canonicalPath)

            loader.unloadPlugin(FIXTURE_ID).getOrThrow()
            assertFalse(
                assertNotNull(stagedSeen).exists(),
                "unloading must delete the staged copy the classloader owned",
            )
        }

    @Test
    fun `a swap reaching the staged copy itself fails closed`() =
        runBlocking<Unit> {
            val loader = testLoader()
            val original = probeJar(includeClassBytes = true)
            var stagedSeen: File? = null
            loader.verifyToLoadHook = { _, staged ->
                stagedSeen = staged
                staged.writeBytes("unverified bytes".toByteArray())
            }

            val result = loader.loadPlugin(original.absolutePath)

            // The load must fail rather than execute whatever the writer left.
            assertIs<PluginBinaryIncompatibilityException>(result.exceptionOrNull())
            assertNull(loader.getPlugin(FIXTURE_ID))
            assertFalse(
                assertNotNull(stagedSeen).exists(),
                "staged copy must be deleted when the refused load unwinds",
            )
        }

    @Test
    fun `a refusal after verification deletes the staged copy`() =
        runBlocking<Unit> {
            val loader = testLoader().apply { currentBossVersion = "1.0.0" }
            val original =
                probeJar(
                    includeClassBytes = true,
                    extraManifest = ",\n  \"minBossVersion\": \"999.0.0\"",
                )

            var stagedSeen: File? = null
            loader.verifyToLoadHook = { _, staged -> stagedSeen = staged }

            val result = loader.loadPlugin(original.absolutePath)

            assertIs<PluginBossVersionException>(result.exceptionOrNull())
            assertFalse(
                assertNotNull(stagedSeen).exists(),
                "a gate refusal after verification must not leak the staged copy",
            )
        }

    @Test
    fun `the original jar path stays protected while the staged plugin is live`() =
        runBlocking<Unit> {
            val loader = testLoader()
            val original = probeJar(includeClassBytes = true)

            val plugin = loader.loadPlugin(original.absolutePath).getOrThrow()
            assertNotNull(plugin)

            // The classpath points at the staged copy, but the recorded source
            // path must still count as in use — the reconciler and plugins that
            // reopen their own jar by name depend on it (BossConsole#72).
            assertTrue(
                PluginClassLoader.isPathOpenByLiveLoader(original.absolutePath),
                "loaded plugin's original jar path must read as open",
            )

            loader.unloadPlugin(FIXTURE_ID).getOrThrow()
            assertFalse(
                PluginClassLoader.isPathOpenByLiveLoader(original.absolutePath),
                "original jar path must release once the classloader closes",
            )
        }

    private companion object {
        const val FIXTURE_ID = "com.example.verify.toctou"
    }
}

/**
 * Loadable fixture whose class bytes are also written INTO the probe jar, so
 * the test can tell whether the plugin classloader defined the class from the
 * (staged) jar or fell back to the parent test classpath. Top-level because
 * the manifest validator rejects nested-class names (the `$`).
 */
class StagedProbePlugin : Plugin {
    override val pluginId = "com.example.verify.toctou"
    override val displayName = "TOCTOU Probe"

    override fun register(context: PluginContext) = Unit
}
