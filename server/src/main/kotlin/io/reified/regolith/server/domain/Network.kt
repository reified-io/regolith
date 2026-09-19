package io.reified.regolith.server.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a sandbox may connect. Every mode sits on top of [PlatformFloor]: no policy can reach a
 * destination the floor refuses.
 */
@Serializable
sealed interface NetworkPolicy {
    @Serializable
    @SerialName("public")
    data object Public : NetworkPolicy

    @Serializable
    @SerialName("none")
    data object None : NetworkPolicy

    @Serializable
    @SerialName("allowlist")
    data class Allowlist(val cidrs: List<Cidr>) : NetworkPolicy {
        init {
            requireValid(cidrs.size <= MAX_ENTRIES) { "An allowlist holds at most $MAX_ENTRIES entries" }

            for (cidr in cidrs) {
                requireValid(!PlatformFloor.refuses(cidr)) { "$cidr is never reachable from a sandbox" }
            }
        }

        companion object {
            const val MAX_ENTRIES = 64
        }
    }
}

/** An IPv4 network in canonical form: host bits cleared, prefix always written. */
@Serializable(with = CidrSerializer::class)
@JvmInline
value class Cidr private constructor(val value: String) {
    val prefix: Int get() = value.substringAfter('/').toInt()

    val network: Long get() = addressOf(value.substringBefore('/'))

    /** Whether every address of [other] lies inside this network. */
    fun contains(other: Cidr): Boolean =
        other.prefix >= prefix && (other.network and maskOf(prefix)) == network

    override fun toString(): String = value

    companion object {
        private val shape = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:/(\d{1,2}))?""")

        fun parse(raw: String): Cidr {
            val match = shape.matchEntire(raw.trim()) ?: throw RegolithError.Invalid("Expected an IPv4 address or CIDR, got `$raw`")
            val octets = match.groupValues.subList(1, 5).map { it.toInt() }
            requireValid(octets.all { it in 0..255 }) { "Expected an IPv4 address or CIDR, got `$raw`" }
            val prefix = match.groupValues[5].ifEmpty { "32" }.toInt()
            requireValid(prefix in 0..32) { "A CIDR prefix is between 0 and 32, got `$raw`" }
            val address = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() } and maskOf(prefix)

            return Cidr("${render(address)}/$prefix")
        }

        private fun maskOf(prefix: Int): Long = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

        private fun addressOf(dotted: String): Long =
            dotted.split('.').fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }

        private fun render(address: Long): String =
            (3 downTo 0).joinToString(".") { ((address shr (it * 8)) and 0xFF).toString() }
    }
}

/**
 * Destinations refused to every sandbox whatever its policy: private, shared, loopback, link-local
 * (cloud metadata lives there), benchmarking, multicast and reserved space. The enforcer adds what
 * a list of networks cannot express — the host's own addresses, outbound SMTP and IPv6.
 */
object PlatformFloor {
    val refused: List<Cidr> = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "224.0.0.0/4",
        "240.0.0.0/4",
    ).map(Cidr::parse)

    /** Whether [cidr] lies entirely inside refused space, so allowing it could never work. */
    fun refuses(cidr: Cidr): Boolean = refused.any { it.contains(cidr) }
}
