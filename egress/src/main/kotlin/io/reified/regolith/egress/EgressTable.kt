package io.reified.regolith.egress

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the egress proxy enforces, sent whole by the control plane on every change as one JSON
 * object per line on the proxy's stdin. The proxy answers each line with [ACK] and its [seq] once the
 * table is in force, so the control plane never redirects an address before the proxy knows it.
 *
 * [refused] are the networks no connection may reach whatever a name resolves to: the platform floor,
 * the host's own addresses and the sandbox subnet. [sandboxes] holds every sandbox address with domain
 * rules; any other address is refused everything.
 */
@Serializable
data class EgressTable(
    val seq: Long,
    val refused: List<String>,
    val sandboxes: Map<String, EgressSandbox>,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** What the proxy prints once it listens; nothing reaches it before. */
        const val READY = "@ready"

        /** What the proxy prints, followed by a table's [seq], once that table is in force. */
        const val ACK = "@ack "

        private val json = Json

        fun decode(line: String): EgressTable = json.decodeFromString(serializer(), line)
    }
}

/**
 * One sandbox address: the sandbox it belongs to, for the log, and the names it may reach. A pattern
 * is a name, which matches itself only, or `*.` and a name, which matches every name below it.
 */
@Serializable
data class EgressSandbox(
    val sandbox: String,
    val domains: List<String>,
)
