package io.reified.regolith.egress

import io.reified.regolith.egress.support.Hellos
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpeningsTest {
    @Test
    fun `a client hello is read whole with the name it asks for`() {
        val hello = Hellos.clientHello("files.example.com")
        val opening = checkNotNull(ClientHello.read(ByteArrayInputStream(hello + "after".toByteArray())))

        assertEquals("files.example.com", opening.name)
        assertContentEquals(hello, opening.bytes)
    }

    @Test
    fun `the hello of a real tls client is read too`() {
        assertEquals("files.example.com", ClientHello.read(ByteArrayInputStream(Hellos.jdkHello("files.example.com")))?.name)
    }

    @Test
    fun `a hello split across two records is still read`() {
        val hello = Hellos.clientHello("files.example.com")
        val body = hello.copyOfRange(5, hello.size)
        val split = body.size / 2
        val records = Hellos.record(body.copyOfRange(0, split)) + Hellos.record(body.copyOfRange(split, body.size))

        assertEquals("files.example.com", ClientHello.read(ByteArrayInputStream(records))?.name)
    }

    @Test
    fun `a hello without a server name names nothing, and plain bytes are no hello`() {
        assertNull(ClientHello.read(ByteArrayInputStream(Hellos.clientHello(null)))?.name)
        assertNull(ClientHello.read(ByteArrayInputStream("GET / HTTP/1.1\r\n\r\n".toByteArray())))
    }

    @Test
    fun `a request head names its host`() {
        val opening = HttpHead.read(ByteArrayInputStream("GET /a HTTP/1.1\r\nHost: Files.Example.com:80\r\nAccept: */*\r\n\r\nbody".toByteArray()))

        assertEquals("files.example.com", opening?.name)
        assertEquals("body", opening?.bytes?.decodeToString()?.substringAfter("\r\n\r\n"))
    }

    @Test
    fun `a head with no host, two hosts, a tunnel or a disagreeing target names nothing`() {
        listOf(
            "GET / HTTP/1.1\r\nAccept: */*",
            "GET / HTTP/1.1\r\nHost: a.example.com\r\nHost: b.example.com",
            "CONNECT a.example.com:443 HTTP/1.1\r\nHost: a.example.com:443",
            "GET http://b.example.com/ HTTP/1.1\r\nHost: a.example.com",
            "GET / HTTP/1.1\r\nHost: 10.0.0.1",
            "GET / HTTP/1.1\r\nHost: user@a.example.com",
        ).forEach { assertNull(HttpHead.host(it), it) }
    }

    @Test
    fun `a target in absolute form that agrees with its host is fine`() {
        assertEquals("a.example.com", HttpHead.host("GET http://a.example.com/x?y HTTP/1.1\r\nHost: a.example.com"))
    }

    @Test
    fun `a head that never ends within the cap is refused`() {
        val endless = ByteArrayInputStream(("GET / HTTP/1.1\r\nX: " + "y".repeat(HttpHead.MAX_BYTES)).toByteArray())

        assertNull(HttpHead.read(endless))
    }
}
