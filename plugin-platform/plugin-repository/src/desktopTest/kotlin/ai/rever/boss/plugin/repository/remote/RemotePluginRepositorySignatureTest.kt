package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.loader.PluginSignatureEnforcement
import ai.rever.boss.plugin.loader.PluginSignatureVerifier
import ai.rever.boss.plugin.loader.PluginStoreTrust
import ai.rever.boss.plugin.repository.DownloadException
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the enforcement glue in [RemotePluginRepository.enforceStoreSignature]
 * — the policy branches where a regression would silently disable the
 * security control (the verifier itself is covered separately).
 */
class RemotePluginRepositorySignatureTest {
    private val tempDir = createTempDirectory("sig-enforce-test").toFile()

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

    private val repository =
        RemotePluginRepository(
            downloadCache = PluginDownloadCache(File(tempDir, "cache")),
            storeVerifier = verifier,
        )

    private val pluginId = "test.plugin"
    private val digest = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    private fun signAnchor(
        pluginId: String,
        version: String,
        sha256: String,
    ): String {
        val anchor = PluginStoreTrust.versionAnchor(pluginId, version, sha256)
        val sig =
            Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(anchor.toByteArray(Charsets.UTF_8))
            }
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun jarFile(name: String): File = File(tempDir, name).apply { writeText("fake jar bytes") }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `valid anchor signature allows install and keeps files`() {
        val jar = jarFile("valid.jar")
        repository.enforceStoreSignature(
            sha256 = digest,
            signature = signAnchor(pluginId, "1.0.0", digest),
            pluginId = pluginId,
            versionLabel = "1.0.0",
            requestedVersion = "1.0.0",
            onVerificationFailure = { jar.delete() },
        )
        assertTrue(jar.exists())
    }

    @Test
    fun `missing signature warns but allows during rollout`() {
        val jar = jarFile("unsigned.jar")
        repository.enforceStoreSignature(
            sha256 = digest,
            signature = null,
            pluginId = pluginId,
            versionLabel = "1.0.0",
            requestedVersion = null,
            onVerificationFailure = { jar.delete() },
        )
        assertTrue(jar.exists())
    }

    @Test
    fun `missing signature hard-fails once enforcement flag is on`() {
        val jar = jarFile("unsigned-enforced.jar")
        System.setProperty(PluginSignatureEnforcement.PROPERTY, "true")
        try {
            assertFailsWith<DownloadException> {
                repository.enforceStoreSignature(
                    sha256 = digest,
                    signature = null,
                    pluginId = pluginId,
                    versionLabel = "1.0.0",
                    requestedVersion = null,
                    onVerificationFailure = { jar.delete() },
                )
            }
            assertFalse(jar.exists())
        } finally {
            System.clearProperty(PluginSignatureEnforcement.PROPERTY)
        }
    }

    @Test
    fun `substituted artifact with valid signature from another version fails`() {
        // The DB-write substitution attack: version 2.0.0's row is rewritten
        // to serve version 1.0.0's (legitimately signed) bytes, hash, and
        // signature. The anchor covers pluginId|version|sha256, so the
        // signature no longer verifies for the identity being installed.
        val jar = jarFile("downgrade.jar")
        assertFailsWith<DownloadException> {
            repository.enforceStoreSignature(
                sha256 = digest,
                signature = signAnchor(pluginId, "1.0.0", digest),
                pluginId = pluginId,
                versionLabel = "2.0.0",
                requestedVersion = "2.0.0",
                onVerificationFailure = { jar.delete() },
            )
        }
        assertFalse(jar.exists())
    }

    @Test
    fun `store-reported version differing from requested version fails`() {
        // Even with a signature that would verify for what the store claims,
        // serving a different version than requested is rejected outright.
        val jar = jarFile("wrong-version.jar")
        assertFailsWith<DownloadException> {
            repository.enforceStoreSignature(
                sha256 = digest,
                signature = signAnchor(pluginId, "1.0.0", digest),
                pluginId = pluginId,
                versionLabel = "1.0.0",
                requestedVersion = "2.0.0",
                onVerificationFailure = { jar.delete() },
            )
        }
        assertFalse(jar.exists())
    }

    @Test
    fun `invalid signature throws and deletes all files`() {
        val jar = jarFile("tampered.jar")
        val cached = jarFile("tampered-cached.jar")

        assertFailsWith<DownloadException> {
            repository.enforceStoreSignature(
                sha256 = digest,
                signature = signAnchor("evil.other.plugin", "1.0.0", digest),
                pluginId = pluginId,
                versionLabel = "1.0.0",
                requestedVersion = "1.0.0",
                onVerificationFailure = {
                    jar.delete()
                    cached.delete()
                },
            )
        }
        assertFalse(jar.exists())
        assertFalse(cached.exists())
    }

    @Test
    fun `garbage signature throws and deletes rather than allowing`() {
        val jar = jarFile("garbage.jar")
        assertFailsWith<DownloadException> {
            repository.enforceStoreSignature(
                sha256 = digest,
                signature = "!!not-base64!!",
                pluginId = pluginId,
                versionLabel = "1.0.0",
                requestedVersion = null,
                onVerificationFailure = { jar.delete() },
            )
        }
        assertFalse(jar.exists())
    }
}
