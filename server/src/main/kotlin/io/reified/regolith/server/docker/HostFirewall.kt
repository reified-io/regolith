package io.reified.regolith.server.docker

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.egress.EgressSandbox
import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.DomainRule
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.PlatformFloor
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.NetworkEnforcer
import io.reified.regolith.server.ports.SandboxNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * [NetworkEnforcer] as iptables rules in the Docker host's own namespace, installed by the firewall
 * helper. The server keeps the policies of live sessions in memory, renders the whole ruleset on every
 * change and remembers the fingerprint the helper printed; the guard later compares a fresh fingerprint
 * with it rule for rule.
 *
 * Domain rules add the egress proxy, which this class starts with the floor and keeps running. Every
 * change sends the proxy its whole table before the rules move, and only once it is in force: an address
 * redirected before the proxy knows it is refused everything, never let through.
 */
class HostFirewall(
    private val docker: DockerCli,
    private val helpers: Helpers,
    private val namespace: String,
    private val extraRefused: List<Cidr>,
) : NetworkEnforcer {

    private val mutex = Mutex()
    private val prefix = FirewallRules.prefixFor(namespace)
    private val policies = LinkedHashMap<SandboxId, Pair<String, NetworkPolicy.Attached>>()
    private var network: SandboxNetwork? = null
    private var refused: List<Cidr> = PlatformFloor.refused
    private var probing: Pair<String, NetworkPolicy.Attached>? = null
    private var fingerprint: String? = null
    private var egress: EgressProcess? = null
    private val egressName = "$namespace-egress"

    override suspend fun install(network: SandboxNetwork) = mutex.withLock {
        this.network = network
        val hostAddresses = helper(helpers.firewall("addresses")).lines().filter { it.isNotBlank() }.map(Cidr::parse)
        refused = (PlatformFloor.refused + hostAddresses + extraRefused).distinct()
        startEgress(network)
        applyLocked()
        val lan = prove(network)
        log.info { "Network floor installed and proven: prefix=[$prefix] bridge=[${network.bridge}] lanControls=[${lan.joinToString()}]" }
    }

    override suspend fun verifyAndRepair(): Boolean = mutex.withLock {
        val network = this.network ?: return@withLock false
        val current = try {
            helper(helpers.firewall("check", prefix, network.bridge))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Reading the firewall failed" }
            null
        }
        val proxied = egress?.alive == true
        if (current != null && current == fingerprint && proxied) return@withLock true
        if (proxied) log.warn { "Firewall drifted from what was installed; reinstalling: prefix=[$prefix]" } else log.warn { "The egress proxy is gone; starting it again" }

        try {
            if (!proxied) startEgress(network)
            applyLocked()
            helper(helpers.firewall("check", prefix, network.bridge)) == fingerprint
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Reinstalling the firewall failed" }
            false
        }
    }

    override suspend fun apply(sandbox: SandboxId, address: String, policy: NetworkPolicy.Attached) = mutex.withLock {
        val previous = policies.put(sandbox, address to policy)

        try {
            applyLocked()
        } catch (e: Exception) {
            if (previous == null) policies.remove(sandbox) else policies[sandbox] = previous
            throw e
        }
    }

    override suspend fun release(sandbox: SandboxId) = mutex.withLock {
        if (policies.remove(sandbox) != null) applyLocked()
    }

    private suspend fun applyLocked() {
        val network = checkNotNull(network) { "The firewall is not installed" }
        val addresses = policies.byAddress() + listOfNotNull(probing)
        val entries = policies.map { (id, entry) -> Triple(id.value, entry.first, entry.second) } +
            listOfNotNull(probing?.let { (address, policy) -> Triple("probe", address, policy) })
        val proxied = entries.mapNotNull { (sandbox, address, policy) ->
            (policy as? NetworkPolicy.Allowlist)?.takeIf { it.proxied }?.let { address to EgressSandbox(sandbox, it.domains.map(DomainRule::value)) }
        }.toMap()
        // a proxy that went is needed again only by an address it would serve; nothing else waits for it.
        if (egress?.alive != true && proxied.isNotEmpty()) startEgress(network)
        egress?.takeIf { it.alive }?.push((refused + Cidr.parse(network.subnet)).distinct().map { it.value }, proxied)
        val rules = FirewallRules(prefix, network, refused, addresses).render()
        fingerprint = helper(helpers.firewall("apply", prefix, network.bridge, EgressListener.UID.toString()), input = rules)
    }

    /** Starts the egress proxy on [network]'s gateway, in place of any proxy this or a previous run left. */
    private suspend fun startEgress(network: SandboxNetwork) {
        egress?.destroy()
        egress = null
        docker.run(listOf("rm", "--force", egressName))
        val process = docker.start(helpers.egress(egressName, network.gateway), stdin = true)
        val started = EgressProcess(process)

        try {
            started.awaitReady()
        } catch (e: Exception) {
            started.destroy()
            withContext(NonCancellable) { docker.run(listOf("rm", "--force", egressName)) }
            throw e
        }
        egress = started
        log.info { "Egress proxy listening: gateway=[${network.gateway}] ports=[${EgressListener.HTTP_PORT},${EgressListener.TLS_PORT},${EgressListener.DNS_PORT}]" }
    }

    /**
     * Asks a throwaway container what it can reach. A probe with no control proves nothing — an address
     * where nothing listens refuses a connection exactly like a blocked one — so a listener on the host's
     * side of the bridge must first be seen answering, and only then does silence from the sandbox side
     * count. Egress is the control for the unpoliced-address check: only if a policed probe reaches the
     * internet does an unpoliced one failing to mean anything.
     */
    private suspend fun prove(network: SandboxNetwork): List<String> {
        val listener = "$namespace-firewall-control"
        val probe = "$namespace-firewall-probe"
        removeContainers(listener, probe)

        try {
            docker.run(helpers.controlListener(listener, network.gateway, CONTROL_PORT)).requireOk("Starting the control listener")
            awaitControl(listener, network.gateway)
            docker.run(helpers.probe(probe, network.name)).requireOk("Starting the network probe")
            val address = docker.run(listOf("inspect", "--format", "{{(index .NetworkSettings.Networks \"${network.name}\").IPAddress}}", probe))
                .requireOk("Inspecting the network probe").text.trim()
            check(address.isNotEmpty()) { "The network probe has no address" }

            val lan = lanControls(listener)
            if (lan.isEmpty()) {
                log.info { "No device on the host's network answered from the host; the floor is proven against the host only" }
            }

            probing = address to NetworkPolicy.Public
            applyLocked()
            val controls = listOf("${network.gateway}:$CONTROL_PORT") + lan
            val policed = probeOutput(probe, PROBE_SCRIPT, controls)
            val leaks = policed.filter { it.startsWith("leak ") }
            check(leaks.isEmpty()) { "The network floor is not in effect: ${leaks.joinToString()}" }
            val egress = "egress" in policed
            if (!egress) {
                log.warn { "Sandboxes cannot reach the public internet; check the host or provider firewall" }
            }

            proveDomains(probe, address, egress, controls)

            probing = null
            applyLocked()
            if (egress) {
                val unpoliced = probeOutput(probe, EGRESS_SCRIPT)
                check("egress" !in unpoliced) { "An address with no policy reached the internet" }
            }
            return lan
        } finally {
            withContext(NonCancellable) {
                removeContainers(listener, probe)
                if (probing != null) dropProbeLocked()
            }
        }
    }

    /**
     * Takes the probe's policy out after a proof that ended early. What stays behind if this fails
     * names an address nobody holds, and the next change of a policy renders the rules without it.
     */
    private suspend fun dropProbeLocked() {
        probing = null

        try {
            applyLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Could not take the probe's policy out of the rules" }
        }
    }

    /**
     * Domain rules, proven from the same probe given a policy of one name. The proxy itself must answer a
     * request for another name, and refuse it; another name must not resolve; a hello for another name
     * must get nowhere, whatever address it was sent to; a port the proxy does not take must stay shut.
     * The name allowed must get through, which is a control only when the probe could reach the internet
     * at all. And the proxy's uid, in the host's namespace, must reach none of the controls the host
     * itself reaches: that is the floor put back on the proxy.
     */
    private suspend fun proveDomains(probe: String, address: String, egress: Boolean, controls: List<String>) {
        probing = address to NetworkPolicy.Allowlist(emptyList(), listOf(DomainRule.parse(PROOF_DOMAIN)))
        applyLocked()
        val proxied = probeOutput(probe, DOMAIN_SCRIPT, listOf(PROOF_DOMAIN, if (egress) "egress" else "none"))
        val leaks = proxied.filter { it.startsWith("leak ") }
        check(leaks.isEmpty()) { "Domain rules are not in effect: ${leaks.joinToString()}" }
        check("refused" in proxied) { "The egress proxy did not answer a sandbox on the gateway" }
        if (egress && "allowed" !in proxied) {
            log.warn { "A domain rule let nothing through the egress proxy; check that the host reaches ${PublicResolvers.addresses.joinToString()} on port 53" }
        }
        val escaped = controls.filter { target -> docker.run(helpers.egressReach(target.substringBefore(':'), target.substringAfter(':').toInt())).ok }
        check(escaped.isEmpty()) { "The egress proxy's uid reaches refused space: ${escaped.joinToString()}" }
    }

    /**
     * Real devices on the host's own network that answer the host: its default gateways, on ports a router
     * usually serves. Each one is a control in private space — reachable from the host, so the sandbox
     * failing to reach it is evidence, not silence. Connections only, never a login.
     */
    private suspend fun lanControls(listener: String): List<String> {
        val gateways = helper(helpers.firewall("gateways")).lines().filter { it.isNotBlank() }
            .filter { gateway -> runCatching { PlatformFloor.refuses(Cidr.parse(gateway)) }.getOrDefault(false) }

        return gateways.mapNotNull { gateway ->
            LAN_PORTS.firstOrNull { port ->
                docker.run(listOf("exec", listener, "nc", "-z", "-w", "2", gateway, port.toString())).ok
            }?.let { "$gateway:$it" }
        }
    }

    private suspend fun awaitControl(listener: String, gateway: String) {
        repeat(CONTROL_ATTEMPTS) {
            if (docker.run(listOf("exec", listener, "nc", "-z", "-w", "1", gateway, CONTROL_PORT.toString())).ok) return
            delay(100.milliseconds)
        }
        error("Cannot prove the network floor: the control listener on $gateway:$CONTROL_PORT never answered")
    }

    /** Runs one of the fixed scripts in the probe; [targets] reach it as positional parameters, never as text. */
    private suspend fun probeOutput(probe: String, script: String, targets: List<String> = emptyList()): List<String> =
        docker.run(listOf("exec", probe, "bash", "-c", script, "probe") + targets)
            .requireOk("Running the network probe").text.lines().filter { it.isNotBlank() }

    private suspend fun removeContainers(vararg names: String) {
        // these run with --rm, and a forced removal takes the place of docker's own; --volumes keeps
        // anonymous volumes from outliving it the way --rm would have.
        docker.run(listOf("rm", "--force", "--volumes") + names)
    }

    private suspend fun helper(args: List<String>, input: String? = null): String =
        docker.run(args, input = input?.toByteArray()).requireOk("Firewall helper ${args.lastOrNull()}").text.trim()

    private companion object {
        val log = KotlinLogging.logger {}
        const val CONTROL_PORT = 49531
        const val CONTROL_ATTEMPTS = 50
        val LAN_PORTS = listOf(443, 80, 53)

        // the control listener first, then the devices that answered the host, each as address:port.
        val PROBE_SCRIPT = """
            reach() { timeout 3 bash -c 'exec 3<>"/dev/tcp/${'$'}1/${'$'}2"' reach "${'$'}1" "${'$'}2" 2>/dev/null; }
            control="${'$'}1"; shift
            reach "${'$'}{control%:*}" "${'$'}{control#*:}" && echo "leak host ${'$'}control"
            for target in "${'$'}@"; do
              reach "${'$'}{target%:*}" "${'$'}{target#*:}" && echo "leak lan ${'$'}target"
            done
            for target in 169.254.169.254:80 10.255.255.254:80 172.31.255.254:80 192.168.255.254:80 100.100.100.100:80; do
              reach "${'$'}{target%:*}" "${'$'}{target#*:}" && echo "leak ${'$'}target"
            done
            if reach 1.1.1.1 443; then
              echo egress
              reach 8.8.4.4 53 && echo "leak resolver 8.8.4.4:53"
            fi
            exit 0
        """.trimIndent()

        const val PROOF_DOMAIN = "one.one.one.one"

        // under a policy of one name: the proxy refuses another name on port 80, which proves it answers;
        // another name does not resolve; a hello for another name sent to an address the allowed name has
        // gets nowhere; a port the proxy does not take is closed. the allowed name is tried last.
        val DOMAIN_SCRIPT = """
            reach() { timeout 3 bash -c 'exec 3<>"/dev/tcp/${'$'}1/${'$'}2"' reach "${'$'}1" "${'$'}2" 2>/dev/null; }
            code="${'$'}(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -H 'Host: example.com' http://1.1.1.1/ || true)"
            case "${'$'}code" in 403) echo refused ;; 000) ;; *) echo "leak host ${'$'}code" ;; esac
            getent hosts example.com >/dev/null 2>&1 && echo "leak dns example.com"
            curl -sk -o /dev/null --max-time 5 --resolve example.com:443:1.1.1.1 https://example.com/ && echo "leak sni example.com"
            reach 1.1.1.1 853 && echo "leak port 1.1.1.1:853"
            if [[ "${'$'}2" == egress ]]; then
              curl -s -o /dev/null --max-time 10 "https://${'$'}1/" && echo allowed
            fi
            exit 0
        """.trimIndent()

        // a resolver counts as egress too: an allowance for names is the likeliest way for an address
        // with no policy to be let through by mistake.
        const val EGRESS_SCRIPT = """for port in 443 53; do timeout 5 bash -c "exec 3<>/dev/tcp/1.1.1.1/${'$'}port" 2>/dev/null && echo egress; done; exit 0"""
    }
}
