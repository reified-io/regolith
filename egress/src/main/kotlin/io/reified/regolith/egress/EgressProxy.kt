package io.reified.regolith.egress

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Where the proxy listens and whom it asks. Connections go upstream to ports 80 and 443 except in tests. */
data class EgressConfig(
    val listen: InetAddress,
    val httpPort: Int,
    val tlsPort: Int,
    val dnsPort: Int,
    val resolvers: List<InetSocketAddress>,
    val upstreamHttpPort: Int = 80,
    val upstreamTlsPort: Int = 443,
)

/**
 * The proxy behind domain rules. The host firewall redirects a sandbox's HTTP, HTTPS and DNS here, by
 * its source address, when its policy names domains; nothing else of it arrives.
 *
 * A connection is matched by the name it opens with — the TLS server name, or the `Host` of a plain
 * request — and goes upstream only to an address the proxy resolved for that name itself, never to the
 * address the sandbox dialed, so neither an address literal nor a resolver of the sandbox's choosing
 * gets past it. A name that resolves into refused space reaches nothing. A query for a name the policy
 * does not cover is answered as a name that does not exist, so DNS carries nothing out either.
 *
 * What it cannot see, it does not claim: past the opening, the bytes of a connection are not read, so
 * a server that routes by something inside TLS can still be asked for another site it serves.
 */
class EgressProxy(private val config: EgressConfig, private val report: (String) -> Unit) : AutoCloseable {
    @Volatile
    private var policy: Policy = Policy.NONE

    private val upstream = Upstream(config.resolvers)
    private val open: MutableSet<Splice> = ConcurrentHashMap.newKeySet()
    private val connections = ConcurrentHashMap<String, AtomicInteger>()
    private val queries = ConcurrentHashMap<String, AtomicInteger>()
    private val reported = ConcurrentHashMap<String, Long>()
    private val threads = Thread.ofVirtual().name("egress-", 0).factory()

    private val http = ServerSocket(config.httpPort, BACKLOG, config.listen)
    private val tls = ServerSocket(config.tlsPort, BACKLOG, config.listen)
    private val dnsTcp = ServerSocket(config.dnsPort, BACKLOG, config.listen)
    private val dnsUdp = DatagramSocket(InetSocketAddress(config.listen, dnsTcp.localPort))

    /** The ports bound, which tests ask for after listening on any. */
    val ports: List<Int> get() = listOf(http.localPort, tls.localPort, dnsTcp.localPort)

    fun start() {
        accept(http) { client, source -> proxy(client, source, HttpHead::read, config.upstreamHttpPort, plain = true) }
        accept(tls) { client, source -> proxy(client, source, ClientHello::read, config.upstreamTlsPort, plain = false) }
        accept(dnsTcp, ::dnsOverTcp)
        threads.newThread(::dnsOverUdp).start()
    }

    /** Puts [table] in force, and closes every open connection it no longer allows. */
    fun update(table: EgressTable) {
        val next = Policy.of(table)
        policy = next
        open.filter { !next.allows(it.source, it.name) }.forEach(Splice::close)
    }

    override fun close() {
        listOf(http, tls, dnsTcp, dnsUdp).forEach(AutoCloseable::close)
        open.forEach(Splice::close)
    }

    private fun accept(server: ServerSocket, serve: (Socket, String) -> Unit) {
        threads.newThread {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    continue
                }
                threads.newThread { admitted(client, serve) }.start()
            }
        }.start()
    }

    /** Runs one connection within its source's share of the proxy, and always closes it. */
    private fun admitted(client: Socket, serve: (Socket, String) -> Unit) {
        val source = client.inetAddress.hostAddress
        val count = connections.computeIfAbsent(source) { AtomicInteger() }

        try {
            if (count.incrementAndGet() <= MAX_CONNECTIONS) serve(client, source)
        } catch (_: IOException) {
        } finally {
            count.decrementAndGet()
            client.close()
        }
    }

    private fun proxy(client: Socket, source: String, read: (InputStream) -> Opening?, port: Int, plain: Boolean) {
        client.soTimeout = OPENING_TIMEOUT_MS
        val opening = read(client.getInputStream()) ?: return
        val name = opening.name
        val current = policy

        if (name == null || !current.allows(source, name)) {
            refused(source, name ?: "(none)", "not allowed")
            val message = name?.let { "$it is not allowed by this sandbox's network policy" } ?: "this request names no host"
            client.getOutputStream().write(if (plain) answer("403 Forbidden", message) else alert(UNRECOGNIZED_NAME))
            return
        }

        val target = when (val reached = reach(name, port, current)) {
            is Reached.Open -> reached.socket
            Reached.Refused -> {
                refused(source, name, "refused address")
                client.getOutputStream().write(if (plain) answer("403 Forbidden", "$name resolves only to addresses no sandbox may reach") else alert(UNRECOGNIZED_NAME))
                return
            }
            Reached.Failed -> {
                client.getOutputStream().write(if (plain) answer("502 Bad Gateway", "$name could not be reached") else alert(INTERNAL_ERROR))
                return
            }
        }

        target.use {
            it.getOutputStream().write(opening.bytes)
            Splice(source, name, client, it).run()
        }
    }

    // a name is only as good as where it leads: one that resolves into refused space opens nothing.
    private fun reach(name: String, port: Int, current: Policy): Reached {
        val addresses = upstream.lookup(name) ?: return Reached.Failed
        val allowed = addresses.filterNot(current::refuses)
        if (allowed.isEmpty()) return if (addresses.isEmpty()) Reached.Failed else Reached.Refused

        for (address in allowed.take(MAX_ATTEMPTS)) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(Ipv4.address(address), port), CONNECT_TIMEOUT_MS)
                return Reached.Open(socket)
            } catch (_: IOException) {
                socket.close()
            }
        }

        return Reached.Failed
    }

    private fun dnsOverUdp() {
        val buffer = ByteArray(MAX_QUERY)

        while (!dnsUdp.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                dnsUdp.receive(packet)
            } catch (_: IOException) {
                continue
            }
            val source = packet.address.hostAddress
            val query = buffer.copyOf(packet.length)
            val sender = packet.socketAddress
            val count = queries.computeIfAbsent(source) { AtomicInteger() }
            // a source past its share loses the query, as a congested resolver would; it asks again.
            if (count.incrementAndGet() > MAX_QUERIES) {
                count.decrementAndGet()
                continue
            }
            threads.newThread {
                try {
                    answer(query, source, tcp = false)?.let { dnsUdp.send(DatagramPacket(it, it.size, sender)) }
                } catch (_: IOException) {
                } finally {
                    count.decrementAndGet()
                }
            }.start()
        }
    }

    private fun dnsOverTcp(client: Socket, source: String) {
        client.soTimeout = DNS_IDLE_MS
        val input = DataInputStream(client.getInputStream())
        val output = DataOutputStream(client.getOutputStream())

        while (true) {
            val size = input.readUnsignedShort()
            if (size < Dns.HEADER || size > MAX_QUERY) return
            val query = input.readNBytes(size)
            if (query.size < size) return
            val reply = answer(query, source, tcp = true) ?: return
            output.writeShort(reply.size)
            output.write(reply)
            output.flush()
        }
    }

    /** The answer to one query: forwarded when the policy covers the name, otherwise a name that does not exist. */
    private fun answer(query: ByteArray, source: String, tcp: Boolean): ByteArray? {
        val question = Dns.question(query) ?: return null
        val name = Names.hostName(question.name)

        if (name == null || !policy.allows(source, name)) {
            refused(source, question.name.ifEmpty { "." }, "not allowed")
            return Dns.refusal(query, question, Dns.NXDOMAIN)
        }

        return upstream.forward(query, tcp) ?: Dns.refusal(query, question, Dns.SERVFAIL)
    }

    // once a minute per sandbox and name: a resolver asks again and again, and so do agents. the sandbox
    // is part of the key, since an address passes to the next sandbox while the old one is still counted.
    private fun refused(source: String, name: String, why: String) {
        val now = System.nanoTime()
        val sandbox = policy.sandbox(source)
        var due = false
        if (reported.size > MAX_REPORTED) reported.clear()
        reported.compute("$sandbox $source $name") { _, last ->
            if (last == null || now - last >= REPORT_NANOS) now.also { due = true } else last
        }
        if (due) report("Refused: sandbox=[$sandbox] source=[$source] name=[$name] reason=[$why]")
    }

    private sealed interface Reached {
        class Open(val socket: Socket) : Reached

        data object Refused : Reached

        data object Failed : Reached
    }

    /** Both directions of one proxied connection, ended together when the policy stops allowing it. */
    private inner class Splice(val source: String, val name: String, private val client: Socket, private val upstream: Socket) {
        private val closed = AtomicBoolean()

        @Volatile
        private var active = System.nanoTime()

        fun run() {
            open += this

            try {
                // a table may have arrived between the check and joining the open set.
                if (!policy.allows(source, name)) return
                val back = threads.newThread { pump(upstream, client) }
                back.start()
                pump(client, upstream)
                back.join()
            } finally {
                open -= this
                close()
            }
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                client.close()
                upstream.close()
            }
        }

        /** Copies one direction; its end passes on as a half-close, anything else closes both. */
        private fun pump(from: Socket, to: Socket) {
            val buffer = ByteArray(BUFFER_BYTES)

            try {
                from.soTimeout = TICK_MS
                while (!closed.get()) {
                    val count = read(from, buffer) ?: continue
                    if (count < 0) {
                        to.shutdownOutput()
                        return
                    }
                    active = System.nanoTime()
                    to.getOutputStream().write(buffer, 0, count)
                }
            } catch (_: IOException) {
            }
            close()
        }

        /** Bytes read, -1 at the end, or null after a quiet tick; a connection quiet both ways for long ends. */
        private fun read(from: Socket, buffer: ByteArray): Int? = try {
            from.getInputStream().read(buffer)
        } catch (e: SocketTimeoutException) {
            if (System.nanoTime() - active > IDLE_NANOS) throw e
            null
        }
    }

    private companion object {
        const val BACKLOG = 256
        const val MAX_CONNECTIONS = 256
        const val MAX_QUERIES = 64
        const val MAX_QUERY = 4096
        const val MAX_ATTEMPTS = 3
        const val OPENING_TIMEOUT_MS = 10_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val DNS_IDLE_MS = 10_000
        const val TICK_MS = 30_000
        const val IDLE_NANOS = 10L * 60 * 1_000_000_000
        const val BUFFER_BYTES = 32 * 1024
        const val REPORT_NANOS = 60L * 1_000_000_000
        const val MAX_REPORTED = 10_000
        const val UNRECOGNIZED_NAME = 112
        const val INTERNAL_ERROR = 80

        /** A fatal TLS alert, which is all a refused handshake gets: no certificate is ever presented. */
        fun alert(description: Int): ByteArray = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, description.toByte())

        fun answer(status: String, message: String): ByteArray {
            val body = "regolith: $message\n".toByteArray()
            val head = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"

            return head.toByteArray() + body
        }
    }
}
