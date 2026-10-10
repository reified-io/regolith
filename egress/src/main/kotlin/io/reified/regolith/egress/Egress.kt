package io.reified.regolith.egress

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The `egress` command: runs the proxy until its stdin ends, taking each line as a new [EgressTable]
 * and printing [EgressTable.ACK] with its sequence once it is in force. The control plane holds the
 * other end of stdin, so when the server goes, every policy goes with it and so does the proxy.
 *
 * Stdout is the channel back: [EgressTable.READY], the acknowledgements, and one line per refusal,
 * which the server writes to its own log.
 */
fun runEgress(args: List<String>): Int {
    val options = options(args) ?: return usage()
    val listen = options["--listen"]?.takeIf { Ipv4.parse(it) != null } ?: return usage()
    val ports = listOf("--http", "--tls", "--dns").map { options[it]?.toIntOrNull()?.takeIf { port -> port in 1..65535 } ?: return usage() }
    val resolvers = options["--resolvers"]?.split(',')?.map { resolver ->
        val host = resolver.substringBefore(':').takeIf { Ipv4.parse(it) != null } ?: return usage()
        InetSocketAddress(InetAddress.getByName(host), resolver.substringAfter(':', "53").toIntOrNull() ?: return usage())
    } ?: return usage()
    val config = EgressConfig(InetAddress.getByName(listen), ports[0], ports[1], ports[2], resolvers)

    EgressProxy(config, ::say).use { proxy ->
        proxy.start()
        say(EgressTable.READY)
        val input = System.`in`.bufferedReader()

        while (true) {
            val line = input.readLine() ?: break
            val table = EgressTable.decode(line)
            proxy.update(table)
            say(EgressTable.ACK + table.seq)
        }
    }

    return 0
}

private fun options(args: List<String>): Map<String, String>? {
    if (args.size % 2 != 0) return null

    return args.chunked(2).associate { (name, value) -> name to value }
}

private fun usage(): Int {
    System.err.println("usage: server egress --listen ADDRESS --http PORT --tls PORT --dns PORT --resolvers ADDRESS[:PORT],...")

    return 2
}

private fun say(line: String) {
    println(line)
    System.out.flush()
}
