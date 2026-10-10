package io.reified.regolith.egress

import java.net.Inet4Address
import java.net.InetAddress

/** Host names as the proxy compares them: lowercase, no trailing dot, never an address. */
internal object Names {
    private val label = Regex("[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?")

    /** [raw] as a host name, or null when it is not one; an address literal is not a name. */
    fun hostName(raw: String): String? {
        val name = raw.lowercase().removeSuffix(".")
        if (name.isEmpty() || name.length > MAX_NAME) return null
        val labels = name.split('.')
        if (labels.any { !label.matches(it) }) return null
        // no top-level domain is all digits, so a name ending in one is an address written as a name.
        if (labels.last().all { it.isDigit() }) return null

        return name
    }

    /** Whether [pattern] covers [name]: itself, or with a leading `*.` every name below it. */
    fun matches(pattern: String, name: String): Boolean {
        if (!pattern.startsWith("*.")) return name == pattern
        val suffix = pattern.substring(1)

        return name.length > suffix.length && name.endsWith(suffix)
    }

    private const val MAX_NAME = 253
}

/** IPv4 addresses as unsigned 32-bit values, which is all the refused list needs. */
internal object Ipv4 {
    private val shape = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

    fun parse(text: String): Long? {
        val octets = shape.matchEntire(text)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
        if (octets.any { it > 255 }) return null

        return octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
    }

    fun of(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[at + i].toLong() and 0xFF) }

    fun address(value: Long): Inet4Address =
        InetAddress.getByAddress(ByteArray(4) { i -> (value shr (24 - 8 * i)).toByte() }) as Inet4Address

    fun text(value: Long): String = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 0xFF).toString() }
}

/** An IPv4 network of the refused list. */
internal class Network private constructor(private val base: Long, private val mask: Long) {
    fun contains(address: Long): Boolean = (address and mask) == base

    companion object {
        fun parse(text: String): Network {
            val address = checkNotNull(Ipv4.parse(text.substringBefore('/'))) { "Not an IPv4 network: $text" }
            val prefix = text.substringAfter('/', "32").toIntOrNull()
            check(prefix != null && prefix in 0..32) { "Not an IPv4 network: $text" }
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

            return Network(address and mask, mask)
        }
    }
}

/** One table, put in force whole: which names each address may reach, and where no name may lead. */
internal class Policy private constructor(
    private val refused: List<Network>,
    private val sandboxes: Map<String, EgressSandbox>,
) {

    fun sandbox(source: String): String? = sandboxes[source]?.sandbox

    fun allows(source: String, name: String): Boolean =
        sandboxes[source]?.domains?.any { Names.matches(it, name) } == true

    fun refuses(address: Long): Boolean = refused.any { it.contains(address) }

    companion object {
        /** Before the first table: nothing is allowed and every address is refused. */
        val NONE = Policy(listOf(Network.parse("0.0.0.0/0")), emptyMap())

        fun of(table: EgressTable): Policy = Policy(table.refused.map(Network::parse), table.sandboxes)
    }
}
