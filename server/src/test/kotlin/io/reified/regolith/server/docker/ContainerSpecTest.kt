package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.ports.ExecSpec
import io.reified.regolith.server.ports.HomeMount
import io.reified.regolith.server.ports.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ContainerSpecTest {
    private val spec = ContainerSpec("regolith", "/bin/bash", homeReadBps = "100mb", homeWriteBps = "50mb")
    private val name = SandboxName.parse("keeper")
    private val sandbox = Sandbox(
        name = name,
        image = "example/sandbox:1",
        resources = Resources(0.5, 768, 4096),
        network = NetworkPolicy.Public,
        lifecycle = Lifecycle(15.minutes, 1.days, 14.days),
        env = mapOf("GREETING" to "hello world"),
        labels = emptyMap(),
        createdAt = Instant.fromEpochSeconds(0),
        lastUsedAt = Instant.fromEpochSeconds(0),
    )

    private fun List<String>.valueAfter(flag: String): List<String> = indices.filter { this[it] == flag }.map { this[it + 1] }

    @Test
    fun `a session runs unprivileged, bounded and idle until its first exec`() {
        val args = spec.run(sandbox, HomeMount("regolith-keeper-home"))

        assertEquals(listOf("1000:1000"), args.valueAfter("--user"))
        assertEquals(listOf("ALL"), args.valueAfter("--cap-drop"))
        assertEquals(listOf("no-new-privileges"), args.valueAfter("--security-opt"))
        assertTrue("--read-only" in args && "--init" in args)
        assertEquals(listOf("768m"), args.valueAfter("--memory"))
        assertEquals(listOf("768m"), args.valueAfter("--memory-swap"))
        assertEquals(listOf("0.50"), args.valueAfter("--cpus"))
        assertEquals(listOf("regolith-sandboxes"), args.valueAfter("--network"))
        assertEquals(listOf("type=volume,src=regolith-keeper-home,dst=/home/sandbox"), args.valueAfter("--mount"))
        assertTrue("GREETING=hello world" in args.valueAfter("--env"))
        assertEquals(listOf("example/sandbox:1", "infinity"), args.takeLast(2))
        assertTrue(args.none { "docker.sock" in it || it == "--privileged" || it == "--cap-add" })
        assertEquals(listOf("net.ipv6.conf.all.disable_ipv6=1", "net.ipv6.conf.default.disable_ipv6=1"), args.valueAfter("--sysctl"))
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), args.valueAfter("--dns"))
    }

    @Test
    fun `a none sandbox starts with no network and its home device is throttled`() {
        val args = spec.run(sandbox.copy(network = NetworkPolicy.None), HomeMount("regolith-keeper-home", "/dev/loop7"))

        assertEquals(listOf("none"), args.valueAfter("--network"))
        assertEquals(emptyList(), args.valueAfter("--dns"))
        assertEquals(listOf("/dev/loop7:100mb"), args.valueAfter("--device-read-bps"))
        assertEquals(listOf("/dev/loop7:50mb"), args.valueAfter("--device-write-bps"))
    }

    @Test
    fun `an exec carries its marker and never goes through a server-side shell`() {
        val id = ExecId.random()
        val script = "echo \"\$HOME\"; rm -rf /tmp/x"
        val args = spec.exec(name, ExecSpec(id, ExecCommand.Shell(script), "/home/sandbox/app", emptyMap(), stdin = false))

        assertEquals(listOf("regolith-keeper", "/bin/bash", "-c", script), args.takeLast(4))
        assertTrue("REGOLITH_EXEC_ID=$id" in args.valueAfter("--env"))
        assertTrue("--interactive" !in args)
        assertEquals(listOf("/home/sandbox/app"), args.valueAfter("--workdir"))
    }

    @Test
    fun `a signal finds processes by marker, with the id passed as an argument`() {
        val id = ExecId.random()
        val args = spec.signal(name, id, Signal.KILL)

        assertEquals(listOf(id.value, "KILL"), args.takeLast(2))
        assertTrue(args.any { "REGOLITH_EXEC_ID=\$1" in it })
    }

    @Test
    fun `a path is always an argument, never part of a script`() {
        val path = "/home/sandbox/\$(reboot); ls"
        assertEquals(path, spec.write(name, path, ".tmp").takeLast(2).first())
        assertEquals(path, spec.read(name, path).last())
        assertEquals(path, spec.delete(name, path, recursive = true, directory = true).last())
    }
}
