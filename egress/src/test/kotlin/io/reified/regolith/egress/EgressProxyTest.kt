package io.reified.regolith.egress

import io.reified.regolith.egress.support.FakeResolver
import io.reified.regolith.egress.support.FakeUpstream
import io.reified.regolith.egress.support.Hellos
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EgressProxyTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val resolver = FakeResolver(mapOf("files.example.com" to listOf("127.0.0.1"), "inside.example.com" to listOf("10.1.2.3")))
    private val web = FakeUpstream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
    private val reports = CopyOnWriteArrayList<String>()
    private val proxy = EgressProxy(
        EgressConfig(loopback, 0, 0, 0, listOf(resolver.address), upstreamHttpPort = web.port, upstreamTlsPort = web.port),
        reports::add,
    ).also { it.start() }
    private val http = proxy.ports[0]
    private val tls = proxy.ports[1]
    private val dns = proxy.ports[2]

    @AfterTest
    fun close() {
        proxy.close()
        resolver.close()
        web.close()
    }

    private fun allow(vararg domains: String, refused: List<String> = listOf("10.0.0.0/8")) =
        proxy.update(EgressTable(1, refused, mapOf("127.0.0.1" to EgressSandbox("sandbox-1", domains.toList()))))

    private fun exchange(port: Int, request: ByteArray): ByteArray = Socket(loopback, port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write(request)
        socket.shutdownOutput()
        socket.getInputStream().readAllBytes()
    }

    private fun get(host: String) = exchange(http, "GET / HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray()).decodeToString()

    @Test
    fun `an allowed host goes upstream with its request untouched`() {
        allow("*.example.com")
        val request = "GET /x HTTP/1.1\r\nHost: files.example.com\r\n\r\n"

        assertTrue(exchange(http, request.toByteArray()).decodeToString().endsWith("ok"))
        assertEquals(request, web.received.single().decodeToString())
    }

    @Test
    fun `a host the policy does not cover is answered by the proxy and reaches nothing`() {
        allow("files.example.com")

        val answer = get("other.example.org")

        assertTrue(answer.startsWith("HTTP/1.1 403"), answer)
        assertTrue("other.example.org is not allowed" in answer)
        assertEquals(emptyList(), web.received)
        assertEquals(emptyList(), resolver.asked)
        assertTrue(reports.single().contains("sandbox=[sandbox-1]"))
    }

    @Test
    fun `a refusal is reported once a minute per sandbox, and again for the next sandbox on the address`() {
        allow("files.example.com")
        repeat(3) { get("other.example.org") }
        proxy.update(EgressTable(2, listOf("10.0.0.0/8"), mapOf("127.0.0.1" to EgressSandbox("sandbox-2", listOf("files.example.com")))))
        get("other.example.org")

        assertEquals(listOf("sandbox-1", "sandbox-2"), reports.map { it.substringAfter("sandbox=[").substringBefore(']') })
    }

    @Test
    fun `an allowed name that resolves into refused space opens nothing`() {
        allow("inside.example.com")

        assertTrue(get("inside.example.com").contains("resolves only to addresses no sandbox may reach"))
        allow("files.example.com", refused = listOf("127.0.0.0/8"))
        assertTrue(get("files.example.com").startsWith("HTTP/1.1 403"))
        assertEquals(emptyList(), web.received)
    }

    @Test
    fun `an address the table does not hold is refused everything`() {
        proxy.update(EgressTable(1, emptyList(), mapOf("172.30.0.9" to EgressSandbox("sandbox-2", listOf("files.example.com")))))

        assertTrue(get("files.example.com").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `a tls connection is matched by its server name and replayed upstream whole`() {
        allow("files.example.com")
        val hello = Hellos.clientHello("files.example.com")

        assertTrue(exchange(tls, hello).decodeToString().endsWith("ok"))
        assertContentEquals(hello, web.received.single())
    }

    @Test
    fun `a tls connection for another name, or for none, gets an alert and nothing upstream`() {
        allow("files.example.com")

        listOf("other.example.org", null).forEach { name ->
            val answer = exchange(tls, Hellos.clientHello(name))
            assertContentEquals(byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 112), answer)
        }
        assertEquals(emptyList(), web.received)
    }

    @Test
    fun `a query for an allowed name is answered by the resolver and any other does not exist`() {
        allow("*.example.com")

        val allowed = query("files.example.com")
        val refused = query("leak.example.org")

        assertEquals(listOf("127.0.0.1"), Dns.addresses(allowed, allowed.size, 7)?.map(Ipv4::text))
        assertEquals(Dns.NXDOMAIN, Dns.u16(refused, 2) and 0xF)
        assertEquals(listOf("files.example.com"), resolver.asked)
    }

    @Test
    fun `a table that drops a name closes the connections open to it`() {
        val slow = FakeUpstream(ByteArray(0))
        val proxy = EgressProxy(EgressConfig(loopback, 0, 0, 0, listOf(resolver.address), upstreamHttpPort = slow.port), reports::add)
        proxy.start()
        proxy.update(EgressTable(1, emptyList(), mapOf("127.0.0.1" to EgressSandbox("sandbox-1", listOf("files.example.com")))))

        Socket(loopback, proxy.ports[0]).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: files.example.com\r\n\r\n".toByteArray())
            // the upstream waits for an end that never comes, so the connection stays until the table changes.
            Thread.sleep(300)
            proxy.update(EgressTable(2, emptyList(), emptyMap()))
            assertEquals(-1, socket.getInputStream().read())
        }
        proxy.close()
        slow.close()
    }

    private fun query(name: String): ByteArray = DatagramSocket().use { socket ->
        socket.soTimeout = 5000
        val query = Dns.query(7, name)
        socket.send(DatagramPacket(query, query.size, loopback, dns))
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        buffer.copyOf(packet.length)
    }
}
