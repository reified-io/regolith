package io.reified.regolith.server.docker

import io.reified.regolith.server.app.CpuGuard
import io.reified.regolith.server.app.Health
import io.reified.regolith.server.app.Sessions
import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.DomainRule
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.ImageCatalog
import io.reified.regolith.server.domain.ImagePolicy
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.domain.SnapshotId
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.ports.ExecSpec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Homes and the host firewall on the real machine running the build. Opt in with
 * `REGOLITH_HOST_TESTS=1`: it builds the server and sandbox images, installs firewall chains for its
 * own namespace — they match only that namespace's bridge — runs the egress proxy, attaches loop
 * devices, and removes all of it afterward. It needs internet access for the egress checks.
 *
 * The helpers and the egress proxy run from a whole server image: `REGOLITH_HOST_TEST_IMAGE` when it
 * names one already on this machine, otherwise one built here.
 */
class HostIntegrationTest {
    private val enabled = System.getenv("REGOLITH_HOST_TESTS") == "1"
    private val namespace = "regolith-host-it"
    private val docker = DockerCli("docker")
    private val name = SandboxId.random()
    private val twin = SandboxId.random()
    private val serverImage = System.getenv("REGOLITH_HOST_TEST_IMAGE")?.ifBlank { null } ?: "regolith-server:it"

    @Test
    fun `homes are bounded and persistent, and the network floor holds under every policy`() {
        assumeTrue(enabled, "set REGOLITH_HOST_TESTS=1 to change this machine's firewall and loop devices")
        val repository = Path.of("").toAbsolutePath().parent
        val stateDir = Files.createTempDirectory("regolith-host-it")
        runBlocking {
            if (serverImage == "regolith-server:it") {
                build("-t", serverImage, "-f", repository.resolve("images/server/Dockerfile").toString(), repository.toString())
            }
            build("--target", "base", "-t", "regolith-sandbox:it", repository.resolve("images/sandbox").toString())

            val helpers = Helpers(Helpers.resolveImage(docker, serverImage), namespace)
            val spec = ContainerSpec(namespace, "/bin/bash", homeReadBps = "100mb", homeWriteBps = "50mb")
            val runtime = DockerRuntime(docker, spec)
            val homes = HomeDisks(docker, helpers, namespace, stateDir, reserveMb = 256)
            val firewall = HostFirewall(docker, helpers, namespace, emptyList())
            val image = "regolith-sandbox:it"
            val sessions = Sessions(runtime, homes, firewall, Health(), Clock.System, maxSessions = 2, images = ImageCatalog(image, listOf(image)))
            val now = Clock.System.now()
            val sandbox = Sandbox(name, alias = null, site = null, ImagePolicy.Default, Resources(1.0, 512, 256), NetworkPolicy.Public, Lifecycle(5.minutes, 1.days, 1.days), emptyMap(), emptyMap(), now, now)
            var network: io.reified.regolith.server.ports.SandboxNetwork? = null

            suspend fun sh(script: String, sandbox: SandboxId = name): String {
                val process = runtime.exec(sandbox, ExecSpec(ExecId.random(), ExecCommand.Shell("{ $script\n} 2>&1"), "/home/sandbox", emptyMap(), stdin = false))
                withTimeout(120.seconds) { process.awaitExit() }

                return process.stdout.readAllBytes().decodeToString().trim().also { process.detach() }
            }

            suspend fun reach(host: String, port: Int): Boolean =
                sh("timeout 4 bash -c 'exec 3<>/dev/tcp/$host/$port' && echo open || echo closed").endsWith("open")

            try {
                network = runtime.initialize()
                homes.recover()
                // install proves the floor itself, against a live control listener.
                firewall.install(checkNotNull(network))
                assertTrue(firewall.verifyAndRepair())

                sessions.withLease(sandbox) {
                    assertEquals("1000", sh("id -u"))
                    val size = sh("df --block-size=1M --output=size /home/sandbox | tail -1").trim().toInt()
                    assertTrue(size in 200..256, "home is $size MB")
                    val fill = sh("dd if=/dev/zero of=/home/sandbox/fill bs=1M count=400; echo exit=\$?")
                    assertTrue("No space left on device" in fill && fill.endsWith("exit=1"), fill)
                    sh("rm /home/sandbox/fill; echo kept > /home/sandbox/kept.txt")

                    assertTrue(reach("1.1.1.1", 443), "public egress")
                    assertTrue(!reach("169.254.169.254", 80))
                    assertTrue(!reach(checkNotNull(network).gateway, 22), "the host")
                    assertTrue(!reach("8.8.4.4", 53), "a resolver that was not given")
                    // a live device on this machine's own network: it must answer the host, or its silence
                    // from the sandbox would prove nothing.
                    val (lanHost, lanPort) = lanTarget()
                    assertTrue(!reach(lanHost, lanPort), "the local network device $lanHost:$lanPort")
                    assertTrue("no-dns" !in sh("getent hosts one.one.one.one || echo no-dns"), "public resolves names")
                }

                sessions.stop(name, StopReason.STOPPED)
                // the volume name is read back as the id it was made from, and nothing else is left over.
                assertEquals(listOf(name), homes.list())
                assertEquals(emptyList(), homes.unrecognized())
                sessions.withLease(sandbox) {
                    assertEquals("kept", sh("cat /home/sandbox/kept.txt"), "the home survives its session")
                }

                val allowlist = sandbox.copy(network = NetworkPolicy.Allowlist(listOf(Cidr.parse("1.1.1.1"))))
                sessions.applyNetwork(allowlist)
                // an allowlist that names everything still cannot reopen private space: the floor comes first.
                val everything = sandbox.copy(network = NetworkPolicy.Allowlist(listOf(Cidr.parse("0.0.0.0/0"))))
                sessions.applyNetwork(everything)
                sessions.withLease(everything) {
                    val (lanHost, lanPort) = lanTarget()
                    assertTrue(reach("1.1.1.1", 443), "the internet under 0.0.0.0/0")
                    assertTrue(!reach(lanHost, lanPort), "the local network under 0.0.0.0/0")
                    assertTrue(!reach(checkNotNull(network).gateway, 22), "the host under 0.0.0.0/0")
                }

                sessions.applyNetwork(allowlist)
                sessions.withLease(allowlist) {
                    assertTrue(reach("1.1.1.1", 443), "an allowed address")
                    assertTrue(!reach("9.9.9.9", 443), "an address outside the allowlist")
                }

                // names resolve under any allowlist, one that names no resolver included, and the
                // resolver's address is open for nothing else.
                val narrow = sandbox.copy(network = NetworkPolicy.Allowlist(listOf(Cidr.parse("140.82.112.0/20"))))
                sessions.applyNetwork(narrow)
                sessions.withLease(narrow) {
                    assertTrue("no-dns" !in sh("getent hosts one.one.one.one || echo no-dns"), "names under an allowlist that names no resolver")
                    assertTrue(!reach("1.1.1.1", 443), "the resolver's address, for anything but names")
                }

                // domain rules: names and web traffic go through the egress proxy, which lets through only
                // what a rule covers, and the rest of the host stays as closed as it was.
                val domains = sandbox.copy(network = NetworkPolicy.Allowlist(emptyList(), listOf(DomainRule.parse("one.one.one.one"), DomainRule.parse("*.github.com"))))
                sessions.applyNetwork(domains)
                suspend fun provesDomains() {
                    assertTrue("no-dns" !in sh("getent hosts one.one.one.one || echo no-dns"), "an allowed name resolves")
                    assertTrue(sh("getent hosts example.com || echo no-dns").endsWith("no-dns"), "a name no rule covers does not")
                    val code = sh("curl -s -o /dev/null -w '%{http_code}' --max-time 10 https://one.one.one.one/")
                    assertTrue(Regex("[1-5][0-9][0-9]").matches(code), "an allowed name answers over https: $code")
                    val fronted = sh("curl -sk -o /dev/null --max-time 5 --resolve example.com:443:1.1.1.1 https://example.com/ && echo leak || echo refused")
                    assertTrue(fronted.endsWith("refused"), "another name sent to an allowed address: $fronted")
                    val plain = sh("curl -s --max-time 5 -H 'Host: example.com' http://1.1.1.1/")
                    assertTrue("is not allowed by this sandbox's network policy" in plain, "another host over http: $plain")
                    assertTrue(!reach("1.1.1.1", 853), "a port the proxy does not take")
                    assertTrue(!reach(checkNotNull(network).gateway, 22), "the host, beside the proxy")
                }
                sessions.withLease(domains) { provesDomains() }

                // a proxy that went is started again by the guard's check, with the table of every live session.
                docker.run(listOf("rm", "--force", "$namespace-egress")).requireOk("taking the egress proxy away")
                // the client attached to it notices a moment after the container is gone.
                delay(2.seconds)
                assertTrue(firewall.verifyAndRepair(), "a proxy that went is started again")
                sessions.withLease(domains) { provesDomains() }

                val offline = sandbox.copy(network = NetworkPolicy.None)
                sessions.applyNetwork(offline)
                sessions.withLease(offline) {
                    assertEquals("lo", sh("ls /sys/class/net"))
                    assertTrue(sh("getent hosts one.one.one.one || echo no-dns").endsWith("no-dns"), "none resolves nothing")
                }

                sessions.applyNetwork(sandbox)
                sessions.withLease(sandbox) { assertTrue(reach("1.1.1.1", 443), "attached again") }

                // a session that started under none is attached as well, names and all.
                sessions.stop(name, StopReason.STOPPED)
                sessions.withLease(offline) { assertEquals("lo", sh("ls /sys/class/net"), "a session started under none") }
                sessions.applyNetwork(sandbox)
                sessions.withLease(sandbox) {
                    assertTrue(reach("1.1.1.1", 443), "a session started under none, attached")
                    assertTrue("no-dns" !in sh("getent hosts one.one.one.one || echo no-dns"), "and it resolves names")
                    assertEquals("0", sh("grep -c '^search' /etc/resolv.conf || true"), "with no search domain of the host's")
                }

                // a detached busy loop outlives the command that started it; only the cpu guard ends it.
                sessions.withLease(sandbox) {
                    sh("nohup sh -c 'while :; do :; done' >/dev/null 2>&1 &")
                }
                val guard = CpuGuard(sessions, runtime, Clock.System, limit = 3.seconds)
                withTimeout(60.seconds) {
                    while (sessions.get(name) != null) {
                        guard.tick()
                        delay(1.seconds)
                    }
                }
                assertEquals(StopReason.CPU_LIMIT, sessions.lastEnd(name)?.reason)

                // drift: somebody deletes the rule that shields the host; the guard's check puts it back.
                val prefix = FirewallRules.prefixFor(namespace)
                val bridge = checkNotNull(network).bridge
                val iptables = docker.run(helpers.firewall("backend")).requireOk("finding the backend").text.trim()
                docker.run(tools(iptables, "-D", "${prefix}_IN", "-i", bridge, "-j", "DROP")).requireOk("deleting a rule")
                assertTrue(firewall.verifyAndRepair(), "drift is repaired")
                assertTrue(docker.run(tools(iptables, "-C", "${prefix}_IN", "-i", bridge, "-j", "DROP")).ok, "the rule is back")

                // every rule still there and nothing pointing at them: only this namespace's own hook is
                // taken out, since whatever else this machine keeps in DOCKER-USER is not the test's to flush.
                docker.run(tools(iptables, "-D", "DOCKER-USER", "-j", prefix)).requireOk("unhooking the rules")
                assertTrue(firewall.verifyAndRepair(), "an unhooked chain is repaired")
                assertEquals("-A DOCKER-USER -j $prefix", firstRule(iptables, "DOCKER-USER"), "the hook is back, and first")

                // anything that could accept ahead of the hook is drift, though every rule is where it was.
                docker.run(tools(iptables, "-I", "INPUT", "1", "-i", bridge, "-j", "ACCEPT")).requireOk("an accept ahead of the hook")
                assertTrue(firewall.verifyAndRepair(), "a displaced hook is repaired")
                assertEquals("-A INPUT -j ${prefix}_IN", firstRule(iptables, "INPUT"), "the hook is first again")
                docker.run(tools(iptables, "-D", "INPUT", "-i", bridge, "-j", "ACCEPT")).requireOk("taking the accept out")
                sessions.withLease(sandbox) {
                    assertTrue(!reach(checkNotNull(network).gateway, 22), "the host, after all of that")
                }

                // a home grows between sessions with every file in it, and a size below it never opens it again.
                // it grows under an attachment a run that stopped short left behind, which must follow the image.
                sessions.stop(name, StopReason.STOPPED)
                docker.run(helpers.homeDisk("$namespace-$name-disk", "prepare", "256", "256")).requireOk("a stale attachment")
                val grown = sandbox.copy(resources = sandbox.resources.copy(homeMb = 512))
                sessions.withLease(grown) {
                    val size = sh("df --block-size=1M --output=size /home/sandbox | tail -1").trim().toInt()
                    assertTrue(size in 450..512, "the grown home is $size MB")
                    assertEquals("kept", sh("cat /home/sandbox/kept.txt"), "the files survive the growth")
                    val fill = sh("dd if=/dev/zero of=/home/sandbox/fill bs=1M count=600; echo exit=\$?")
                    assertTrue("No space left on device" in fill && fill.endsWith("exit=1"), "still bounded: $fill")
                }
                sessions.stop(name, StopReason.STOPPED)
                assertEquals(512, homes.sizeMb(name))
                assertTrue(runCatching { sessions.withLease(sandbox) {} }.isFailure, "a home is never shrunk to open it")

                // a snapshot takes what the home uses, not its size, and restoring it undoes what came after.
                val snapshot = SnapshotId.random()
                val bytes = sessions.stopped(name, StopReason.STOPPED) { homes.snapshot(name, snapshot) }
                assertTrue(bytes in 1 until 256L * 1024 * 1024, "a snapshot of a nearly empty 512 MB home takes $bytes bytes")
                sessions.withLease(grown) { sh("rm /home/sandbox/kept.txt; echo later > /home/sandbox/later.txt") }
                assertTrue(runCatching { homes.snapshot(name, SnapshotId.random()) }.isFailure, "a home in use is never copied")
                sessions.stopped(name, StopReason.STOPPED) { homes.restore(name, snapshot) }
                sessions.withLease(grown) {
                    assertEquals("kept", sh("cat /home/sandbox/kept.txt"), "the restored home has what it had")
                    assertTrue(sh("ls /home/sandbox").lines().none { it == "later.txt" }, "and nothing written since")
                    val fill = sh("dd if=/dev/zero of=/home/sandbox/fill bs=1M count=600; echo exit=\$?")
                    assertTrue("No space left on device" in fill && fill.endsWith("exit=1"), "a restored home is still bounded: $fill")
                    sh("rm -f /home/sandbox/fill")
                }

                // another sandbox's home made from it, larger than the home it came from.
                sessions.stop(name, StopReason.STOPPED)
                homes.clone(name, snapshot, twin)
                val copy = sandbox.copy(id = twin, resources = sandbox.resources.copy(homeMb = 768))
                sessions.withLease(copy) {
                    assertEquals("kept", sh("cat /home/sandbox/kept.txt", twin), "the clone has the snapshot's files")
                    val size = sh("df --block-size=1M --output=size /home/sandbox | tail -1", twin).trim().toInt()
                    assertTrue(size in 700..768, "the clone's home grew to $size MB")
                }
                sessions.stop(twin, StopReason.STOPPED)
                homes.deleteSnapshot(name, snapshot)
                assertTrue(runCatching { homes.restore(name, snapshot) }.isFailure, "a deleted snapshot is gone")
            } finally {
                withContext(NonCancellable) {
                    // the one rule the test writes outside its own chains; gone already unless it failed midway.
                    network?.let { docker.run(tools("sh", "-c", "for t in iptables-nft iptables-legacy; do \$t -D INPUT -i \"\$1\" -j ACCEPT; done 2>/dev/null; true", "sh", it.bridge)) }
                    sessions.stopAll(StopReason.STOPPED)
                    runCatching { homes.destroy(name) }
                    runCatching { homes.destroy(twin) }
                    docker.run(helpers.firewall("remove", FirewallRules.prefixFor(namespace)))
                    // the proxy would end with this process anyway, when its stdin closes.
                    docker.run(listOf("rm", "--force", "$namespace-egress"))
                    docker.run(listOf("network", "rm", spec.network))
                    stateDir.toFile().deleteRecursively()
                }
            }

            val leftovers = docker.run(helpers.firewall("check", FirewallRules.prefixFor(namespace), "lo")).text.lines()
                .filter { it.startsWith("-") }
            assertEquals(emptyList(), leftovers, "firewall chains left behind")
            val volumes = docker.run(listOf("volume", "ls", "--quiet", "--filter", "label=${ContainerSpec.LABEL_PREFIX}.namespace=$namespace")).text.trim()
            assertEquals("", volumes, "volumes left behind")
        }
    }

    /**
     * A device on this machine's network that answers from the host: `REGOLITH_HOST_TEST_LAN` as
     * `host:port`, or the default gateway on a port routers serve. Fails rather than skipping, so the
     * private-network checks can never pass without a control.
     */
    private suspend fun lanTarget(): Pair<String, Int> {
        System.getenv("REGOLITH_HOST_TEST_LAN")?.takeIf { it.isNotBlank() }?.let { configured ->
            return configured.substringBeforeLast(':') to configured.substringAfterLast(':').toInt()
        }
        val gateway = docker.run(tools("sh", "-c", "ip -4 route show default | awk '{ print \$3; exit }'")).requireOk("finding the gateway").text.trim()
        val port = listOf(443, 80, 53).firstOrNull { docker.run(tools("nc", "-z", "-w", "2", gateway, it.toString())).ok }
        checkNotNull(port) { "No local network device answered from the host at $gateway; set REGOLITH_HOST_TEST_LAN=host:port" }

        return gateway to port
    }

    private suspend fun firstRule(iptables: String, chain: String): String? =
        docker.run(tools(iptables, "-S", chain)).requireOk("reading $chain").text.lines().firstOrNull { it.startsWith("-A ") }

    private fun tools(vararg command: String): List<String> =
        listOf("run", "--rm", "--network=host", "--cap-drop=ALL", "--cap-add=NET_ADMIN", "--cap-add=NET_RAW", "--entrypoint", command.first(), serverImage) + command.drop(1)

    private suspend fun build(vararg args: String) {
        val result = docker.run(listOf("build", "--quiet") + args, timeout = 30.minutes)
        check(result.ok) { "docker build failed: ${result.stderr}" }
    }
}
