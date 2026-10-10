package io.reified.regolith.egress

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * What a connection opens with, read only as far as the name it asks for, and kept whole so it can be
 * replayed upstream unchanged. [name] is null when the opening names nothing the proxy can match.
 */
internal class Opening(val bytes: ByteArray, val name: String?)

/** The TLS ClientHello, which carries the name a connection is for in its server_name extension. */
internal object ClientHello {
    const val MAX_BYTES = 64 * 1024

    private const val HANDSHAKE = 0x16
    private const val CLIENT_HELLO = 1
    private const val SERVER_NAME = 0
    private const val HOST_NAME = 0
    private const val MAX_RECORD = 16384 + 2048

    /** Reads the records that carry the ClientHello; null when the stream does not open with one. */
    fun read(input: InputStream): Opening? {
        val raw = ByteArrayOutputStream()
        val handshake = ByteArrayOutputStream()

        while (true) {
            val header = input.readNBytes(5)
            if (header.size < 5 || header[0].toInt() != HANDSHAKE || header[1].toInt() != 3) return null
            val length = Dns.u16(header, 3)
            if (length == 0 || length > MAX_RECORD || raw.size() + 5 + length > MAX_BYTES) return null
            val body = input.readNBytes(length)
            if (body.size < length) return null
            raw.write(header)
            raw.write(body)
            handshake.write(body)
            // a hello may span records; it is whole once its own length has arrived.
            val message = handshake.toByteArray()
            if (message.size < 4) continue
            if (message[0].toInt() != CLIENT_HELLO) return null
            val end = 4 + u24(message, 1)
            if (end > MAX_BYTES) return null
            if (message.size >= end) return Opening(raw.toByteArray(), serverName(message, 4, end)?.let(Names::hostName))
        }
    }

    /** The host_name of a ClientHello body between [start] and [end], or null when it names none. */
    fun serverName(m: ByteArray, start: Int, end: Int): String? {
        // legacy_version and random, then the session id, the cipher suites and the compression methods.
        var at = start + 2 + 32
        if (at + 1 > end) return null
        at += 1 + (m[at].toInt() and 0xFF)
        if (at + 2 > end) return null
        at += 2 + Dns.u16(m, at)
        if (at + 1 > end) return null
        at += 1 + (m[at].toInt() and 0xFF)
        if (at + 2 > end) return null
        val extensionsEnd = at + 2 + Dns.u16(m, at)
        if (extensionsEnd > end) return null
        at += 2

        while (at + 4 <= extensionsEnd) {
            val type = Dns.u16(m, at)
            val size = Dns.u16(m, at + 2)
            at += 4
            if (at + size > extensionsEnd) return null
            if (type == SERVER_NAME) return hostName(m, at, at + size)
            at += size
        }

        return null
    }

    private fun hostName(m: ByteArray, start: Int, end: Int): String? {
        if (start + 2 > end) return null
        val listEnd = start + 2 + Dns.u16(m, start)
        if (listEnd > end) return null
        var at = start + 2

        while (at + 3 <= listEnd) {
            val type = m[at].toInt() and 0xFF
            val size = Dns.u16(m, at + 1)
            at += 3
            if (at + size > listEnd) return null
            if (type == HOST_NAME) return String(m, at, size, Charsets.US_ASCII)
            at += size
        }

        return null
    }

    private fun u24(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xFF) shl 16) or Dns.u16(bytes, at + 1)
}

/** The head of a plain HTTP request, which names its host in the `Host` header. */
internal object HttpHead {
    const val MAX_BYTES = 32 * 1024

    private val end = "\r\n\r\n".toByteArray()

    /** Reads up to the end of the request head; null when the stream ends or the head runs past [MAX_BYTES]. */
    fun read(input: InputStream): Opening? {
        val head = ByteArrayOutputStream()
        val buffer = ByteArray(4096)

        while (head.size() < MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - head.size()))
            if (count < 0) return null
            head.write(buffer, 0, count)
            val bytes = head.toByteArray()
            val at = indexOf(bytes, end)
            if (at >= 0) return Opening(bytes, host(String(bytes, 0, at, Charsets.ISO_8859_1)))
        }

        return null
    }

    /**
     * The one host a request head names, or null when it names none, several, or two that disagree. A
     * request in absolute form is routed by its own authority, which must then name the same host, and
     * CONNECT asks for a tunnel this is not.
     */
    fun host(head: String): String? {
        val lines = head.split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3 || request[0] == "CONNECT") return null
        val hosts = lines.drop(1).filter { it.substringBefore(':').trim().equals("host", ignoreCase = true) }
        val host = hosts.singleOrNull()?.substringAfter(':')?.trim()?.let(::authorityHost) ?: return null
        val target = request[1]

        if (target.contains("://")) {
            val authority = target.substringAfter("://").substringBefore('/').substringBefore('?')
            if (authorityHost(authority) != host) return null
        }

        return host
    }

    private fun authorityHost(authority: String): String? {
        if ('@' in authority || authority.startsWith('[')) return null

        return Names.hostName(authority.substringBefore(':'))
    }

    private fun indexOf(bytes: ByteArray, pattern: ByteArray): Int {
        for (i in 0..bytes.size - pattern.size) {
            if (pattern.indices.all { bytes[i + it] == pattern[it] }) return i
        }

        return -1
    }
}
