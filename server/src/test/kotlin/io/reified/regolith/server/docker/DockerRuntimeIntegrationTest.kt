package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.EntryType
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.ImagePolicy
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.ExecSpec
import io.reified.regolith.server.ports.HomeMount
import io.reified.regolith.server.ports.Signal
import io.reified.regolith.server.ports.SnapshotBounds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    private val name = SandboxId.random()
    private val volume = "$namespace-probe-home"

    @Test
    fun `a session runs commands, handles files and dies with its container`() {
        assumeTrue(enabled, "set REGOLITH_DOCKER_TESTS=1 to run against a real docker daemon")
        runBlocking {
            try {
                runtime.initialize()
                docker.run(listOf("volume", "create", volume)).requireOk("volume create")
                val now = Clock.System.now()
                val image = "debian:trixie-slim"
                val sandbox = Sandbox(name, alias = null, site = null, ImagePolicy.Pin(image), Resources(0.5, 256, 512), NetworkPolicy.Public, Lifecycle(5.minutes, 1.days, 1.days), emptyMap(), emptyMap(), now, now)
                runtime.startSession(sandbox, HomeMount(volume), image)

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
                assertTrue(runtime.readable(name, "/tmp/probe/nested/file.txt"))
                // 0640 root:shadow in this image: stat answers where a read would fail, which is why the
                // read is refused on this answer rather than on a stream that already promised 200.
                assertEquals(EntryType.FILE, runtime.stat(name, "/etc/shadow").type)
                assertFalse(runtime.readable(name, "/etc/shadow"), "uid 1000 must not read /etc/shadow")
                assertFailsWith<RegolithError.TooLarge> { runtime.write(name, "/tmp/probe/big", ByteArray(4096).inputStream(), 1024) }
                assertFailsWith<RegolithError.NotFound> { runtime.stat(name, "/tmp/probe/big") }

                // a body that breaks off looks finished from inside the container; the old file must stay.
                assertFailsWith<IOException> { runtime.write(name, "/tmp/probe/nested/file.txt", breakingAfter(100 * 1024), 1024L * 1024) }
                // /tmp holds half the session's 256 MB, so 200 MB fills it before the body ends.
                assertFailsWith<RegolithError.InsufficientStorage> { runtime.write(name, "/tmp/probe/nested/huge", zeros(200L * 1024 * 1024), 512L * 1024 * 1024) }
                assertFailsWith<RegolithError.Invalid> { runtime.write(name, "/tmp/probe/nested", "x".byteInputStream(), 1024) }
                assertEquals(14, runtime.stat(name, "/tmp/probe/nested/file.txt").size)
                assertEquals(listOf("file.txt"), runtime.list(name, "/tmp/probe/nested", 100).map { it.name }, "an upload left a file behind")

                // a snapshot is the sandbox user's own tar, bounded as it arrives: a symlink out stays behind.
                docker.run(listOf("exec", spec.container(name), "sh", "-c", "ln -s /etc/passwd /tmp/probe/escape && mkdir -p /tmp/probe/deep && printf js > /tmp/probe/deep/app.js"))
                    .requireOk("preparing the snapshot")
                assertEquals(setOf("nested/file.txt", "deep/app.js"), runtime.tree(name, "/tmp/probe", 100).map { it.path }.toSet())
                val snapshot = Files.createTempDirectory("regolith-it-snapshot")
                try {
                    runtime.copyOut(name, "/tmp/probe", snapshot, SnapshotBounds(maxFiles = 10, maxFileBytes = 1024, maxTotalBytes = 4096))
                    val copied = Files.walk(snapshot).use { paths -> paths.filter { it.isRegularFile() }.map { it.relativeTo(snapshot).joinToString("/") }.toList() }
                    assertEquals(setOf("nested/file.txt", "deep/app.js"), copied.toSet())
                    assertEquals("hello regolith", snapshot.resolve("nested/file.txt").readText())
                    assertFalse(snapshot.resolve("escape").exists(), "a symlink was recreated on the server")
                    assertFailsWith<RegolithError.TooLarge> { runtime.copyOut(name, "/tmp/probe", snapshot.resolve("again"), SnapshotBounds(maxFiles = 1, maxFileBytes = 1024, maxTotalBytes = 4096)) }
                } finally {
                    snapshot.toFile().deleteRecursively()
                }
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

                // leftovers that fill the pids limit leave the container running with nothing startable in it:
                // every read from inside fails, and only the daemon can still say the session is there.
                val flood = runtime.exec(name, ExecSpec(ExecId.random(), ExecCommand.Shell("for i in \$(seq 1 700); do sleep 300 & done 2>/dev/null; true"), "/tmp", emptyMap(), stdin = false))
                withTimeout(60.seconds) { flood.awaitExit() }
                flood.detach()
                assertFailsWith<IllegalStateException> { runtime.cpuMicros(name) }
                assertFailsWith<IllegalStateException> { runtime.limitEvents(name) }
                assertTrue(runtime.isRunning(name), "a full session still runs")

                runtime.stopSession(name)
                assertFalse(runtime.isRunning(name), "a removed session is not running")
                runtime.startSession(sandbox, HomeMount(volume), image)
                assertTrue(runtime.isRunning(name))
                // a command can end the idle entrypoint it shares a uid with, and the container goes with it.
                val killer = runtime.exec(name, ExecSpec(ExecId.random(), ExecCommand.Shell("for p in /proc/[0-9]*; do [ \"\$(tr '\\0' ' ' < \$p/cmdline 2>/dev/null)\" = 'sleep infinity ' ] && kill \${p#/proc/}; done; sleep 5"), "/tmp", emptyMap(), stdin = false))
                withTimeout(30.seconds) { killer.awaitExit() }
                killer.detach()
                assertFalse(runtime.isRunning(name), "killing the entrypoint ends the container")
            } finally {
                runtime.stopSession(name)
                docker.run(listOf("volume", "rm", "--force", volume))
                docker.run(listOf("network", "rm", spec.network))
            }
        }
    }
}

/** A body of [count] zero bytes, without holding them. */
private fun zeros(count: Long): InputStream = object : InputStream() {
    private var left = count

    override fun read(): Int = if (left-- > 0) 0 else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val count = minOf(len.toLong(), left).toInt()
        b.fill(0, off, off + count)
        left -= count

        return count
    }
}

/** A body whose client goes away after [bytes] bytes. */
private fun breakingAfter(bytes: Int): InputStream = object : InputStream() {
    private var sent = 0

    override fun read(): Int = read(ByteArray(1), 0, 1)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (sent >= bytes) throw IOException("The client went away")
        val count = minOf(len, bytes - sent)
        b.fill('y'.code.toByte(), off, off + count)
        sent += count

        return count
    }
}
