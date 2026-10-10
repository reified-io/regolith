package io.reified.regolith.egress

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * The public resolvers a sandbox is given. The proxy asks them for its own lookups, so a name means to
 * it what it means to the sandbox and never what the host's resolver would make of it, and forwards to
 * them the queries a sandbox's policy allows.
 */
internal class Upstream(private val resolvers: List<InetSocketAddress>) {
    private val random = SecureRandom()

    /** The IPv4 addresses [name] resolves to, or null when no resolver gave an answer. */
    fun lookup(name: String): List<Long>? {
        for (resolver in resolvers) {
            val id = random.nextInt(0x10000)
            val reply = udp(resolver, Dns.query(id, name)) ?: continue

            return Dns.addresses(reply, reply.size, id) ?: continue
        }

        return null
    }

    /** Forwards a sandbox's query unchanged and returns the first answer to it, or null when none came. */
    fun forward(query: ByteArray, tcp: Boolean): ByteArray? {
        val id = Dns.u16(query, 0)

        for (resolver in resolvers) {
            val reply = (if (tcp) tcp(resolver, query) else udp(resolver, query)) ?: continue
            if (reply.size >= Dns.HEADER && Dns.u16(reply, 0) == id) return reply
        }

        return null
    }

    // a connected socket takes datagrams from the resolver alone, on a port the kernel picked at random.
    private fun udp(resolver: InetSocketAddress, query: ByteArray): ByteArray? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(resolver)
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(MAX_MESSAGE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            buffer.copyOf(packet.length)
        }
    } catch (_: IOException) {
        null
    }

    private fun tcp(resolver: InetSocketAddress, query: ByteArray): ByteArray? = try {
        Socket().use { socket ->
            socket.connect(resolver, TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val size = input.readUnsignedShort()
            input.readNBytes(size).takeIf { it.size == size }
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val TIMEOUT_MS = 3000
        const val MAX_MESSAGE = 65535
    }
}
