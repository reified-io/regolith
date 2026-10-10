package io.reified.regolith.egress.support

import io.reified.regolith.egress.Dns
import io.reified.regolith.egress.Ipv4
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/** A resolver on the loopback address answering A queries from a fixed table, and nothing else. */
class FakeResolver(private val records: Map<String, List<String>>) : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    /** Every name it was asked for, in order. */
    val asked = CopyOnWriteArrayList<String>()

    val address: InetSocketAddress get() = InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort)

    init {
        thread(isDaemon = true) {
            val buffer = ByteArray(4096)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: IOException) {
                    continue
                }
                val query = buffer.copyOf(packet.length)
                val name = Dns.question(query)?.name ?: continue
                asked += name
                val reply = answer(query, records[name].orEmpty())
                socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            }
        }
    }

    override fun close() = socket.close()

    companion object {
        /** An answer to [query] with one A record per address, each naming the question by a pointer. */
        fun answer(query: ByteArray, addresses: List<String>): ByteArray {
            val question = checkNotNull(Dns.question(query))
            val out = ByteArrayOutputStream()
            out.write(query, 0, 2)
            out.write16(0x8180 or if (addresses.isEmpty()) Dns.NXDOMAIN else 0)
            listOf(1, addresses.size, 0, 0).forEach { out.write16(it) }
            out.write(query, Dns.HEADER, question.end - Dns.HEADER)

            for (address in addresses) {
                out.write16(0xC00C)
                out.write16(Dns.TYPE_A)
                out.write16(1)
                out.write16(0)
                out.write16(60)
                out.write16(4)
                val value = checkNotNull(Ipv4.parse(address))
                (3 downTo 0).forEach { out.write(((value shr (it * 8)) and 0xFF).toInt()) }
            }

            return out.toByteArray()
        }

        private fun ByteArrayOutputStream.write16(value: Int) {
            write(value shr 8)
            write(value and 0xFF)
        }
    }
}

/**
 * A server on the loopback address that reads each connection to its end, keeps what it sent, and only
 * then answers with [reply]; a connection whose sender never ends its side stays open.
 */
class FakeUpstream(private val reply: ByteArray) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())

    /** What each connection sent before it ended, in order. */
    val received = CopyOnWriteArrayList<ByteArray>()

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    continue
                }
                thread(isDaemon = true) {
                    client.use {
                        received += it.getInputStream().readAllBytes()
                        it.getOutputStream().write(reply)
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

/** TLS ClientHellos, made by hand where the test needs a shape and by the JDK where it needs a real one. */
object Hellos {
    /** One record holding a minimal ClientHello, with a server name when [name] is given. */
    fun clientHello(name: String?): ByteArray {
        val extensions = ByteArrayOutputStream()

        if (name != null) {
            val host = name.toByteArray()
            extensions.write16(0)
            extensions.write16(host.size + 5)
            extensions.write16(host.size + 3)
            extensions.write(0)
            extensions.write16(host.size)
            extensions.write(host)
        }
        // supported_versions, so the name is not the only extension there is to walk past.
        extensions.write(byteArrayOf(0x00, 0x2b, 0x00, 0x03, 0x02, 0x03, 0x04))
        val body = ByteArrayOutputStream()
        body.write16(0x0303)
        body.write(ByteArray(32))
        body.write(0)
        body.write16(2)
        body.write16(0x1301)
        body.write(1)
        body.write(0)
        body.write16(extensions.size())
        body.write(extensions.toByteArray())
        val handshake = ByteArrayOutputStream()
        handshake.write(1)
        handshake.write(body.size() shr 16)
        handshake.write16(body.size() and 0xFFFF)
        handshake.write(body.toByteArray())

        return record(handshake.toByteArray())
    }

    /** The first flight of the JDK's own TLS client, as a real program would send it. */
    fun jdkHello(name: String): ByteArray {
        val engine = SSLContext.getDefault().createSSLEngine(name, 443)
        engine.useClientMode = true
        engine.sslParameters = engine.sslParameters.apply { serverNames = listOf(SNIHostName(name)) }
        engine.beginHandshake()
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()

        return ByteArray(out.remaining()).also { out.get(it) }
    }

    fun record(fragment: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x16, 0x03, 0x01))
        out.write16(fragment.size)
        out.write(fragment)

        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.write16(value: Int) {
        write(value shr 8)
        write(value and 0xFF)
    }
}
