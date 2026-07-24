package ai.rever.boss.plugin.loader

import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Verifies load-time signature enforcement in [DynamicPluginLoaderImpl] — the
 * choke point every install path funnels through. A valid sidecar signature
 * passes the gate (proven by the load proceeding to the missing-main-class
 * failure downstream); a tampered or wrong-identity sidecar is rejected with
 * [PluginSignatureException] before any classloading.
 */
class LoadTimeSignatureVerificationTest {
    private val tempFiles = mutableListOf<File>()

    private val keyPair: KeyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply {
                initialize(2048)
            }.generateKeyPair()

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

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.delete() }
        System.clearProperty(PluginSignatureEnforcement.PROPERTY)
        System.clearProperty("boss.dev.mode")
    }

    private fun manifestJar(
        pluginId: String,
        version: String,
    ): File {
        val jar = File.createTempFile("sig-load", ".jar")
        tempFiles.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "Sig Load Test",
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

    private fun sha256(file: File): String = FileHashing.sha256(file)

    private fun writeSidecar(
        jar: File,
        pluginId: String,
        version: String,
        sha256: String,
    ) {
        val anchor = PluginStoreTrust.versionAnchor(pluginId, version, sha256)
        val sig =
            Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(anchor.toByteArray(Charsets.UTF_8))
            }
        tempFiles.add(File(PluginSignatureSidecar.pathFor(jar.absolutePath)))
        PluginSignatureSidecar.write(jar.absolutePath, Base64.getEncoder().encodeToString(sig.sign()))
    }

    @Test
    fun `valid sidecar passes the signature gate`() =
        runBlocking<Unit> {
            val id = "com.example.sig.valid"
            val jar = manifestJar(id, "1.0.0")
            writeSidecar(jar, id, "1.0.0", sha256(jar))

            // Past the gate the load proceeds and fails on the missing main class,
            // proving the signature check allowed it through.
            val result = testLoader().loadPlugin(jar.absolutePath)
            assertIs<PluginClassException>(result.exceptionOrNull())
        }

    @Test
    fun `sidecar signed for a different version is rejected`() =
        runBlocking<Unit> {
            val id = "com.example.sig.substituted"
            val jar = manifestJar(id, "2.0.0")
            // Sign the anchor for 1.0.0 but the manifest says 2.0.0 — substitution.
            writeSidecar(jar, id, "1.0.0", sha256(jar))

            val result = testLoader().loadPlugin(jar.absolutePath)
            assertIs<PluginSignatureException>(result.exceptionOrNull())
        }

    @Test
    fun `tampered jar with a valid-looking sidecar is rejected`() =
        runBlocking<Unit> {
            val id = "com.example.sig.tampered"
            val jar = manifestJar(id, "1.0.0")
            writeSidecar(jar, id, "1.0.0", sha256(jar))
            // Mutate the jar after signing so its hash no longer matches the anchor.
            jar.appendBytes("tamper".toByteArray())

            val result = testLoader().loadPlugin(jar.absolutePath)
            assertIs<PluginSignatureException>(result.exceptionOrNull())
        }

    @Test
    fun `missing sidecar is allowed during rollout`() =
        runBlocking<Unit> {
            val id = "com.example.sig.unsigned"
            val jar = manifestJar(id, "1.0.0")
            // No sidecar, enforcement off (default) → proceeds past the gate.
            val result = testLoader().loadPlugin(jar.absolutePath)
            assertIs<PluginClassException>(result.exceptionOrNull())
        }

    @Test
    fun `missing sidecar hard-fails once enforcement is on`() =
        runBlocking<Unit> {
            val id = "com.example.sig.enforced"
            val jar = manifestJar(id, "1.0.0")
            System.setProperty(PluginSignatureEnforcement.PROPERTY, "true")
            System.setProperty("boss.dev.mode", "false")

            val result = testLoader().loadPlugin(jar.absolutePath)
            assertIs<PluginSignatureException>(result.exceptionOrNull())
        }

    @Test
    fun `missing sidecar stays allowed in dev mode even under enforcement`() =
        runBlocking<Unit> {
            val id = "com.example.sig.devexempt"
            val jar = manifestJar(id, "1.0.0")
            System.setProperty(PluginSignatureEnforcement.PROPERTY, "true")
            System.setProperty("boss.dev.mode", "true")

            // Dev side-loads have no sidecar and must keep loading.
            val result = testLoader().loadPlugin(jar.absolutePath)
            assertIs<PluginClassException>(result.exceptionOrNull())
        }

    @Test
    fun `dev mode does not rescue a tampered sidecar`() =
        runBlocking<Unit> {
            // The dev exemption is for MISSING sidecars only. A present-but-invalid
            // sidecar must still hard-fail even in dev mode — otherwise dev builds
            // would silently accept tampered signed artifacts.
            val id = "com.example.sig.devtampered"
            val jar = manifestJar(id, "1.0.0")
            writeSidecar(jar, id, "1.0.0", sha256(jar))
            jar.appendBytes("tamper".toByteArray())
            System.setProperty(PluginSignatureEnforcement.PROPERTY, "false")
            System.setProperty("boss.dev.mode", "true")

            val result = testLoader().loadPlugin(jar.absolutePath)
            assertIs<PluginSignatureException>(result.exceptionOrNull())
        }
}
