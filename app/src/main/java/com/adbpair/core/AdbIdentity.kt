package com.adbpair.core

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Mints a fresh ADB identity: a self-signed certificate plus its PKCS8
 * private key, PEM-encoded exactly as kadb stores them.
 *
 * This exists because kadb 1.2.1 cannot mint one in the running process:
 * KadbCert.get() only generates when its in-memory key is empty, and the
 * generator behind it (CertUtils) is internal to the kadb module — so a
 * reset that deletes the identity files rewrites the SAME key, and the
 * fingerprint never changes (the reset bug: three resets, one identity).
 * This generator always mints fresh, mirroring CertUtils.generate in
 * format (RSA 2048, self-signed X.509v3, SHA512withRSA, single-line
 * Base64 PEM). One deliberate deviation: the first provider that converts
 * the certificate wins, instead of letting later providers overwrite it —
 * the DER encoding is deterministic, so the bytes are the same, and it
 * never touches the Android-only provider on the JVM.
 *
 * Ported from DroidDeck's AdbIdentityGenerator (proven by device:
 * reset-mint changed the fingerprint on 2026-11-09).
 */
object AdbIdentity {

    /** Fresh (certificate, private key) PEM bytes; a new keypair every call. */
    fun generateFreshIdentity(notAfterMs: Long): Pair<ByteArray, ByteArray> {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        val keyPair = keyPairGenerator.generateKeyPair()
        val subject = X500Name("CN=Kadb, OU=Kadb, O=Kadb, L=Kadb, ST=Kadb, C=Kadb")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Time(Date(System.currentTimeMillis())),
            Time(Date(notAfterMs)),
            subject,
            keyPair.public,
        )
        val holder = builder.build(JcaContentSignerBuilder("SHA512withRSA").build(keyPair.private))
        var certificate: X509Certificate? = null
        for (provider in listOf<Any?>(null, BouncyCastleProvider(), "AndroidOpenSSL")) {
            try {
                certificate = when (provider) {
                    is Provider -> JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
                    is String -> JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
                    else -> JcaX509CertificateConverter().getCertificate(holder)
                }
                break
            } catch (_: Exception) {
                // Next provider: on the JVM the AndroidOpenSSL provider is
                // absent, and a lookup failure must fall through, not crash
                // the mint (kadb's own loop only catches CertificateException).
            }
        }
        val cert = certificate
            ?: throw CertificateException("All certificate providers failed to generate a certificate.")
        return pem("CERTIFICATE", cert.encoded) to pem("PRIVATE KEY", keyPair.private.encoded)
    }

    private fun pem(type: String, der: ByteArray): ByteArray {
        val base64 = Base64.getEncoder().encodeToString(der)
        return "-----BEGIN $type-----\n$base64\n-----END $type-----".toByteArray()
    }
}
