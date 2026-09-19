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
 *   S…   public: return · allowlist: the given resolvers on port 53 and the listed networks return,
 *        the rest rejected
 * INPUT -> P_IN     the host offers a sandbox nothing
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
        val dns = "${prefix}_N"
        val dispatch = "${prefix}_D"
        val entries = sandboxes.entries.sortedBy { it.key }.map { (address, policy) -> Triple(address, sandboxChain(address), policy) }
        val chains = listOf(main, input, dns, dispatch) + entries.map { it.second }
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
                    // to resolve under any allowlist, and only for an address that has a policy at all.
                    PublicResolvers.addresses.forEach { resolver ->
                        listOf("udp", "tcp").forEach { rule(chain, "-d $resolver/32 -p $it -m $it --dport 53 -j RETURN") }
                    }
                    policy.cidrs.forEach { rule(chain, "-d $it -j RETURN") }
                    rule(chain, "-j REJECT")
                }
            }
        }

        rule(input, "-i $bridge -j DROP")
        appendLine("COMMIT")
    }

    private fun StringBuilder.rule(chain: String, spec: String) {
        appendLine("-A $chain $spec")
    }

    /** One chain per address rather than per sandbox: the dispatch rule and its chain always change together. */
    private fun sandboxChain(address: String): String = "${prefix}_S${digest(address).take(12).uppercase()}"

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
