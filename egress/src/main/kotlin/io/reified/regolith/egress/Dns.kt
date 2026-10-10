package io.reified.regolith.egress

import java.io.ByteArrayOutputStream

/**
 * The little of the DNS wire format the proxy needs: the question of a query, an answer that refuses
 * it, and the IPv4 addresses of an answer to its own lookups. Every read is checked against the
 * packet's length; a packet that does not parse is never answered and never forwarded.
 */
internal object Dns {
    const val HEADER = 12
    const val TYPE_A = 1
    const val NXDOMAIN = 3
    const val SERVFAIL = 2

    private const val CLASS_IN = 1
    private const val TYPE_OPT = 41
    private const val RESPONSE = 0x8000
    private const val RECURSION_DESIRED = 0x0100
    private const val RECURSION_AVAILABLE = 0x0080
    private const val OPCODE_AND_RD = 0x7900
    private const val EDNS_PAYLOAD = 1232

    /** The question of a standard query: its id, the name asked for, and where the question ends. */
    class Question(val id: Int, val name: String, val end: Int)

    /** The single question of a standard query, or null when [packet] is anything else. */
    fun question(packet: ByteArray, length: Int = packet.size): Question? {
        if (length < HEADER + 5) return null
        val flags = u16(packet, 2)
        if (flags and RESPONSE != 0 || (flags shr 11) and 0xF != 0 || u16(packet, 4) != 1) return null
        val labels = mutableListOf<String>()
        var at = HEADER

        while (true) {
            if (at >= length) return null
            val size = packet[at].toInt() and 0xFF
            at++
            if (size == 0) break
            // a question never carries a compression pointer, and a label is at most 63 bytes.
            if (size > 63 || at + size > length) return null
            labels += String(packet, at, size, Charsets.ISO_8859_1)
            at += size
        }
        if (at + 4 > length) return null

        return Question(u16(packet, 0), labels.joinToString("."), at + 4)
    }

    /** An answer to [query] that holds only its question and [rcode]. */
    fun refusal(query: ByteArray, question: Question, rcode: Int): ByteArray {
        val answer = query.copyOf(question.end)
        put16(answer, 2, RESPONSE or (u16(query, 2) and OPCODE_AND_RD) or RECURSION_AVAILABLE or rcode)
        put16(answer, 4, 1)
        put16(answer, 6, 0)
        put16(answer, 8, 0)
        put16(answer, 10, 0)

        return answer
    }

    /** A recursive query for the A records of [name], with room for an answer larger than 512 bytes. */
    fun query(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        listOf(id, RECURSION_DESIRED, 1, 0, 0, 1).forEach { out.write16(it) }

        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.ISO_8859_1))
        }
        out.write(0)
        out.write16(TYPE_A)
        out.write16(CLASS_IN)
        out.write(0)
        out.write16(TYPE_OPT)
        out.write16(EDNS_PAYLOAD)
        out.write16(0)
        out.write16(0)
        out.write16(0)

        return out.toByteArray()
    }

    /** The IPv4 addresses of a successful answer to [id], or null when [packet] is not one. */
    fun addresses(packet: ByteArray, length: Int, id: Int): List<Long>? {
        if (length < HEADER || u16(packet, 0) != id) return null
        val flags = u16(packet, 2)
        if (flags and RESPONSE == 0 || flags and 0xF != 0) return null
        var at = HEADER

        repeat(u16(packet, 4)) {
            at = (skipName(packet, length, at) ?: return null) + 4
        }
        val found = mutableListOf<Long>()

        repeat(u16(packet, 6)) {
            at = skipName(packet, length, at) ?: return null
            if (at + 10 > length) return null
            val type = u16(packet, at)
            val klass = u16(packet, at + 2)
            val size = u16(packet, at + 8)
            at += 10
            if (at + size > length) return null
            if (type == TYPE_A && klass == CLASS_IN && size == 4) found += Ipv4.of(packet, at)
            at += size
        }

        return found
    }

    private fun skipName(packet: ByteArray, length: Int, start: Int): Int? {
        var at = start

        while (at < length) {
            val size = packet[at].toInt() and 0xFF
            when {
                size == 0 -> return at + 1
                size and 0xC0 == 0xC0 -> return (at + 2).takeIf { it <= length }
                size > 63 -> return null
                else -> at += size + 1
            }
        }

        return null
    }

    fun u16(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun put16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value shr 8).toByte()
        bytes[at + 1] = value.toByte()
    }

    private fun ByteArrayOutputStream.write16(value: Int) {
        write(value shr 8)
        write(value and 0xFF)
    }
}
