package io.reified.regolith.server.docker

import io.reified.regolith.server.app.CpuGuard
import io.reified.regolith.server.app.Health
import io.reified.regolith.server.app.Sessions
import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.ImageCatalog
import io.reified.regolith.server.domain.ImagePolicy
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
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
 * `REGOLITH_HOST_TESTS=1`: it builds the helper and sandbox images, installs firewall chains for its
 * own namespace — they match only that namespace's bridge — attaches a loop device, and removes all of
 * it afterwards. It needs internet access for the egress checks.
 */
class HostIntegrationTest {
    private val enabled = System.getenv("REGOLITH_HOST_TESTS") == "1"
    private val namespace = "regolith-host-it"
    private val docker = DockerCli("docker")
    private val name = SandboxId.random()

    @Test
    fun `homes are bounded and persistent, and the network floor holds under every policy`() {
        assumeTrue(enabled, "set REGOLITH_HOST_TESTS=1 to change this machine's firewall and loop devices")
        val repository = Path.of("").toAbsolutePath().parent
        val stateDir = Files.createTempDirectory("regolith-host-it")
        runBlocking {
            build("--target", "tools", "-t", "regolith-tools:it", "-f", repository.resolve("images/server/Dockerfile").toString(), repository.toString())
            build("--target", "base", "-t", "regolith-sandbox:it", repository.resolve("images/sandbox").toString())

            val helpers = Helpers(Helpers.resolveImage(docker, "regolith-tools:it"), namespace)
            val spec = ContainerSpec(namespace, "/bin/bash", homeReadBps = "100mb", homeWriteBps = "50mb")
            val runtime = DockerRuntime(docker, spec)
            val homes = HomeDisks(docker, helpers, namespace, stateDir, reserveMb = 256)
            val firewall = HostFirewall(docker, helpers, namespace, emptyList())
            val image = "regolith-sandbox:it"
            val sessions = Sessions(runtime, homes, firewall, Health(), Clock.System, maxSessions = 2, images = ImageCatalog(image, listOf(image)))
            val now = Clock.System.now()
            val sandbox = Sandbox(name, alias = null, site = null, ImagePolicy.Default, Resources(1.0, 512, 256), NetworkPolicy.Public, Lifecycle(5.minutes, 1.days, 1.days), emptyMap(), emptyMap(), now, now)
            var network: io.reified.regolith.server.ports.SandboxNetwork? = null

            suspend fun sh(script: String): String {
                val process = runtime.exec(name, ExecSpec(ExecId.random(), ExecCommand.Shell("{ $script\n} 2>&1"), "/home/sandbox", emptyMap(), stdin = false))
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

                val offline = sandbox.copy(network = NetworkPolicy.None)
                sessions.applyNetwork(offline)
                sessions.withLease(offline) {
                    assertEquals("lo", sh("ls /sys/class/net"))
                    assertTrue(sh("getent hosts one.one.one.one || echo no-dns").endsWith("no-dns"), "none resolves nothing")
                }

                sessions.applyNetwork(sandbox)
                sessions.withLease(sandbox) { assertTrue(reach("1.1.1.1", 443), "attached again") }

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
                docker.run(tools("iptables-nft", "-D", "${prefix}_IN", "-i", bridge, "-j", "DROP")).requireOk("deleting a rule")
                assertTrue(firewall.verifyAndRepair(), "drift is repaired")
                assertTrue(docker.run(tools("iptables-nft", "-C", "${prefix}_IN", "-i", bridge, "-j", "DROP")).ok, "the rule is back")
            } finally {
                withContext(NonCancellable) {
                    sessions.stopAll(StopReason.STOPPED)
                    runCatching { homes.destroy(name) }
                    docker.run(helpers.firewall("remove", FirewallRules.prefixFor(namespace)))
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

    private fun tools(vararg command: String): List<String> =
        listOf("run", "--rm", "--network=host", "--cap-drop=ALL", "--cap-add=NET_ADMIN", "--cap-add=NET_RAW", "--entrypoint", command.first(), "regolith-tools:it") + command.drop(1)

    private suspend fun build(vararg args: String) {
        val result = docker.run(listOf("build", "--quiet") + args, timeout = 15.minutes)
        check(result.ok) { "docker build failed: ${result.stderr}" }
    }
}
