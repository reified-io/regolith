package io.reified.regolith.server.docker

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.domain.EntryType
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.FileEntry
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxLayout
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.ports.ExecSpec
import io.reified.regolith.server.ports.HomeMount
import io.reified.regolith.server.ports.LimitEvents
import io.reified.regolith.server.ports.TreeFile
import io.reified.regolith.server.ports.RunningProcess
import io.reified.regolith.server.ports.SandboxNetwork
import io.reified.regolith.server.ports.SandboxRuntime
import io.reified.regolith.server.ports.SessionHandle
import io.reified.regolith.server.ports.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** [SandboxRuntime] on one Docker host, driven through [DockerCli]. */
class DockerRuntime(private val docker: DockerCli, private val spec: ContainerSpec) : SandboxRuntime {
    override suspend fun initialize(): SandboxNetwork {
        val leftovers = docker.run(listOf("ps", "--all", "--quiet", "--filter", "label=${spec.namespaceLabel}"))
            .requireOk("Listing leftover sessions").text.lines().filter { it.isNotBlank() }

        if (leftovers.isNotEmpty()) {
            docker.run(listOf("rm", "--force") + leftovers).requireOk("Removing leftover sessions")
            log.info { "Leftover sessions removed: count=[${leftovers.size}]" }
        }

        if (!docker.run(listOf("network", "inspect", spec.network)).ok) {
            docker.run(spec.networkCreate()).requireOk("Creating the sandbox network")
        }

        val inspect = docker.run(
            listOf("network", "inspect", "--format", "{{.Id}} {{range .IPAM.Config}}{{.Subnet}} {{.Gateway}}{{end}}", spec.network),
        ).requireOk("Inspecting the sandbox network").text.trim().split(' ')
        check(inspect.size == 3) { "The sandbox network ${spec.network} has no single IPv4 subnet: $inspect" }
        val (id, subnet, gateway) = inspect

        // docker names the bridge of a user-defined network after the first 12 characters of its id.
        return SandboxNetwork(name = spec.network, bridge = "br-${id.take(12)}", subnet = subnet, gateway = gateway)
    }

    override suspend fun pull(image: String) {
        docker.run(spec.pull(image), timeout = PULL_TIMEOUT).requireOk("Pulling $image")
    }

    override suspend fun startSession(sandbox: Sandbox, home: HomeMount, image: String): SessionHandle {
        val container = spec.container(sandbox.name)
        // a container can survive a crash between its start and its registration; it is never reused.
        docker.run(listOf("rm", "--force", container))
        docker.run(spec.run(sandbox, home, image), timeout = START_TIMEOUT).requireOk("Starting session ${sandbox.name}")
        if (sandbox.network == NetworkPolicy.None) return SessionHandle(container, address = null)

        return try {
            SessionHandle(container, address(sandbox.name))
        } catch (e: Exception) {
            docker.run(listOf("rm", "--force", container))
            throw e
        }
    }

    override suspend fun attachNetwork(name: SandboxName): String {
        docker.run(spec.connect(name)).requireOk("Attaching session $name to ${spec.network}")

        return address(name)
    }

    override suspend fun detachNetwork(name: SandboxName) {
        docker.run(spec.disconnect(name)).requireOk("Detaching session $name from ${spec.network}")
        check(docker.run(spec.address(name)).text.isBlank()) { "Session $name still has an address after detaching" }
    }

    private suspend fun address(name: SandboxName): String {
        val address = docker.run(spec.address(name)).requireOk("Inspecting session $name").text.trim()
        check(address.isNotEmpty()) { "Session $name has no address on ${spec.network}" }

        return address
    }

    override suspend fun stopSession(name: SandboxName) {
        val result = docker.run(listOf("rm", "--force", spec.container(name)))
        if (!result.ok && "No such container" !in result.stderr) result.requireOk("Stopping session $name")
    }

    override suspend fun exec(name: SandboxName, spec: ExecSpec): RunningProcess = withContext(Dispatchers.IO) {
        DockerProcess(docker.start(this@DockerRuntime.spec.exec(name, spec), stdin = spec.stdin), spec.stdin)
    }

    override suspend fun cpuMicros(name: SandboxName): Long {
        val stat = docker.run(spec.cpuStat(name)).requireOk("Reading the cpu usage of session $name").text
        val usage = stat.lineSequence().firstOrNull { it.startsWith("usage_usec ") }?.substringAfter(' ')?.trim()?.toLongOrNull()

        return checkNotNull(usage) { "Session $name reports no cpu usage" }
    }

    override suspend fun limitEvents(name: SandboxName): LimitEvents {
        val text = docker.run(spec.limitEvents(name)).requireOk("Reading the limit events of session $name").text
        val memory = text.substringBefore("\n--\n")
        val pids = text.substringAfter("\n--\n", "")
        fun counter(section: String, key: String): Long =
            section.lineSequence().firstOrNull { it.startsWith("$key ") }?.substringAfter(' ')?.trim()?.toLongOrNull() ?: 0

        return LimitEvents(oomKills = counter(memory, "oom_kill"), forksRefused = counter(pids, "max"))
    }

    override suspend fun signal(name: SandboxName, exec: ExecId, signal: Signal) {
        docker.run(spec.signal(name, exec, signal)).requireOk("Signalling exec $exec")
    }

    override suspend fun stat(name: SandboxName, path: String): FileEntry {
        val result = docker.run(spec.stat(name, path))
        failOn(result, path)
        val fields = result.text.split(Char(0))
        check(fields.size >= 4) { "Unexpected stat output for $path" }

        return FileEntry(
            path = path,
            name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" },
            type = statType(fields[0]),
            size = fields[1].toLong(),
            modifiedAt = Instant.fromEpochSeconds(fields[2].toLong()),
            mode = fields[3].toInt(8),
        )
    }

    override suspend fun list(name: SandboxName, path: String, maxEntries: Int): List<FileEntry> {
        val directory = stat(name, path)
        requireValid(directory.type == EntryType.DIRECTORY) { "`$path` is not a directory" }
        val result = docker.run(spec.list(name, path), maxStdout = maxEntries * ENTRY_BYTES_ESTIMATE)
        failOn(result, path)

        // find prints five NUL-terminated fields per entry: name, type, size, mtime, mode.
        return result.text.split(Char(0)).dropLast(1).chunked(5).filter { it.size == 5 }.take(maxEntries).map { fields ->
            FileEntry(
                path = "${path.trimEnd('/')}/${fields[0]}",
                name = fields[0],
                type = findType(fields[1]),
                size = fields[2].toLong(),
                modifiedAt = Instant.fromEpochMilliseconds((fields[3].toDouble() * 1000).toLong()),
                mode = fields[4].toInt(8),
            )
        }.sortedBy { it.name }
    }

    override suspend fun read(name: SandboxName, path: String, sink: OutputStream, maxBytes: Long) = withContext(Dispatchers.IO) {
        val process = docker.start(spec.read(name, path), stdin = false)
        coroutineScope {
            val stderr = async(Dispatchers.IO) { quietly { process.errorStream.readNBytes(MAX_STDERR).toString(StandardCharsets.UTF_8) } }
            var total = 0L
            process.inputStream.use { input ->
                val buffer = ByteArray(COPY_CHUNK)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        process.destroyForcibly()
                        throw RegolithError.TooLarge("`$path` grew past $maxBytes bytes while it was read")
                    }
                    sink.write(buffer, 0, count)
                }
            }
            val code = process.onExit().await().exitValue()
            if (code != 0) failOn(DockerCli.Result(code, ByteArray(0), stderr.await()), path)
        }
    }

    override suspend fun write(name: SandboxName, path: String, source: InputStream, maxBytes: Long): FileEntry {
        requireValid(path.trimEnd('/') != SandboxLayout.HOME && path.trimEnd('/').isNotEmpty()) { "`$path` cannot be replaced" }
        val temporaryName = ".regolith-upload-${UUID.randomUUID()}"
        withContext(Dispatchers.IO) {
            val process = docker.start(spec.write(name, path, temporaryName), stdin = true)
            coroutineScope {
                val stderr = async(Dispatchers.IO) { quietly { process.errorStream.readNBytes(MAX_STDERR).toString(StandardCharsets.UTF_8) } }
                val drainStdout = async(Dispatchers.IO) { quietly { process.inputStream.use { it.readAllBytes() }.size.toString() } }
                val delivered = try {
                    copyBody(source, process.outputStream, maxBytes)
                } catch (e: Exception) {
                    // a body that failed or ran past its limit: nothing renames the temporary file, so it goes.
                    process.destroyForcibly()
                    runCatching { process.outputStream.close() }
                    removeUpload(name, path, temporaryName)
                    throw e
                }
                runCatching { process.outputStream.close() }
                val code = process.onExit().await().exitValue()
                drainStdout.await()
                // a writer that stopped taking the body, a full disk most often, explains itself in its exit.
                if (code != 0) failOn(DockerCli.Result(code, ByteArray(0), stderr.await()), path)
                if (!delivered) {
                    removeUpload(name, path, temporaryName)
                    error("The writer of $path exited before the body ended")
                }
            }

            val moved = docker.run(spec.move(name, path, temporaryName))
            if (!moved.ok) {
                removeUpload(name, path, temporaryName)
                failOn(moved, path)
            }
        }

        return stat(name, path)
    }

    /** Copies [source] into a writer's stdin and closes it; false when the writer stopped taking it first. */
    private fun copyBody(source: InputStream, stdin: OutputStream, maxBytes: Long): Boolean {
        val buffer = ByteArray(COPY_CHUNK)
        var total = 0L

        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw RegolithError.TooLarge("Files are limited to $maxBytes bytes")
            if (!writerTakes { stdin.write(buffer, 0, count) }) return false
        }

        return writerTakes { stdin.close() }
    }

    /** A write to a writer that has exited breaks its pipe, which is the writer's failure, not the body's. */
    private inline fun writerTakes(write: () -> Unit): Boolean = try {
        write()
        true
    } catch (_: IOException) {
        false
    }

    private suspend fun removeUpload(name: SandboxName, path: String, temporaryName: String) {
        val directory = path.substringBeforeLast('/', SandboxLayout.HOME).ifEmpty { "/" }
        docker.run(spec.delete(name, "$directory/$temporaryName", recursive = false, directory = false))
    }

    override suspend fun tree(name: SandboxName, path: String, maxFiles: Int): List<TreeFile> {
        val directory = stat(name, path)
        requireValid(directory.type == EntryType.DIRECTORY) { "`$path` is not a directory" }
        val result = docker.run(spec.tree(name, path), maxStdout = maxFiles * TREE_BYTES_ESTIMATE)
        failOn(result, path)

        // find prints two NUL-terminated fields per file: size and the path relative to the directory.
        return result.text.split(Char(0)).dropLast(1).chunked(2).filter { it.size == 2 }.map { (size, relative) ->
            TreeFile(relative, size.toLongOrNull() ?: 0)
        }
    }

    override suspend fun copyOut(name: SandboxName, path: String, destination: Path) {
        withContext(Dispatchers.IO) { Files.createDirectories(destination) }
        val result = docker.run(spec.copyOut(name, path, destination.toString()))
        failOn(result, path)
    }

    override suspend fun delete(name: SandboxName, path: String, recursive: Boolean) {
        val trimmed = path.trimEnd('/')
        requireValid(trimmed.isNotEmpty() && trimmed != SandboxLayout.HOME) { "`$path` cannot be deleted" }
        val entry = stat(name, path)
        val result = docker.run(spec.delete(name, path, recursive, directory = entry.type == EntryType.DIRECTORY))
        failOn(result, path)
    }

    private fun failOn(result: DockerCli.Result, path: String) {
        if (result.ok) return
        val reason = result.stderr
        throw when {
            "No such container" in reason || "is not running" in reason ->
                RegolithError.Unavailable("The session ended while `$path` was being accessed")
            "No such file or directory" in reason -> RegolithError.NotFound("`$path` does not exist")
            "Permission denied" in reason -> RegolithError.Invalid("Permission denied: `$path`")
            "Not a directory" in reason || "Is a directory" in reason -> RegolithError.Invalid(reason.lineSequence().first())
            "Directory not empty" in reason -> RegolithError.Conflict("`$path` is not empty; delete it recursively")
            "cannot overwrite directory" in reason -> RegolithError.Invalid("`$path` is a directory")
            "No space left on device" in reason || "Disk quota exceeded" in reason ->
                RegolithError.TooLarge("The sandbox home is full")
            else -> IllegalStateException("File operation on $path failed (exit ${result.exitCode}): $reason")
        }
    }

    private fun statType(value: String): EntryType = when (value) {
        "regular file", "regular empty file" -> EntryType.FILE
        "directory" -> EntryType.DIRECTORY
        "symbolic link" -> EntryType.SYMLINK
        else -> EntryType.OTHER
    }

    private fun findType(value: String): EntryType = when (value) {
        "f" -> EntryType.FILE
        "d" -> EntryType.DIRECTORY
        "l" -> EntryType.SYMLINK
        else -> EntryType.OTHER
    }

    /**
     * Reads a helper stream that a killed process closes under the reader. Its failure must not
     * replace the reason the process was killed, which the caller is about to throw.
     */
    private fun quietly(read: () -> String): String = try {
        read()
    } catch (_: IOException) {
        ""
    }

    private class DockerProcess(private val process: Process, stdin: Boolean) : RunningProcess {
        override val stdout: InputStream = process.inputStream
        override val stderr: InputStream = process.errorStream
        override val stdin: OutputStream? = if (stdin) process.outputStream else null

        override suspend fun awaitExit(): Int = process.onExit().await().exitValue()

        override fun detach() {
            process.destroy()
            runCatching { stdout.close() }
            runCatching { stderr.close() }
            runCatching { stdin?.close() }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}

        /** A pull runs in the background, so it may take as long as a large image needs. */
        val PULL_TIMEOUT = 20.minutes

        /** Starting holds the server's session capacity, so an image not on the host yet gets one try. */
        val START_TIMEOUT = 5.minutes
        const val MAX_STDERR = 16 * 1024
        const val COPY_CHUNK = 64 * 1024
        const val ENTRY_BYTES_ESTIMATE = 512
        const val TREE_BYTES_ESTIMATE = 512
    }
}
