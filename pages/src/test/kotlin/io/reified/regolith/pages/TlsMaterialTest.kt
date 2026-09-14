package io.reified.regolith.pages

import java.nio.file.Path
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

internal fun fixture(name: String): Path = Path.of(checkNotNull(TlsMaterialTest::class.java.getResource("/tls/$name")) { "missing fixture $name" }.toURI())

class TlsMaterialTest {
    private val server = PagesConfig.Tls(fixture("server-chain.pem"), fixture("server.key"), clientCa = null)

    @Test
    fun `a chain and its key become an in-memory store`() {
        val material = TlsMaterial.load(server)
        val chain = material.keyStore.getCertificateChain(TlsMaterial.ALIAS)
        assertEquals(2, chain.size, "the leaf and the CA that signed it")
        assertEquals("CN=demo.sites.test", (chain.first() as X509Certificate).subjectX500Principal.name)
        assertNull(material.trustStore, "no client CA, no client certificates required")
    }

    @Test
    fun `a client CA turns into the trust store that makes client certificates mandatory`() {
        val material = TlsMaterial.load(server.copy(clientCa = fixture("client-ca.pem")))
        assertNotNull(material.trustStore).let { assertEquals(1, it.size()) }
    }

    // a mismatched pair would only fail on the first handshake, long after the operator walked away
    @Test
    fun `a key that belongs to another certificate stops the role at startup`() {
        val error = assertFailsWith<IllegalStateException> { TlsMaterial.load(server.copy(key = fixture("stranger.key"))) }
        assertContains(error.message.orEmpty(), "does not belong")
    }

    @Test
    fun `a pkcs1 key is refused with the command that converts it`() {
        val error = assertFailsWith<IllegalStateException> { TlsMaterial.load(server.copy(key = fixture("server-pkcs1.key"))) }
        assertContains(error.message.orEmpty(), "openssl pkcs8 -topk8")
    }

    @Test
    fun `an expired certificate is refused before anything listens`() {
        val farFuture = object : Clock {
            override fun now(): Instant = Instant.parse("2200-01-01T00:00:00Z")
        }
        val error = assertFailsWith<IllegalStateException> { TlsMaterial.load(server, farFuture) }
        assertContains(error.message.orEmpty(), "expired")
    }

    @Test
    fun `the password handed to the engine is a copy it may wipe`() {
        val material = TlsMaterial.load(server)
        material.password().fill(Char(0))
        assertEquals(material.password().size, material.password().count { it != Char(0) })
    }
}
