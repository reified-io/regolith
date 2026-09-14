package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.EntryType
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.ports.ExecSpec
import io.reified.regolith.server.ports.HomeMount
import io.reified.regolith.server.ports.Signal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The runtime against a real Docker daemon. Opt in with `REGOLITH_DOCKER_TESTS=1`; it pulls a Debian
 * image and cleans up only what it created under its own namespace. The home here is a plain volume,
 * which production never uses — this test is about containers, not home isolation.
 */
class DockerRuntimeIntegrationTest {
    private val enabled = System.getenv("REGOLITH_DOCKER_TESTS") == "1"
    private val namespace = "regolith-it"
    private val docker = DockerCli("docker")
    private val spec = ContainerSpec(namespace, "/bin/bash")
    private val runtime = DockerRuntime(docker, spec)
    private val name = SandboxName.parse("probe")
    private val volume = "$namespace-probe-home"

    @Test
    fun `a session runs commands, handles files and dies with its container`() {
        assumeTrue(enabled, "set REGOLITH_DOCKER_TESTS=1 to run against a real docker daemon")
        runBlocking {
            try {
                runtime.initialize()
                docker.run(listOf("volume", "create", volume)).requireOk("volume create")
                val now = Clock.System.now()
                val sandbox = Sandbox(name, "debian:trixie-slim", Resources(0.5, 256, 512), NetworkPolicy.Public, Lifecycle(5.minutes, 1.days, 1.days), emptyMap(), emptyMap(), now, now)
                runtime.startSession(sandbox, HomeMount(volume))

                val idle = runtime.cpuMicros(name)
                val burner = runtime.exec(name, ExecSpec(ExecId.random(), ExecCommand.Shell("timeout 1 sh -c 'while :; do :; done'; true"), "/tmp", emptyMap(), stdin = false))
                withTimeout(30.seconds) { burner.awaitExit() }
                burner.detach()
                val burned = runtime.cpuMicros(name) - idle
                assertTrue(burned > 300_000, "a one-second busy loop counted ${burned}us")

                val beforeOom = runtime.limitEvents(name)
                val hog = runtime.exec(name, ExecSpec(ExecId.random(), ExecCommand.Shell("head -c 600M /dev/zero | tail > /dev/null"), "/tmp", emptyMap(), stdin = false))
                val hogExit = withTimeout(60.seconds) { hog.awaitExit() }
                hog.detach()
                assertTrue(hogExit != 0, "a 600 MB allocation in a 256 MB session must fail")
                assertTrue(runtime.limitEvents(name).oomKills > beforeOom.oomKills, "the kernel's OOM kill is counted")

                val process = runtime.exec(name, ExecSpec(ExecId.random(), ExecCommand.Shell("id -u; echo oops >&2; exit 7"), "/tmp", emptyMap(), stdin = false))
                assertEquals(7, withTimeout(30.seconds) { process.awaitExit() })
                assertEquals("1000", process.stdout.readAllBytes().decodeToString().trim())
                assertEquals("oops", process.stderr.readAllBytes().decodeToString().trim())

                val written = runtime.write(name, "/tmp/probe/nested/file.txt", "hello regolith".byteInputStream(), 1024)
                assertEquals(14, written.size)
                val sink = ByteArrayOutputStream()
                runtime.read(name, "/tmp/probe/nested/file.txt", sink, 1024)
                assertEquals("hello regolith", sink.toString())
                assertEquals(listOf("file.txt"), runtime.list(name, "/tmp/probe/nested", 100).map { it.name })
                assertEquals(EntryType.DIRECTORY, runtime.stat(name, "/tmp/probe").type)
                assertFailsWith<RegolithError.TooLarge> { runtime.write(name, "/tmp/probe/big", ByteArray(4096).inputStream(), 1024) }
                runtime.delete(name, "/tmp/probe", recursive = true)
                assertFailsWith<RegolithError.NotFound> { runtime.stat(name, "/tmp/probe") }

                val sleeper = ExecId.random()
                val sleeping = runtime.exec(name, ExecSpec(sleeper, ExecCommand.Shell("sleep 300 & sleep 300"), "/tmp", emptyMap(), stdin = false))
                Thread.sleep(500)
                runtime.signal(name, sleeper, Signal.TERM)
                assertEquals(143, withTimeout(30.seconds) { sleeping.awaitExit() })
                // the slim image has no procps, so the survivors are counted straight from /proc, by command
                // line: the session's own idle entrypoint is a `sleep infinity` that must not be counted.
                val survivors = docker.run(
                    listOf("exec", spec.container(name), "sh", "-c", "for p in /proc/[0-9]*; do tr '\\0' ' ' < \$p/cmdline 2>/dev/null; echo; done | grep -c '^sleep 300' || true"),
                ).requireOk("counting survivors").text.trim()
                val entrypoint = docker.run(
                    listOf("exec", spec.container(name), "sh", "-c", "for p in /proc/[0-9]*; do tr '\\0' ' ' < \$p/cmdline 2>/dev/null; echo; done | grep -c '^sleep infinity' || true"),
                ).requireOk("finding the entrypoint").text.trim()
                assertEquals("1", entrypoint, "the probe must see the session's own processes")
                assertEquals("0", survivors, "detached children survived the signal")
                sleeping.detach()
            } finally {
                runtime.stopSession(name)
                docker.run(listOf("volume", "rm", "--force", volume))
                docker.run(listOf("network", "rm", spec.network))
            }
        }
    }
}
