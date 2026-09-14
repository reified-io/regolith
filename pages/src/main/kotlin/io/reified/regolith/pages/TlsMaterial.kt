package io.reified.regolith.pages

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * The certificate, key and client CA of the public listener, read from PEM files and held in memory.
 *
 * An operator hands over the files a CA gives out — a CDN's origin certificate is exactly that — and
 * never builds a keystore or chooses a password: the stores exist only in this process, under a
 * password generated for it. Anything that would make the listener fail later, on a visitor's request,
 * fails here instead: a key that belongs to another certificate, or a certificate already expired.
 */
class TlsMaterial private constructor(
    val keyStore: KeyStore,
    val trustStore: KeyStore?,
    val expiresAt: Instant,
    private val password: CharArray,
) {

    /** A fresh copy each time: the engine wipes the array it is given once it has read the key. */
    fun password(): CharArray = password.copyOf()

    companion object {
        const val ALIAS = "regolith-pages"

        fun load(tls: PagesConfig.Tls, clock: Clock = Clock.System): TlsMaterial {
            val chain = certificates(tls.certificate)
            check(chain.isNotEmpty()) { "${tls.certificate} holds no certificate" }
            val leaf = chain.first()
            val expiresAt = leaf.notAfter.toInstant().toKotlinInstant()
            check(expiresAt > clock.now()) { "The certificate in ${tls.certificate} expired at $expiresAt" }

            val key = privateKey(tls.key)
            check(belongsTo(key, leaf)) { "The key in ${tls.key} does not belong to the certificate in ${tls.certificate}" }

            val password = CharArray(PASSWORD_CHARS).also { chars ->
                val random = SecureRandom()
                for (i in chars.indices) chars[i] = ALPHABET[random.nextInt(ALPHABET.length)]
            }
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(ALIAS, key, password, chain.toTypedArray())
            }
            val trustStore = tls.clientCa?.let { path ->
                val authorities = certificates(path)
                check(authorities.isNotEmpty()) { "$path holds no certificate" }
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    authorities.forEachIndexed { index, certificate -> setCertificateEntry("client-ca-$index", certificate) }
                }
            }

            return TlsMaterial(keyStore, trustStore, expiresAt, password)
        }

        private fun certificates(path: Path): List<X509Certificate> = Files.newInputStream(path).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }
        }

        private fun privateKey(path: Path): PrivateKey {
            val pem = path.readText()
            check(PKCS1_HEADER !in pem) {
                "$path is a PKCS#1 key; convert it to PKCS#8 with: openssl pkcs8 -topk8 -nocrypt -in <key> -out <key>.pk8"
            }
            val body = pem.substringAfter(PKCS8_HEADER, missingDelimiterValue = "").substringBefore(PKCS8_FOOTER)
            check(body.isNotBlank()) { "$path holds no unencrypted PKCS#8 key (`$PKCS8_HEADER`)" }
            val spec = PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(body))

            return KEY_ALGORITHMS.firstNotNullOfOrNull { algorithm ->
                runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }.getOrNull()
            } ?: error("$path holds a key of an algorithm this server does not read; use RSA or ECDSA")
        }

        /** Signs with the key and verifies with the certificate: the only proof that the two are a pair. */
        private fun belongsTo(key: PrivateKey, certificate: X509Certificate): Boolean {
            val algorithm = if (key.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
            val probe = "regolith-pages".toByteArray()
            val signature = Signature.getInstance(algorithm).run {
                initSign(key)
                update(probe)
                sign()
            }

            return runCatching {
                Signature.getInstance(algorithm).run {
                    initVerify(certificate.publicKey)
                    update(probe)
                    verify(signature)
                }
            }.getOrDefault(false)
        }

        private const val PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----"
        private const val PKCS8_FOOTER = "-----END PRIVATE KEY-----"
        private const val PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----"
        private const val PASSWORD_CHARS = 32
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private val KEY_ALGORITHMS = listOf("RSA", "EC")
    }
}
