package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.SandboxNetwork
import java.security.MessageDigest

/** The resolvers every attached sandbox is given, and the only DNS servers it may address directly. */
object PublicResolvers {
    val addresses: List<String> = listOf("1.1.1.1", "9.9.9.9")
}

/**
 * The egress proxy behind domain rules: the ports it listens on at the sandbox gateway, and the uid it
 * runs as in the host's network namespace, which the host's own output rules confine.
 */
object EgressListener {
    const val HTTP_PORT = 49532
    const val TLS_PORT = 49533
    const val DNS_PORT = 49534

    /** Outside the ranges distributions give people and systemd gives services, so it names the proxy alone. */
    const val UID = 60531
}

/**
 * The complete iptables ruleset of one namespace, rendered as `iptables-restore` input. The whole set is
 * rebuilt on every change and swapped in atomically, so there is no incremental state to drift.
 *
 * ```
 * DOCKER-USER -> P        from and to the sandbox bridge
 *   P    inbound NEW dropped · spoofed sources dropped · the pool's own subnet, the floor and the host's
 *        addresses rejected · SMTP rejected · connection and packet rates metered per source ·
 *        DNS -> N · everything -> D
 *   N    only the given public resolvers
 *   D    source address -> that sandbox's chain; an address with no policy is rejected
 *   S…   public: return · allowlist: the given resolvers on port 53 (unless proxied) and the listed
 *        networks return, the rest rejected
 * INPUT -> P_IN     the host offers a sandbox nothing but the egress proxy, and that only to an address
 *                   whose policy names domains
 * OUTPUT -> P_O     the egress proxy's uid: the floor, then only DNS to the given resolvers and ports
 *                   80 and 443
 * PREROUTING (nat) -> P_R    a proxied address's DNS, port 80 and port 443, unless bound for a listed
 *                            network, are redirected to the egress proxy
 * ```
 */
class FirewallRules(
    val prefix: String,
    private val network: SandboxNetwork,
    private val refused: List<Cidr>,
    private val sandboxes: Map<String, NetworkPolicy.Attached>,
) {

    init {
        require(PREFIX.matches(prefix)) { "Invalid chain prefix $prefix" }
        require(sandboxes.keys.all { ADDRESS.matches(it) }) { "Sandbox addresses must be IPv4" }
    }

    fun render(): String = buildString {
        val main = prefix
        val input = "${prefix}_IN"
        val output = "${prefix}_O"
        val dns = "${prefix}_N"
        val dispatch = "${prefix}_D"
        val entries = sandboxes.entries.sortedBy { it.key }.map { (address, policy) -> Triple(address, sandboxChain(address), policy) }
        val proxied = entries.mapNotNull { (address, _, policy) -> (policy as? NetworkPolicy.Allowlist)?.takeIf { it.proxied }?.let { address to it } }
        val chains = listOf(main, input, output, dns, dispatch) + entries.map { it.second }
        val bridge = network.bridge
        val hash = prefix.removePrefix("RGL_").lowercase()

        appendLine("*filter")
        chains.forEach { appendLine(":$it - [0:0]") }
        chains.forEach { appendLine("-F $it") }

        // a sandbox answers nobody: the server reaches it through docker, never over the network.
        rule(main, "-o $bridge -m conntrack --ctstate NEW -j DROP")
        rule(main, "-i $bridge ! -s ${network.subnet} -j DROP")
        // the second lock on inter-container traffic, which the bridge already disables.
        rule(main, "-i $bridge -d ${network.subnet} -j REJECT")
        refused.distinct().forEach { rule(main, "-i $bridge -d $it -j REJECT") }
        rule(main, "-i $bridge -p tcp -m multiport --dports 25,465,587 -j REJECT")
        // metering comes before anything that returns: a rule after an early RETURN never counts.
        rule(main, "-i $bridge -m conntrack --ctstate NEW -m hashlimit --hashlimit-name rgl${hash}c --hashlimit-mode srcip --hashlimit-above 100/sec --hashlimit-burst 200 -j REJECT")
        rule(main, "-i $bridge -m hashlimit --hashlimit-name rgl${hash}p --hashlimit-mode srcip --hashlimit-above 10000/sec --hashlimit-burst 20000 -j DROP")
        rule(main, "-i $bridge -p udp -m udp --dport 53 -j $dns")
        rule(main, "-i $bridge -p tcp -m tcp --dport 53 -j $dns")
        rule(main, "-i $bridge -j $dispatch")

        PublicResolvers.addresses.forEach { rule(dns, "-d $it/32 -j RETURN") }
        rule(dns, "-j REJECT")

        entries.forEach { (address, chain, _) -> rule(dispatch, "-s $address/32 -g $chain") }
        rule(dispatch, "-j REJECT")

        entries.forEach { (_, chain, policy) ->
            when (policy) {
                NetworkPolicy.Public -> rule(chain, "-j RETURN")
                is NetworkPolicy.Allowlist -> {
                    // a sandbox is given resolvers of its own, so docker's resolver asks them from inside
                    // the sandbox's namespace and the query arrives here like any other packet. a name has
                    // to resolve under any allowlist, and only for an address that has a policy at all; a
                    // proxied one's queries never get here, the proxy answers them.
                    if (!policy.proxied) {
                        PublicResolvers.addresses.forEach { resolver ->
                            listOf("udp", "tcp").forEach { rule(chain, "-d $resolver/32 -p $it -m $it --dport 53 -j RETURN") }
                        }
                    }
                    policy.cidrs.forEach { rule(chain, "-d $it -j RETURN") }
                    rule(chain, "-j REJECT")
                }
            }
        }

        if (proxied.isNotEmpty()) {
            // what is redirected to the proxy arrives here instead of in forward, so it is metered here too.
            rule(input, "-i $bridge -m conntrack --ctstate NEW -m hashlimit --hashlimit-name rgl${hash}i --hashlimit-mode srcip --hashlimit-above 100/sec --hashlimit-burst 200 -j REJECT")
            rule(input, "-i $bridge -m hashlimit --hashlimit-name rgl${hash}q --hashlimit-mode srcip --hashlimit-above 10000/sec --hashlimit-burst 20000 -j DROP")
        }
        // the one accept in these chains: a host firewall that denies by default would otherwise drop what
        // the redirect delivers, and nothing else of the host is offered.
        proxied.forEach { (address, _) ->
            val proxy = "-i $bridge -s $address/32 -d ${network.gateway}/32"
            rule(input, "$proxy -p tcp -m multiport --dports ${EgressListener.HTTP_PORT},${EgressListener.TLS_PORT},${EgressListener.DNS_PORT} -j ACCEPT")
            rule(input, "$proxy -p udp -m udp --dport ${EgressListener.DNS_PORT} -j ACCEPT")
        }
        rule(input, "-i $bridge -j DROP")

        // the proxy works in the host's namespace, past the bridge, so the floor is put on its uid again here.
        rule(output, "-m owner ! --uid-owner ${EgressListener.UID} -j RETURN")
        rule(output, "-m conntrack --ctstate ESTABLISHED,RELATED -j RETURN")
        (refused + Cidr.parse(network.subnet)).distinct().forEach { rule(output, "-d $it -j REJECT") }
        PublicResolvers.addresses.forEach { resolver ->
            listOf("udp", "tcp").forEach { rule(output, "-d $resolver/32 -p $it -m $it --dport 53 -j RETURN") }
        }
        rule(output, "-p tcp -m multiport --dports 80,443 -j RETURN")
        rule(output, "-j REJECT")
        appendLine("COMMIT")

        val redirect = "${prefix}_R"
        val targets = proxied.map { (address, policy) -> Triple(address, proxyChain(address), policy) }
        appendLine("*nat")
        (listOf(redirect) + targets.map { it.second }).forEach { appendLine(":$it - [0:0]") }
        (listOf(redirect) + targets.map { it.second }).forEach { appendLine("-F $it") }
        rule(redirect, "! -i $bridge -j RETURN")
        targets.forEach { (address, chain, _) -> rule(redirect, "-s $address/32 -g $chain") }
        targets.forEach { (_, chain, policy) ->
            // every name goes through the proxy's resolver, even one inside a listed network.
            rule(chain, "-p udp -m udp --dport 53 -j REDIRECT --to-ports ${EgressListener.DNS_PORT}")
            rule(chain, "-p tcp -m tcp --dport 53 -j REDIRECT --to-ports ${EgressListener.DNS_PORT}")
            policy.cidrs.forEach { rule(chain, "-d $it -j RETURN") }
            rule(chain, "-p tcp -m tcp --dport 80 -j REDIRECT --to-ports ${EgressListener.HTTP_PORT}")
            rule(chain, "-p tcp -m tcp --dport 443 -j REDIRECT --to-ports ${EgressListener.TLS_PORT}")
        }
        appendLine("COMMIT")
    }

    private fun StringBuilder.rule(chain: String, spec: String) {
        appendLine("-A $chain $spec")
    }

    /** One chain per address rather than per sandbox: the dispatch rule and its chain always change together. */
    private fun sandboxChain(address: String): String = "${prefix}_S${digest(address).take(12).uppercase()}"

    /** The nat chain of a proxied address, named the same way. */
    private fun proxyChain(address: String): String = "${prefix}_T${digest(address).take(12).uppercase()}"

    companion object {
        private val PREFIX = Regex("RGL_[0-9A-F]{8}")
        private val ADDRESS = Regex("""(\d{1,3}\.){3}\d{1,3}""")

        /** The chain prefix of a namespace; two servers on one host never touch each other's chains. */
        fun prefixFor(namespace: String): String = "RGL_" + digest(namespace).take(8).uppercase()

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
    }
}

/** Keyed by sandbox so a release finds its address; rendered by address. */
internal fun Map<SandboxId, Pair<String, NetworkPolicy.Attached>>.byAddress(): Map<String, NetworkPolicy.Attached> =
    values.associate { (address, policy) -> address to policy }
