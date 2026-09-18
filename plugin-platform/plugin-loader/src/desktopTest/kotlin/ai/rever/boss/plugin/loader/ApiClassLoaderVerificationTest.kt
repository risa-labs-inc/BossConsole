package ai.rever.boss.plugin.loader

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fail-closed trust gate on [ApiClassLoader.fromPluginDir] (BossConsole#851):
 * the selected jar becomes the PARENT classloader of every plugin, so the
 * newest api-claiming jar installs only with a verification proof — a store
 * sidecar that verifies over its own claimed identity, or a bundled-trust
 * marker matching its bytes (the #102 bundled-copy path). Unlike plugin
 * loads there is no rollout warn-path: an unverifiable api jar degrades the
 * API layer to host-compiled classes instead of being classloaded.
 */
class ApiClassLoaderVerificationTest {
    private val tempDirs = mutableListOf<File>()

    /** Key the injected verifier trusts. */
    private val storeKeyPair: KeyPair = rsaKeyPair()

    /** Key the verifier does NOT trust — a self-signed jar must fail. */
    private val attackerKeyPair: KeyPair = rsaKeyPair()

    @BeforeTest
    fun resetSharedState() {
        // fromPluginDir itself is stateless, but keep the process-wide layer
        // isolated like ApiClassLoaderTest does.
        PluginClassLoaderManager.resetSharedApiLayerForTests()
    }

    @AfterTest
    fun cleanup() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
        tempDirs.forEach { it.deleteRecursively() }
        System.clearProperty("boss.dev.mode")
        System.clearProperty(PluginSignatureEnforcement.PROPERTY)
    }

    private fun testVerifier(): PluginSignatureVerifier =
        PluginSignatureVerifier(
            mapOf(
                "test-store" to (
                    "-----BEGIN PUBLIC KEY-----\n" +
                        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(storeKeyPair.public.encoded) +
                        "\n-----END PUBLIC KEY-----"
                ),
            ),
        )

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply {
                initialize(2048)
            }.generateKeyPair()

    private fun tempDir(): File =
        File.createTempFile("api-cl-sig", "").let {
            it.delete()
            it.mkdirs()
            tempDirs.add(it)
            it
        }

    private fun bareParent(): ClassLoader = ClassLoader.getPlatformClassLoader()

    /** Build a synthetic api-claiming jar; trust proofs are added per test. */
    private fun apiJar(
        dir: File,
        version: String,
        pluginId: String = ApiClassLoader.API_PLUGIN_ID,
    ): File {
        val jar = File(dir, "boss-plugin-api-$version.jar")
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = version
            }
        JarOutputStream(jar.outputStream(), manifest).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "API Sig Test",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Main"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return jar
    }

    /** Write a store sidecar for [jar] over the anchor of [version] and [keyPair]'s key. */
    private fun sign(
        jar: File,
        version: String,
        keyPair: KeyPair = storeKeyPair,
    ) {
        val anchor =
            PluginStoreTrust.versionAnchor(
                ApiClassLoader.API_PLUGIN_ID,
                version,
                FileHashing.sha256(jar),
            )
        val signature =
            Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(anchor.toByteArray(Charsets.UTF_8))
            }
        PluginSignatureSidecar.write(
            jar.absolutePath,
            Base64.getEncoder().encodeToString(signature.sign()),
        )
    }

    @Test
    fun `newest unverifiable api-claiming jar is not installed`() {
        val dir = tempDir()
        val verified = apiJar(dir, "1.0.1")
        sign(verified, "1.0.1")
        // Newer jar that reached the plugin dir without any proof (the #851
        // scenario: GitHub-fallback download or a nulled-signature row).
        apiJar(dir, "9.9.9")

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertEquals("1.0.1", loader.apiVersion, "only the verified jar may become the API layer")
        assertTrue(loader.apiJarPath!!.endsWith("boss-plugin-api-1.0.1.jar"))
    }

    @Test
    fun `newer jar claiming a version nobody signed is not installed`() {
        val dir = tempDir()
        val verified = apiJar(dir, "1.0.1")
        sign(verified, "1.0.1")
        // Valid signature, but over the anchor of a DIFFERENT version claim:
        // substitution — the manifest says 9.9.9, nobody vouched for that.
        val impostor = apiJar(dir, "9.9.9")
        sign(impostor, "1.0.0")

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertEquals("1.0.1", loader.apiVersion)
    }

    @Test
    fun `newer jar with an invalid manifest version claim is not installed`() {
        val dir = tempDir()
        val verified = apiJar(dir, "1.0.1")
        sign(verified, "1.0.1")
        // "999" is not a semver claim — manifest validation drops the jar
        // before it can outrank the verified one, however new it looks.
        val jar = File(dir, "boss-plugin-api-999.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "ai.rever.boss.plugin.api",
                  "displayName": "API Sig Test",
                  "version": "999",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Main"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertEquals("1.0.1", loader.apiVersion)
    }

    @Test
    fun `validly signed newest api jar installs`() {
        val dir = tempDir()
        sign(apiJar(dir, "1.0.1"), "1.0.1")
        sign(apiJar(dir, "1.0.2"), "1.0.2")

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertEquals("1.0.2", loader.apiVersion, "the newest VERIFIED api jar must still win")
        assertTrue(loader.apiJarPath!!.endsWith("boss-plugin-api-1.0.2.jar"))
    }

    @Test
    fun `trusted bundled api jar installs without a store signature`() {
        val dir = tempDir()
        val jar = apiJar(dir, "1.0.5")
        // The #102 bundled-copy install path: no sidecar, a bundled-trust
        // marker bound to the jar's bytes. Uses the DEFAULT (pinned store
        // key) verifier to prove the bundled exemption needs no injection.
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent())
        assertEquals("1.0.5", loader.apiVersion)
    }

    @Test
    fun `api jar with no proof is not installed even when it is the only candidate`() {
        val dir = tempDir()
        apiJar(dir, "9.9.9")

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertNull(loader.apiVersion, "an unverifiable jar must never become the API layer")
        assertNull(loader.apiJarPath)
    }

    @Test
    fun `api jar with a corrupt sidecar is not installed`() {
        val dir = tempDir()
        val jar = apiJar(dir, "1.0.0")
        // Present but garbage: fail-closed, never "treat as unsigned".
        PluginSignatureSidecar.write(jar.absolutePath, "not valid base64 !!")

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertNull(loader.apiVersion)
        assertNull(loader.apiJarPath)
    }

    @Test
    fun `api jar signed by a key the verifier does not trust is not installed`() {
        val dir = tempDir()
        val jar = apiJar(dir, "1.0.0")
        sign(jar, "1.0.0", keyPair = attackerKeyPair)

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertNull(loader.apiVersion, "self-signing must not install the api layer")
    }

    @Test
    fun `tampered api jar bytes invalidate its sidecar`() {
        val dir = tempDir()
        val jar = apiJar(dir, "1.0.0")
        sign(jar, "1.0.0")
        // Bytes changed after signing — the digest no longer matches the anchor.
        jar.appendBytes("tamper".toByteArray())

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertNull(loader.apiVersion)
        assertNull(loader.apiJarPath)
    }

    @Test
    fun `bundled-trust marker does not cover bytes it was not written for`() {
        val dir = tempDir()
        val jar = apiJar(dir, "1.0.0")
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        jar.appendBytes("tamper".toByteArray())

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertNull(loader.apiVersion)
        assertNull(loader.apiJarPath)
    }

    @Test
    fun `dev mode allows a locally built api jar without a signature`() {
        val dir = tempDir()
        apiJar(dir, "1.0.0")
        System.setProperty("boss.dev.mode", "true")

        // Same carve-out as plugin loads: locally built api jars keep
        // loading in development.
        val loader = ApiClassLoader.fromPluginDir(dir, bareParent(), testVerifier())
        assertEquals("1.0.0", loader.apiVersion)
    }
}
