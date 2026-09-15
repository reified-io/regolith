package io.reified.regolith.server.support

import io.reified.regolith.server.domain.EntryType
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.FileEntry
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.ports.ExecSpec
import io.reified.regolith.server.ports.HomeMount
import io.reified.regolith.server.ports.HomeStore
import io.reified.regolith.server.ports.LimitEvents
import io.reified.regolith.server.ports.PublishedSite
import io.reified.regolith.server.ports.SiteLimits
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
    val sessions: MutableSet<SandboxName> = ConcurrentHashMap.newKeySet()
    val attached: MutableSet<SandboxName> = ConcurrentHashMap.newKeySet()
    val started = CopyOnWriteArrayList<SandboxName>()

    /** The image each session started on, and every image the server asked to have on the host. */
    val startedOn = ConcurrentHashMap<SandboxName, String>()
    val pulled = CopyOnWriteArrayList<String>()

    /** CPU microseconds each session reports; a test moves it by hand. */
    val cpu = ConcurrentHashMap<SandboxName, Long>()
    private val processes = ConcurrentHashMap<ExecId, Pair<SandboxName, FakeProcess>>()
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun initialize() = SandboxNetwork("test-sandboxes", "br-test", "172.30.0.0/16", "172.30.0.1")

    override suspend fun pull(image: String) {
        pulled += image
    }

    override suspend fun startSession(sandbox: Sandbox, home: HomeMount, image: String): SessionHandle {
        sessions += sandbox.name
        started += sandbox.name
        startedOn[sandbox.name] = image
        if (sandbox.network == NetworkPolicy.None) return SessionHandle("test-${sandbox.name}", null)
        attached += sandbox.name

        return SessionHandle("test-${sandbox.name}", "172.30.0.${sessions.size + 1}")
    }

    override suspend fun attachNetwork(name: SandboxName): String {
        attached += name

        return "172.30.1.${attached.size}"
    }

    override suspend fun detachNetwork(name: SandboxName) {
        attached -= name
    }

    override suspend fun stopSession(name: SandboxName) {
        sessions -= name
        attached -= name
        processes.values.filter { it.first == name }.forEach { it.second.kill(137) }
    }

    override suspend fun exec(name: SandboxName, spec: ExecSpec): RunningProcess {
        check(name in sessions) { "No session for $name" }
        val process = FakeProcess(spec.stdin) { limits.merge(name, LimitEvents(1, 0)) { a, b -> LimitEvents(a.oomKills + b.oomKills, a.forksRefused) } }
        processes[spec.id] = name to process
        scope.launch { process.run(spec.command) }

        return process
    }

    override suspend fun cpuMicros(name: SandboxName): Long = cpu[name] ?: 0

    /** Limit counters each session reports; a fake command `oom` bumps them the way the kernel would. */
    val limits = ConcurrentHashMap<SandboxName, LimitEvents>()

    override suspend fun limitEvents(name: SandboxName): LimitEvents = limits[name] ?: LimitEvents(0, 0)

    override suspend fun signal(name: SandboxName, exec: ExecId, signal: Signal) {
        processes[exec]?.second?.kill(if (signal == Signal.TERM) 143 else 137)
    }

    /** Puts a file in a session without going through the api, for a test that needs one to exist. */
    fun place(name: SandboxName, path: String, text: String) {
        files["$name:$path"] = text.toByteArray()
    }

    /** What a session holds at [path], or null. */
    fun content(name: SandboxName, path: String): String? = files["$name:$path"]?.decodeToString()

    override suspend fun stat(name: SandboxName, path: String): FileEntry {
        val bytes = files["$name:$path"]
        val isDirectory = files.keys.any { it.startsWith("$name:${path.trimEnd('/')}/") }
        if (bytes == null && !isDirectory) throw RegolithError.NotFound("`$path` does not exist")

        return entry(path, bytes)
    }

    override suspend fun list(name: SandboxName, path: String, maxEntries: Int): List<FileEntry> {
        val prefix = "$name:${path.trimEnd('/')}/"

        return files.entries.filter { it.key.startsWith(prefix) && '/' !in it.key.removePrefix(prefix) }
            .map { entry(it.key.substringAfter(':'), it.value) }
            .sortedBy { it.name }
    }

    override suspend fun read(name: SandboxName, path: String, sink: OutputStream, maxBytes: Long) {
        sink.write(files["$name:$path"] ?: throw RegolithError.NotFound("`$path` does not exist"))
    }

    override suspend fun write(name: SandboxName, path: String, source: InputStream, maxBytes: Long): FileEntry {
        val bytes = runInterruptible { source.readAllBytes() }
        if (bytes.size > maxBytes) throw RegolithError.TooLarge("Files are limited to $maxBytes bytes")
        files["$name:$path"] = bytes

        return entry(path, bytes)
    }

    override suspend fun delete(name: SandboxName, path: String, recursive: Boolean) {
        files.remove("$name:$path") ?: throw RegolithError.NotFound("`$path` does not exist")
    }

    override suspend fun tree(name: SandboxName, path: String, maxFiles: Int): List<TreeFile> =
        files.filterKeys { it.startsWith("$name:${path.trimEnd('/')}/") }
            .map { (key, bytes) -> TreeFile(key.substringAfter("$name:${path.trimEnd('/')}/"), bytes.size.toLong()) }

    override suspend fun copyOut(name: SandboxName, path: String, destination: Path) {
        for (file in tree(name, path, Int.MAX_VALUE)) {
            val target = destination.resolve(file.path)
            Files.createDirectories(target.parent)
            Files.write(target, files.getValue("$name:${path.trimEnd('/')}/${file.path}"))
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
        val info = PublishedSite(site, "https://$site.example.test", "r${published.size}", files.size, files.values.sumOf { it.length.toLong() }, Instant.parse("2026-09-13T12:00:00Z"))
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
    val disks = ConcurrentHashMap<SandboxName, Int>()

    override suspend fun recover() = Unit

    override suspend fun list(): List<SandboxName> = disks.keys.toList()

    override suspend fun sizeMb(name: SandboxName): Int? = disks[name]

    val open: MutableSet<SandboxName> = ConcurrentHashMap.newKeySet()
    val destroyed = CopyOnWriteArrayList<SandboxName>()

    override suspend fun open(name: SandboxName, sizeMb: Int): HomeMount {
        open += name
        disks.putIfAbsent(name, sizeMb)

        return HomeMount("home-$name")
    }

    override suspend fun close(name: SandboxName) {
        open -= name
    }

    override suspend fun destroy(name: SandboxName) {
        destroyed += name
        disks.remove(name)
    }

    override suspend fun hostFreeBytes(): Long = freeBytes
}

class FakeEnforcer : NetworkEnforcer {
    val applied = ConcurrentHashMap<SandboxName, NetworkPolicy>()

    @Volatile
    var holds = true

    override suspend fun install(network: SandboxNetwork) = Unit

    override suspend fun verifyAndRepair(): Boolean = holds

    override suspend fun apply(name: SandboxName, address: String, policy: NetworkPolicy) {
        check(policy != NetworkPolicy.None) { "none is never applied to an address" }
        applied[name] = policy
    }

    override suspend fun release(name: SandboxName) {
        applied.remove(name)
    }
}

/** A clock a test moves by hand. */
class MutableClock(var current: Instant = Instant.parse("2026-09-13T12:00:00Z")) : Clock {
    override fun now(): Instant = current

    fun advance(by: Duration) {
        current += by
    }
}
