package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Result of plugin signature verification.
 */
sealed class SignatureVerificationResult {
    /**
     * Plugin is signed by a trusted publisher.
     */
    data class Verified(
        val publisher: String
    ) : SignatureVerificationResult()

    /**
     * Plugin signature verification failed.
     */
    data class Failed(
        val reason: String,
        val error: Throwable? = null
    ) : SignatureVerificationResult()

    val isVerified: Boolean get() = this is Verified
}

/**
 * Verifies plugin artifact signatures against pinned publisher keys.
 *
 * Trusted keys are provided at construction as `publisher -> PEM`
 * (`-----BEGIN PUBLIC KEY-----` SubjectPublicKeyInfo, as produced by
 * `openssl pkey -pubout`) and are immutable afterwards — verification never
 * observes a mutating key set, by construction rather than convention. A PEM
 * that fails to parse is logged and skipped; if none parse, verification
 * fails closed. Only that exact armor is handled: other formats (e.g.
 * PKCS#1 `-----BEGIN RSA PUBLIC KEY-----`) are unsupported and fail parse —
 * acceptable because the PEMs are compiled-in and the
 * `store trust anchor parses` test guards the real key.
 *
 * The only supported scheme is the BOSS Plugin Store's anchor signature:
 * RSASSA-PKCS1-v1_5 with SHA-256 over the UTF-8 bytes of the canonical
 * version anchor (`pluginId|version|sha256`, see
 * [PluginStoreTrust.versionAnchor]). Deliberately NOT supported: standard
 * jarsigner verification — `JarFile(file, true)` proves integrity against the
 * JAR's own embedded certificates, so without a trust-anchor check any
 * self-signed JAR would pass; an earlier implementation had exactly that hole.
 */
class PluginSignatureVerifier(trustedKeyPems: Map<String, String> = emptyMap()) {
    private val logger = BossLogger.forComponent("PluginSignatureVerifier")

    /**
     * Trusted public keys by publisher name. Parsed once at construction;
     * never mutated.
     */
    private val trustedKeys: Map<String, PublicKey> = buildMap {
        for ((publisher, pem) in trustedKeyPems) {
            try {
                val pemContent = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\\s".toRegex(), "")

                val decoded = Base64.getDecoder().decode(pemContent)
                put(publisher, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoded)))

                logger.info(LogCategory.SYSTEM, "Added trusted public key", mapOf(
                    "publisher" to publisher
                ))
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to parse trusted public key", mapOf(
                    "publisher" to publisher
                ), e)
            }
        }
    }

    /**
     * Strictly verify a detached signature over a canonical message.
     *
     * The signature must be RSASSA-PKCS1-v1_5 with SHA-256 over the UTF-8
     * bytes of [message] — for store artifacts, the canonical version anchor
     * from [PluginStoreTrust.versionAnchor]. Only a signature produced with a
     * trusted publisher's private key verifies; fails closed when no trusted
     * keys are configured.
     *
     * @param message The exact message the signature is expected to cover
     * @param signatureBase64 Base64-encoded signature
     * @return Verification result
     */
    fun verifySignedMessage(message: String, signatureBase64: String): SignatureVerificationResult {
        if (trustedKeys.isEmpty()) {
            return SignatureVerificationResult.Failed("No trusted keys configured")
        }

        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (e: IllegalArgumentException) {
            // Reason strings surface in user-facing install errors — keep
            // crypto/exception internals in the throwable, not the text.
            return SignatureVerificationResult.Failed("Signature is not valid base64", e)
        }
        val messageBytes = message.toByteArray(Charsets.UTF_8)

        var lastError: Throwable? = null
        for ((publisher, publicKey) in trustedKeys) {
            try {
                val signature = Signature.getInstance("SHA256withRSA")
                signature.initVerify(publicKey)
                signature.update(messageBytes)
                if (signature.verify(signatureBytes)) {
                    return SignatureVerificationResult.Verified(publisher)
                }
            } catch (e: Exception) {
                // e.g. signature length does not match this key's modulus —
                // record and keep trying the remaining trusted keys.
                lastError = e
            }
        }

        return SignatureVerificationResult.Failed("No trusted key verified the signature", lastError)
    }
}
