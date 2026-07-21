package ai.rever.boss.plugin.loader

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginSignatureVerifierTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()

    private fun publicKeyPem(pair: KeyPair = keyPair): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(pair.public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----"
    }

    private fun sign(message: String, pair: KeyPair = keyPair): String {
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(message.toByteArray(Charsets.UTF_8))
        }
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private val digest = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val anchor = PluginStoreTrust.versionAnchor("test.plugin", "1.2.3", digest)

    private fun verifierWithKey() =
        PluginSignatureVerifier(mapOf("test-publisher" to publicKeyPem()))

    @Test
    fun `valid message signature verifies`() {
        val result = verifierWithKey().verifySignedMessage(anchor, sign(anchor))
        assertIs<SignatureVerificationResult.Verified>(result)
        assertEquals("test-publisher", result.publisher)
    }

    @Test
    fun `anchor is case-insensitive on the digest`() {
        val upper = PluginStoreTrust.versionAnchor("test.plugin", "1.2.3", digest.uppercase())
        // versionAnchor lowercases the digest, so both sides produce the same message
        assertEquals(anchor, upper)
    }

    @Test
    fun `signature over a different message fails`() {
        val other = PluginStoreTrust.versionAnchor("test.plugin", "1.0.0", digest)
        val result = verifierWithKey().verifySignedMessage(anchor, sign(other))
        assertIs<SignatureVerificationResult.Failed>(result)
    }

    @Test
    fun `corrupted signature fails with sanitized reason`() {
        val result = verifierWithKey().verifySignedMessage(anchor, "not-base64!!!")
        assertIs<SignatureVerificationResult.Failed>(result)
        // reason must stay user-presentable: no exception internals
        assertFalse(result.reason.contains("Exception"))
    }

    @Test
    fun `signature from untrusted key fails`() {
        val otherPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val verifier = PluginSignatureVerifier(mapOf("other" to publicKeyPem(otherPair)))
        val result = verifier.verifySignedMessage(anchor, sign(anchor))
        assertIs<SignatureVerificationResult.Failed>(result)
    }

    @Test
    fun `no trusted keys fails closed`() {
        val result = PluginSignatureVerifier().verifySignedMessage(anchor, sign(anchor))
        assertIs<SignatureVerificationResult.Failed>(result)
        assertTrue(result.reason.contains("No trusted keys"))
    }

    @Test
    fun `store trust anchor parses`() {
        // Guards against a corrupted embedded PEM: parsing failures are logged,
        // not thrown, so verify a key actually landed by checking a bogus
        // signature fails with a rejection reason rather than
        // no-keys-configured.
        val verifier = PluginSignatureVerifier(PluginStoreTrust.TRUSTED_KEYS)
        val result = verifier.verifySignedMessage(anchor, sign(anchor))
        assertIs<SignatureVerificationResult.Failed>(result)
        assertFalse(result.reason.contains("No trusted keys configured"))
    }
}
