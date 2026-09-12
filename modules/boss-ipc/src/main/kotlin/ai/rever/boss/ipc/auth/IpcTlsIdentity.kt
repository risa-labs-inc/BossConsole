package ai.rever.boss.ipc.auth

import io.grpc.netty.GrpcSslContexts
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

/** An ephemeral endpoint key. Only its owning process receives the private key. */
class IpcTlsIdentity private constructor(
    private val certificate: X509Certificate,
    private val privateKey: PrivateKey,
) {
    val certificateBase64: String get() = Base64.getEncoder().encodeToString(certificate.encoded)

    fun serverContext(): SslContext =
        GrpcSslContexts
            .configure(SslContextBuilder.forServer(privateKey, certificate), SslProvider.JDK)
            .build()

    /** For the host's spawn environment only. Never put this value in logs or command arguments. */
    fun privateKeyBase64(): String = Base64.getEncoder().encodeToString(privateKey.encoded)

    companion object {
        // Each channel trusts exactly its intended endpoint certificate, not a shared host CA.
        const val AUTHORITY = "boss-ipc.local"

        fun create(): IpcTlsIdentity {
            val pair =
                KeyPairGenerator
                    .getInstance("EC")
                    .apply {
                        initialize(ECGenParameterSpec("secp256r1"))
                    }.generateKeyPair()
            val subject = X500Name("CN=$AUTHORITY")
            val now = Instant.now()
            val builder =
                JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger(160, SecureRandom()).setBit(159),
                    Date.from(now.minusSeconds(60)),
                    Date.from(now.plusSeconds(366L * 24 * 60 * 60)),
                    subject,
                    pair.public,
                )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, AUTHORITY)),
            )
            val signed = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
            val certificate = JcaX509CertificateConverter().getCertificate(signed)
            certificate.verify(pair.public)
            return IpcTlsIdentity(certificate, pair.private)
        }

        fun restore(
            certificateBase64: String,
            privateKeyBase64: String,
        ): IpcTlsIdentity {
            val certificate = decodeCertificate(certificateBase64)
            val key =
                KeyFactory.getInstance("EC").generatePrivate(
                    PKCS8EncodedKeySpec(decode(privateKeyBase64)),
                )
            return IpcTlsIdentity(certificate, key)
        }

        fun clientContext(certificateBase64: String): SslContext =
            GrpcSslContexts
                .configure(SslContextBuilder.forClient(), SslProvider.JDK)
                .trustManager(decodeCertificate(certificateBase64))
                .build()

        private fun decodeCertificate(encoded: String): X509Certificate {
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(decode(encoded).inputStream())
            require(certificate is X509Certificate) { "IPC endpoint requires an X.509 certificate" }
            certificate.checkValidity()
            return certificate
        }

        private fun decode(encoded: String): ByteArray {
            require(encoded.length in 1..16_384) { "Invalid IPC TLS material size" }
            return Base64.getDecoder().decode(encoded)
        }
    }
}
