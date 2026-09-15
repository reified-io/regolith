package io.reified.regolith.pages

import io.ktor.server.application.Application
import io.reified.regolith.protocol.pages.ReleaseFile
import java.io.FileInputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ListenerTlsTest {
    private val port = ServerSocket(0).use { it.localPort }

    private fun withListener(clientCa: Boolean, module: (PagesConfig, ReleaseStore) -> Application.() -> Unit, block: () -> Unit) {
        val dir = Files.createTempDirectory("regolith-pages-tls")
        val settings = config(dir)
        val tls = PagesConfig.Tls(fixture("server-chain.pem"), fixture("server.key"), if (clientCa) fixture("client-ca.pem") else null)
        val store = ReleaseStore(dir, settings.limits)
        runBlocking {
            val site = SiteName.parse("demo")
            val page = "<h1>over tls</h1>"
            val started = store.startRelease(site, listOf(ReleaseFile("index.html", sha256(page), page.length.toLong())))
            store.putBlob(sha256(page), page.byteInputStream())
            store.activate(site, ReleaseId.parse(started.release))
        }
        val server = listener("127.0.0.1", port, TlsMaterial.load(tls), module(settings, store))
        server.start(wait = false)

        try {
            block()
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
            dir.toFile().deleteRecursively()
        }
    }

    /** A raw request, so the headers are ours to set and the handshake failure is visible as it is. */
    private fun get(path: String, headers: String, withClientCertificate: Boolean = false): String {
        val trust = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server-ca", Files.newInputStream(fixture("server-ca.pem")).use { CertificateFactory.getInstance("X.509").generateCertificate(it) })
        }
        val keys = if (withClientCertificate) {
            val identity = KeyStore.getInstance("PKCS12").apply { FileInputStream(fixture("client.p12").toFile()).use { load(it, "changeit".toCharArray()) } }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identity, "changeit".toCharArray()) }.keyManagers
        } else {
            null
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(keys, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }.trustManagers, null)
        }

        return (context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.soTimeout = 5_000
            socket.startHandshake()
            socket.outputStream.write("GET $path HTTP/1.1\r\n${headers}Connection: close\r\n\r\n".toByteArray())
            socket.outputStream.flush()
            socket.inputStream.readBytes().decodeToString()
        }
    }

    private val sites: (PagesConfig, ReleaseStore) -> Application.() -> Unit = { settings, store -> { publicSites(settings, store) } }
    private val intake: (PagesConfig, ReleaseStore) -> Application.() -> Unit = { settings, store -> { pagesIntake(settings, store) } }

    @Test
    fun `the public listener terminates tls itself`() = withListener(clientCa = false, sites) {
        val response = get("/", "Host: demo.example.test\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
        assertContains(response, "<h1>over tls</h1>")
    }

    // origin pulls: a visitor who finds the machine's address cannot skip the cdn in front of it
    @Test
    fun `with a client CA, a request without a client certificate never reaches a site`() = withListener(clientCa = true, sites) {
        assertFailsWith<SSLException> { get("/", "Host: demo.example.test\r\n") }
        val response = get("/", "Host: demo.example.test\r\n", withClientCertificate = true)
        assertContains(response, "<h1>over tls</h1>")
    }

    // a control plane on another network publishes to https://pages.example.com with nothing in between
    @Test
    fun `the intake terminates tls of its own and still wants its token`() = withListener(clientCa = false, intake) {
        assertTrue(get("/v1/health", "Host: pages.example.test\r\n").startsWith("HTTP/1.1 200"))
        assertTrue(get("/v1/limits", "Host: pages.example.test\r\n").startsWith("HTTP/1.1 401"), "no token, no limits")
        val limits = get("/v1/limits", "Host: pages.example.test\r\nAuthorization: Bearer $TOKEN\r\n")
        assertTrue(limits.startsWith("HTTP/1.1 200"), limits.lineSequence().first())
        assertContains(limits, "maxFiles")
    }
}
