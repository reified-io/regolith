package io.reified.regolith.server.support

import io.reified.regolith.server.domain.EntryType
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.FileEntry
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.ExecSpec
import io.reified.regolith.server.ports.HomeMount
import io.reified.regolith.server.ports.HomeStore
import io.reified.regolith.server.ports.LimitEvents
import io.reified.regolith.server.ports.PublishedSite
import io.reified.regolith.server.ports.SiteLimits
import io.reified.regolith.server.ports.SnapshotBounds
import io.reified.regolith.server.ports.SitePublisher
import io.reified.regolith.server.ports.TreeFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.time.Instant
import io.reified.regolith.server.ports.NetworkEnforcer
import io.reified.regolith.server.ports.RunningProcess
import io.reified.regolith.server.ports.SandboxNetwork
import io.reified.regolith.server.ports.SandboxRuntime
import io.reified.regolith.server.ports.SessionHandle
import io.reified.regolith.server.ports.Signal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A runtime without containers. A shell script is a `;`-separated list of fake commands:
 * `echo TEXT`, `err TEXT`, `exit N`, `sleep` (until signalled), `cat` (stdin to stdout) and
 * `bytes N` (N bytes of `x`).
 */
class FakeRuntime : SandboxRuntime {
    val sessions: MutableSet<SandboxId> = ConcurrentHashMap.newKeySet()
    val attached: MutableSet<SandboxId> = ConcurrentHashMap.newKeySet()
    val started = CopyOnWriteArrayList<SandboxId>()

    /** The image each session started on, and every image the server asked to have on the host. */
    val startedOn = ConcurrentHashMap<SandboxId, String>()
    val pulled = CopyOnWriteArrayList<String>()

    /** CPU microseconds each session reports; a test moves it by hand. */
    val cpu = ConcurrentHashMap<SandboxId, Long>()

    /** When set, every file write fails the way a full disk makes it. */
    @Volatile
    var diskFull = false

    /** Paths the sandbox user may not read, whatever put them there. */
    val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Sessions whose container exited under them, the way `pkill sleep` inside one ends it. */
    val exited: MutableSet<SandboxId> = ConcurrentHashMap.newKeySet()

    /** Sessions in which nothing more can be started, the way leftovers filling the pids limit leave one. */
    val unreachable: MutableSet<SandboxId> = ConcurrentHashMap.newKeySet()

    private val processes = ConcurrentHashMap<ExecId, Pair<SandboxId, FakeProcess>>()
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun initialize() = SandboxNetwork("test-sandboxes", "br-test", "172.30.0.0/16", "172.30.0.1")

    override suspend fun pull(image: String) {
        pulled += image
    }

    override suspend fun startSession(sandbox: Sandbox, home: HomeMount, image: String): SessionHandle {
        exited -= sandbox.id
        unreachable -= sandbox.id
        sessions += sandbox.id
        started += sandbox.id
        startedOn[sandbox.id] = image
        if (sandbox.network == NetworkPolicy.None) return SessionHandle("test-${sandbox.id}", null)
        attached += sandbox.id

        return SessionHandle("test-${sandbox.id}", "172.30.0.${sessions.size + 1}")
    }

    override suspend fun attachNetwork(sandbox: SandboxId): String {
        attached += sandbox

        return "172.30.1.${attached.size}"
    }

    override suspend fun detachNetwork(sandbox: SandboxId) {
        attached -= sandbox
    }

    override suspend fun stopSession(sandbox: SandboxId) {
        sessions -= sandbox
        attached -= sandbox
        processes.values.filter { it.first == sandbox }.forEach { it.second.kill(137) }
    }

    /** Ends a session's container from inside, as `pkill sleep` would: every command in it dies with it. */
    fun exitContainer(sandbox: SandboxId) {
        exited += sandbox
        processes.values.filter { it.first == sandbox }.forEach { it.second.kill(137) }
    }

    override suspend fun exec(sandbox: SandboxId, spec: ExecSpec): RunningProcess {
        check(sandbox in sessions) { "No session for $sandbox" }
        val process = FakeProcess(spec.stdin) { limits.merge(sandbox, LimitEvents(1, 0)) { a, b -> LimitEvents(a.oomKills + b.oomKills, a.forksRefused) } }
        processes[spec.id] = sandbox to process
        // like the docker client: the command line starts, and fails at once with the daemon's own words.
        val command = when (sandbox) {
            in exited -> ExecCommand.Shell("err Error response from daemon: container is not running; exit 1")
            in unreachable -> ExecCommand.Shell("err OCI runtime exec failed: unable to start container process; exit 126")
            else -> spec.command
        }
        scope.launch { process.run(command) }

        return process
    }

    override suspend fun isRunning(sandbox: SandboxId): Boolean = sandbox in sessions && sandbox !in exited

    /** Throws the way a `docker exec` into the session does when nothing can be started in it. */
    private fun requireReachable(sandbox: SandboxId) {
        check(sandbox !in exited && sandbox !in unreachable) { "Nothing can be started in session $sandbox" }
    }

    override suspend fun cpuMicros(sandbox: SandboxId): Long {
        requireReachable(sandbox)

        return cpu[sandbox] ?: 0
    }

    /** Limit counters each session reports; a fake command `oom` bumps them the way the kernel would. */
    val limits = ConcurrentHashMap<SandboxId, LimitEvents>()

    override suspend fun limitEvents(sandbox: SandboxId): LimitEvents {
        requireReachable(sandbox)

        return limits[sandbox] ?: LimitEvents(0, 0)
    }

    override suspend fun signal(sandbox: SandboxId, exec: ExecId, signal: Signal) {
        requireReachable(sandbox)
        processes[exec]?.second?.kill(if (signal == Signal.TERM) 143 else 137)
    }

    /** Puts a file in a session without going through the api, for a test that needs one to exist. */
    fun place(sandbox: SandboxId, path: String, text: String) {
        files["$sandbox:$path"] = text.toByteArray()
    }

    /** What a session holds at [path], or null. */
    fun content(sandbox: SandboxId, path: String): String? = files["$sandbox:$path"]?.decodeToString()

    override suspend fun stat(sandbox: SandboxId, path: String): FileEntry {
        val bytes = files["$sandbox:$path"]
        val isDirectory = files.keys.any { it.startsWith("$sandbox:${path.trimEnd('/')}/") }
        if (bytes == null && !isDirectory) throw RegolithError.NotFound("`$path` does not exist")

        return entry(path, bytes)
    }

    override suspend fun list(sandbox: SandboxId, path: String, maxEntries: Int): List<FileEntry> {
        val prefix = "$sandbox:${path.trimEnd('/')}/"

        return files.entries.filter { it.key.startsWith(prefix) && '/' !in it.key.removePrefix(prefix) }
            .map { entry(it.key.substringAfter(':'), it.value) }
            .sortedBy { it.name }
    }

    override suspend fun readable(sandbox: SandboxId, path: String): Boolean {
        stat(sandbox, path)

        return path !in unreadable
    }

    override suspend fun read(sandbox: SandboxId, path: String, sink: OutputStream, maxBytes: Long) {
        sink.write(files["$sandbox:$path"] ?: throw RegolithError.NotFound("`$path` does not exist"))
    }

    override suspend fun write(sandbox: SandboxId, path: String, source: InputStream, maxBytes: Long): FileEntry {
        val bytes = runInterruptible { source.readAllBytes() }
        if (bytes.size > maxBytes) throw RegolithError.TooLarge("Files are limited to $maxBytes bytes")
        if (diskFull) throw RegolithError.InsufficientStorage("The disk that holds `$path` is full; delete files to make room")
        files["$sandbox:$path"] = bytes

        return entry(path, bytes)
    }

    override suspend fun delete(sandbox: SandboxId, path: String, recursive: Boolean) {
        files.remove("$sandbox:$path") ?: throw RegolithError.NotFound("`$path` does not exist")
    }

    override suspend fun tree(sandbox: SandboxId, path: String, maxFiles: Int): List<TreeFile> =
        files.filterKeys { it.startsWith("$sandbox:${path.trimEnd('/')}/") }
            .map { (key, bytes) -> TreeFile(key.substringAfter("$sandbox:${path.trimEnd('/')}/"), bytes.size.toLong()) }

    /** Copies what the session holds now, bounded as the runtime is: a file placed after the listing still counts. */
    override suspend fun copyOut(sandbox: SandboxId, path: String, destination: Path, bounds: SnapshotBounds) {
        var total = 0L
        for ((index, file) in tree(sandbox, path, Int.MAX_VALUE).withIndex()) {
            total += file.size
            if (index >= bounds.maxFiles) throw RegolithError.TooLarge("The directory holds more than ${bounds.maxFiles} files")
            if (file.size > bounds.maxFileBytes) throw RegolithError.TooLarge("`${file.path}` is larger than ${bounds.maxFileBytes} bytes")
            if (total > bounds.maxTotalBytes) throw RegolithError.TooLarge("The directory holds more than ${bounds.maxTotalBytes} bytes")
            val target = destination.resolve(file.path)
            Files.createDirectories(target.parent)
            Files.write(target, files.getValue("$sandbox:${path.trimEnd('/')}/${file.path}"))
        }
    }

    private fun entry(path: String, bytes: ByteArray?) = FileEntry(
        path = path,
        name = path.substringAfterLast('/'),
        type = if (bytes == null) EntryType.DIRECTORY else EntryType.FILE,
        size = bytes?.size?.toLong() ?: 0,
        modifiedAt = Instant.fromEpochSeconds(1_800_000_000),
        mode = 420,
    )
}

class FakeProcess(stdinEnabled: Boolean, private val onOom: () -> Unit = {}) : RunningProcess {
    private val out = Pipe()
    private val err = Pipe()
    private val input = Pipe()
    private val exit = CompletableDeferred<Int>()

    override val stdout: InputStream = out.reader
    override val stderr: InputStream = err.reader
    override val stdin: OutputStream? = if (stdinEnabled) input.writer else null

    override suspend fun awaitExit(): Int = exit.await()

    override fun detach() {
        out.end()
        err.end()
    }

    fun kill(code: Int) {
        if (exit.complete(code)) {
            out.end()
            err.end()
            input.end()
        }
    }

    suspend fun run(command: ExecCommand) {
        val script = (command as? ExecCommand.Shell)?.script ?: (command as ExecCommand.Argv).args.joinToString(" ")
        var code = 0

        for (step in script.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (exit.isCompleted) return
            val word = step.substringBefore(' ')
            val rest = step.substringAfter(' ', "")
            when (word) {
                "echo" -> out.write("$rest\n".encodeToByteArray())
                "err" -> err.write("$rest\n".encodeToByteArray())
                "bytes" -> out.write(ByteArray(rest.toInt()) { 'x'.code.toByte() })
                "exit" -> {
                    code = rest.toInt()
                    break
                }
                "sleep" -> {
                    exit.await()
                    return
                }
                "oom" -> {
                    // like the kernel: the counter moves just before the process dies.
                    delay(100)
                    onOom()
                    code = 137
                    break
                }
                "cat" -> runInterruptible { input.reader.copyTo(OutputStreamTo(out)) }
                else -> error("Unknown fake command `$word`")
            }
        }

        kill(code)
    }

    private class OutputStreamTo(private val pipe: Pipe) : OutputStream() {
        override fun write(b: Int) = pipe.write(byteArrayOf(b.toByte()))

        override fun write(b: ByteArray, off: Int, len: Int) = pipe.write(b.copyOfRange(off, off + len))
    }
}

/** A blocking in-memory pipe; unlike the JDK's piped streams it has no thread affinity. */
class Pipe {
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var ended = false

    fun write(bytes: ByteArray) {
        if (!ended && bytes.isNotEmpty()) queue.put(bytes)
    }

    fun end() {
        if (!ended) {
            ended = true
            queue.put(EOF)
        }
    }

    val reader: InputStream = object : InputStream() {
        private var current: ByteArray = ByteArray(0)
        private var position = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)

            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (position >= current.size) {
                if (finished) return -1
                val next = queue.take()
                if (next === EOF) {
                    finished = true
                    queue.put(EOF)
                    return -1
                }
                current = next
                position = 0
            }

            val count = minOf(len, current.size - position)
            current.copyInto(b, off, position, position + count)
            position += count

            return count
        }

        override fun close() = end()
    }

    val writer: OutputStream = object : OutputStream() {
        override fun write(b: Int) = this@Pipe.write(byteArrayOf(b.toByte()))

        override fun write(b: ByteArray, off: Int, len: Int) = this@Pipe.write(b.copyOfRange(off, off + len))

        override fun close() = end()
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}

/** A pages role that records what it was given, so a test can see the snapshot that left the sandbox. */
class FakePublisher(private val limits: SiteLimits = SiteLimits(100, 1024 * 1024, 4 * 1024 * 1024)) : SitePublisher {
    val published: MutableMap<String, Map<String, String>> = ConcurrentHashMap()
    val sites: MutableMap<String, PublishedSite> = ConcurrentHashMap()

    override suspend fun limits(): SiteLimits = limits

    override suspend fun publish(site: String, snapshot: Path): PublishedSite {
        val files = Files.walk(snapshot).use { paths ->
            paths.filter { it.isRegularFile() }.toList()
                .associate { it.relativeTo(snapshot).joinToString("/") to it.readText() }
        }
        published[site] = files
        val info = PublishedSite(
            site,
            "https://$site.example.test",
            "r${published.size}",
            files.size,
            files.values.sumOf { it.length.toLong() },
            Instant.parse("2026-09-13T12:00:00Z"),
            hasIndex = "index.html" in files,
        )
        sites[site] = info

        return info
    }

    override suspend fun published(site: String): PublishedSite? = sites[site]

    override suspend fun unpublish(site: String) {
        sites.remove(site)
        published.remove(site)
    }
}

class FakeHomes(var freeBytes: Long = Long.MAX_VALUE) : HomeStore {
    /** Homes that exist on "disk", with their size; `open` adds one, `destroy` removes it. */
    val disks = ConcurrentHashMap<SandboxId, Int>()

    override suspend fun recover() = Unit

    /** Home volumes whose name holds no sandbox id, as a server from before ids left them. */
    val unrecognized = CopyOnWriteArrayList<String>()

    override suspend fun list(): List<SandboxId> = disks.keys.toList()

    override suspend fun unrecognized(): List<String> = unrecognized.toList()

    override suspend fun sizeMb(sandbox: SandboxId): Int? = disks[sandbox]

    val open: MutableSet<SandboxId> = ConcurrentHashMap.newKeySet()
    val destroyed = CopyOnWriteArrayList<SandboxId>()

    override suspend fun open(sandbox: SandboxId, sizeMb: Int): HomeMount {
        open += sandbox
        disks.putIfAbsent(sandbox, sizeMb)

        return HomeMount("home-$sandbox")
    }

    override suspend fun close(sandbox: SandboxId) {
        open -= sandbox
    }

    override suspend fun destroy(sandbox: SandboxId) {
        destroyed += sandbox
        disks.remove(sandbox)
    }

    override suspend fun hostFreeBytes(): Long = freeBytes
}

class FakeEnforcer : NetworkEnforcer {
    val applied = ConcurrentHashMap<SandboxId, NetworkPolicy>()

    @Volatile
    var holds = true

    override suspend fun install(network: SandboxNetwork) = Unit

    override suspend fun verifyAndRepair(): Boolean = holds

    override suspend fun apply(sandbox: SandboxId, address: String, policy: NetworkPolicy) {
        check(policy != NetworkPolicy.None) { "none is never applied to an address" }
        applied[sandbox] = policy
    }

    override suspend fun release(sandbox: SandboxId) {
        applied.remove(sandbox)
    }
}

/** A clock a test moves by hand. */
class MutableClock(var current: Instant = Instant.parse("2026-09-13T12:00:00Z")) : Clock {
    override fun now(): Instant = current

    fun advance(by: Duration) {
        current += by
    }
}
