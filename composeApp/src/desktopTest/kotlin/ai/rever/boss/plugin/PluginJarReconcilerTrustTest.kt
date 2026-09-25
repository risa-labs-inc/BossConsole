package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.FileHashing
import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginSignatureEnforcement
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.loader.PluginSignatureVerifier
import ai.rever.boss.plugin.loader.PluginStoreTrust
import java.io.File
import java.nio.file.Files
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
import kotlin.test.assertTrue

/**
 * Pins the reconciler's trust ordering in strict mode and its version ordering
 * during the warn-only signature rollout.
 */
class PluginJarReconcilerTrustTest {
    private val temps = mutableListOf<File>()
    private val installedIds = mutableListOf<String>()
    private var previousEnforcement: String? = null
    private lateinit var previousVerifier: PluginSignatureVerifier

    private val keyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

    private val testVerifier =
        PluginSignatureVerifier(
            mapOf(
                "test-store" to (
                    "-----BEGIN PUBLIC KEY-----\n" +
                        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
                        "\n-----END PUBLIC KEY-----"
                ),
            ),
        )

    @BeforeTest
    fun injectVerifier() {
        previousVerifier = PluginJarReconciler.signatureVerifier
        PluginJarReconciler.signatureVerifier = testVerifier
        previousEnforcement = System.getProperty(PluginSignatureEnforcement.PROPERTY)
        System.setProperty(PluginSignatureEnforcement.PROPERTY, "true")
    }

    @AfterTest
    fun cleanup() {
        PluginJarReconciler.signatureVerifier = previousVerifier
        if (previousEnforcement == null) {
            System.clearProperty(PluginSignatureEnforcement.PROPERTY)
        } else {
            System.setProperty(PluginSignatureEnforcement.PROPERTY, previousEnforcement!!)
        }
        installedIds.forEach(PluginPersistence::removeInstalledPlugin)
        temps.forEach { it.deleteRecursively() }
    }

    private fun tempPluginDir(): File = Files.createTempDirectory("reconcile-trust").toFile().also { temps.add(it) }

    private fun recordInstalled(
        pluginId: String,
        jar: File,
    ) {
        installedIds.add(pluginId)
        PluginPersistence.addInstalledPlugin(pluginId, jar.absolutePath, enabled = true)
    }

    private fun manifestJar(
        dir: File,
        fileName: String,
        pluginId: String,
        version: String,
    ): File {
        val jar = File(dir, fileName)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "Reconciler Trust Test",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Missing"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return jar
    }

    private fun signWithTestKey(
        jar: File,
        pluginId: String,
        version: String,
    ) {
        val anchor = PluginStoreTrust.versionAnchor(pluginId, version, FileHashing.sha256(jar))
        val sig =
            Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(anchor.toByteArray(Charsets.UTF_8))
            }
        PluginSignatureSidecar.write(jar.absolutePath, Base64.getEncoder().encodeToString(sig.sign()))
    }

    @Test
    fun `a signed jar wins over an unsigned higher version and keeps the persisted path`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.trust"
        val signed = manifestJar(dir, "test-plugin-1.0.0.jar", pluginId, "1.0.0")
        val unsigned = manifestJar(dir, "test-plugin-2.0.0.jar", pluginId, "2.0.0")
        signWithTestKey(signed, pluginId, "1.0.0")

        // The signed jar is what installed.json knows about; the unsigned
        // drop-in is the attacker artifact.
        recordInstalled(pluginId, signed)

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(signed), result.winners, "the signed candidate must win regardless of version")
        assertTrue(signed.exists(), "the signed jar must survive")
        assertTrue(
            File(PluginSignatureSidecar.pathFor(signed.absolutePath)).exists(),
            "the signed jar's sidecar must survive",
        )
        assertTrue(unsigned.exists(), "a newer unsigned artifact is retained for investigation")
        assertEquals(
            signed.absolutePath,
            PluginPersistence.getInstalledPlugin(pluginId)?.jarPath,
            "installed.json must not be repointed at the unsigned jar",
        )
    }

    @Test
    fun `warn-only rollout keeps a newer unsigned update and its recorded version`() {
        System.setProperty(PluginSignatureEnforcement.PROPERTY, "false")
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.rollout"
        val signed = manifestJar(dir, "rollout-1.0.0.jar", pluginId, "1.0.0")
        val unsigned = manifestJar(dir, "rollout-2.0.0.jar", pluginId, "2.0.0")
        signWithTestKey(signed, pluginId, "1.0.0")
        recordInstalled(pluginId, signed)

        repeat(2) {
            val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)
            assertEquals(listOf(unsigned), result.winners)
            assertTrue(signed.exists(), "the signed fallback must survive")
            assertTrue(unsigned.exists(), "the update must not be deleted on each launch")
            assertEquals(unsigned.absolutePath, PluginPersistence.getInstalledPlugin(pluginId)?.jarPath)
            assertEquals("2.0.0", PluginPersistence.getInstalledPlugin(pluginId)?.installedVersion)
        }
    }

    @Test
    fun `a forged sidecar does not make an unsigned jar trusted`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.forged"
        val signed = manifestJar(dir, "forged-1.0.0.jar", pluginId, "1.0.0")
        val forged = manifestJar(dir, "forged-2.0.0.jar", pluginId, "2.0.0")
        signWithTestKey(signed, pluginId, "1.0.0")
        // A present-but-unverifiable signature is evidence, not trust.
        PluginSignatureSidecar.write(forged.absolutePath, "bm90LWEtc2lnbmF0dXJl")

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(signed), result.winners)
        assertTrue(signed.exists())
        assertFalse(forged.exists(), "a jar with an invalid sidecar is unsigned and loses")
    }

    @Test
    fun `bundled trust also outranks an unsigned higher version`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.bundled"
        val bundled = manifestJar(dir, "bundled-1.0.0.jar", pluginId, "1.0.0")
        val unsigned = manifestJar(dir, "bundled-2.0.0.jar", pluginId, "2.0.0")
        PluginBundledTrust.bindToBundle(bundled.absolutePath, bundled)

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(bundled), result.winners)
        assertTrue(bundled.exists())
        assertTrue(PluginBundledTrust.isTrusted(bundled.absolutePath))
        assertTrue(unsigned.exists())
    }

    @Test
    fun `two unsigned jars still reconcile by version`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.plain"
        manifestJar(dir, "plain-1.0.0.jar", pluginId, "1.0.0")
        val newer = manifestJar(dir, "plain-2.0.0.jar", pluginId, "2.0.0")

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(newer), result.winners)
        assertFalse(File(dir, "plain-1.0.0.jar").exists())
    }

    @Test
    fun `unreadable sidecar leaves duplicate group and persisted path untouched`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.unreadable"
        val old = manifestJar(dir, "unreadable-1.0.0.jar", pluginId, "1.0.0")
        val newer = manifestJar(dir, "unreadable-2.0.0.jar", pluginId, "2.0.0")
        recordInstalled(pluginId, old)
        assertTrue(File(PluginSignatureSidecar.pathFor(old.absolutePath)).mkdir())

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(old), result.winners)
        assertTrue(result.deleted.isEmpty())
        assertTrue(old.exists())
        assertTrue(newer.exists())
        assertEquals(old.absolutePath, PluginPersistence.getInstalledPlugin(pluginId)?.jarPath)
    }

    @Test
    fun `two signed jars select the newer version`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.signed-versions"
        val old = manifestJar(dir, "signed-1.0.0.jar", pluginId, "1.0.0")
        val newer = manifestJar(dir, "signed-2.0.0.jar", pluginId, "2.0.0")
        signWithTestKey(old, pluginId, "1.0.0")
        signWithTestKey(newer, pluginId, "2.0.0")

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(newer), result.winners)
        assertFalse(old.exists())
        assertTrue(newer.exists())
    }

    @Test
    fun `trusted jar wins a same-version tie despite newer unsigned mtime`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.same-version"
        val signed = manifestJar(dir, "signed.jar", pluginId, "1.0.0")
        val unsigned = manifestJar(dir, "unsigned.jar", pluginId, "1.0.0")
        signWithTestKey(signed, pluginId, "1.0.0")
        assertTrue(unsigned.setLastModified(signed.lastModified() + 5_000))

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(signed), result.winners)
        assertTrue(signed.exists())
        assertFalse(unsigned.exists())
    }

    @Test
    fun `signature for a different version cannot outrank a valid signed jar`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.wrong-anchor"
        val signed = manifestJar(dir, "signed-1.0.0.jar", pluginId, "1.0.0")
        val wrongAnchor = manifestJar(dir, "wrong-anchor-2.0.0.jar", pluginId, "2.0.0")
        signWithTestKey(signed, pluginId, "1.0.0")
        signWithTestKey(wrongAnchor, pluginId, "1.0.0")

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(signed), result.winners)
        assertTrue(signed.exists())
        assertFalse(wrongAnchor.exists())
    }

    @Test
    fun `invalid sidecar overrides a bundled trust marker`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.invalid-bundle"
        val bundled = manifestJar(dir, "bundled-2.0.0.jar", pluginId, "2.0.0")
        val signed = manifestJar(dir, "signed-1.0.0.jar", pluginId, "1.0.0")
        PluginBundledTrust.bindToBundle(bundled.absolutePath, bundled)
        PluginSignatureSidecar.write(bundled.absolutePath, "bm90LWEtc2lnbmF0dXJl")
        signWithTestKey(signed, pluginId, "1.0.0")

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(signed), result.winners)
        assertFalse(bundled.exists())
        assertTrue(signed.exists())
    }
}
