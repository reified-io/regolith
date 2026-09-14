package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.domain.Exec
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.ExecOutcome
import io.reified.regolith.server.domain.ExitCause
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.domain.Metadata
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxLayout
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.output.Frame
import io.reified.regolith.server.output.FrameKind
import io.reified.regolith.server.output.OutputLog
import io.reified.regolith.server.output.OutputLogWriter
import io.reified.regolith.server.ports.ExecSpec
import io.reified.regolith.server.ports.LimitEvents
import io.reified.regolith.server.ports.RunningProcess
import io.reified.regolith.server.ports.SandboxRuntime
import io.reified.regolith.server.ports.Signal
import io.reified.regolith.server.ports.StateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a caller asks to run; `null` takes the default. */
data class ExecRequest(
    val command: ExecCommand,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val timeout: Duration? = null,
    val stdin: Boolean = false,
)

/** Output read from one exec: frames, the offset to continue from, and whether that is all there will be. */
data class OutputSlice(val exec: Exec, val frames: List<Frame>, val nextOffset: Long, val complete: Boolean)

/**
 * Commands and their recorded output.
 *
 * Each exec streams stdout and stderr into its output log while it runs and keeps a lease on its
 * session. It ends in exactly one outcome: its own exit, the timeout, a cancel, or the session
 * ending under it — an interrupt reason set before the session is stopped wins over the exit code
 * the dying process reports.
 */
class Execs(
    private val store: StateStore,
    private val sessions: Sessions,
    private val runtime: SandboxRuntime,
    private val config: ServerConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {

    private data class Progress(val end: Long, val truncated: Boolean, val finished: Boolean)

    private class Running(
        val process: RunningProcess,
        val writer: OutputLogWriter,
        val lease: Sessions.Lease,
        initial: Exec,
        val limitsAtStart: Deferred<LimitEvents?>,
    ) {
        @Volatile
        var exec: Exec = initial

        @Volatile
        var cancelled = false

        @Volatile
        var interruptedBy: StopReason? = null

        @Volatile
        var stdinOpen = initial.stdin

        val progress = MutableStateFlow(Progress(0, truncated = false, finished = false))
        val done = CompletableDeferred<Exec>()
        val writeLock = Mutex()
        val stdinLock = Mutex()
    }

    private val running = ConcurrentHashMap<ExecId, Running>()
    private val startLocks = KeyedLocks<SandboxName>()

    /** Closes records a previous run left unfinished; their commands died with its sessions. */
    suspend fun recover(sandboxes: List<Sandbox>) {
        for (sandbox in sandboxes) {
            for (exec in store.execs(sandbox.name).filter { !it.finished }) {
                val file = store.outputFile(sandbox.name, exec.id)
                val end = withContext(Dispatchers.IO) { OutputLog.validEnd(file) }
                store.save(
                    exec.copy(
                        finishedAt = clock.now(),
                        outcome = ExecOutcome.Interrupted(StopReason.SERVER_RESTARTED),
                        outputEnd = end,
                    ),
                )
            }
        }
    }

    suspend fun start(sandbox: Sandbox, request: ExecRequest, idempotencyKey: String?): Exec = startLocks.withLock(sandbox.name) {
        idempotencyKey?.let { key ->
            requireValid(key.length in 1..MAX_KEY_CHARS) { "The idempotency key is 1-$MAX_KEY_CHARS characters" }
            find(sandbox.name, key)?.let { return@withLock it }
        }
        val limits = config.limits
        val timeout = request.timeout ?: config.defaults.execTimeout
        requireValid(timeout > Duration.ZERO && timeout <= limits.maxExecTimeout) {
            "timeoutSeconds is between 1 and ${limits.maxExecTimeout.inWholeSeconds}"
        }
        Metadata.requireEnv(request.env)
        val active = running.values.count { it.exec.sandbox == sandbox.name }

        if (active >= limits.maxExecsPerSandbox) {
            throw RegolithError.Busy("Sandbox `${sandbox.name}` already runs $active commands")
        }

        val exec = Exec(
            id = ExecId.random(),
            sandbox = sandbox.name,
            command = request.command,
            cwd = resolvePath(request.cwd ?: SandboxLayout.HOME),
            env = request.env,
            timeout = timeout,
            stdin = request.stdin,
            idempotencyKey = idempotencyKey,
            startedAt = clock.now(),
        )
        prune(sandbox.name)
        val lease = sessions.acquire(sandbox)

        try {
            store.save(exec)
            val file = store.outputFile(sandbox.name, exec.id)
            val out = withContext(Dispatchers.IO) {
                Files.createDirectories(file.parent)
                BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
            }
            val writer = OutputLogWriter(out, limits.maxOutputBytes, TAIL_BYTES)
            val process = try {
                runtime.exec(sandbox.name, ExecSpec(exec.id, exec.command, exec.cwd, exec.env, exec.stdin))
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { out.close() }
                store.deleteExec(sandbox.name, exec.id)
                throw e
            }
            // read alongside the command rather than before it, so a start never waits on a counter.
            val limitsAtStart = scope.async { limitEventsOrNull(sandbox.name) }
            val run = Running(process, writer, lease, exec, limitsAtStart)
            running[exec.id] = run
            scope.launch { supervise(run) { out.close() } }
            exec
        } catch (e: Exception) {
            lease.close()
            throw e
        }
    }

    suspend fun get(name: SandboxName, id: ExecId): Exec =
        running[id]?.takeIf { it.exec.sandbox == name }?.let(::view)
            ?: store.execs(name).firstOrNull { it.id == id }
            ?: throw RegolithError.NotFound("Exec `$id` does not exist in sandbox `$name`")

    fun stdinOpen(id: ExecId): Boolean = running[id]?.stdinOpen ?: false

    /** Recent execs, newest first. */
    suspend fun list(name: SandboxName): List<Exec> {
        val live = running.values.filter { it.exec.sandbox == name }.associate { it.exec.id to view(it) }

        return (store.execs(name).filter { it.id !in live } + live.values).sortedByDescending { it.startedAt }
    }

    /** Waits up to [wait] for the exec to finish and returns it either way. */
    suspend fun await(name: SandboxName, id: ExecId, wait: Duration): Exec {
        val run = running[id]?.takeIf { it.exec.sandbox == name } ?: return get(name, id)

        return withTimeoutOrNull(wait) { run.done.await() } ?: view(run)
    }

    /**
     * Reads frames from [offset]. When nothing is available yet and the exec still runs, waits up to
     * [wait] for more output or the end.
     */
    suspend fun read(name: SandboxName, id: ExecId, offset: Long, maxBytes: Int, wait: Duration): OutputSlice {
        val run = running[id]?.takeIf { it.exec.sandbox == name }
        val (exec, limit, finished) = if (run != null) {
            if (wait > Duration.ZERO) {
                withTimeoutOrNull(wait) { run.progress.first { it.end > offset || it.finished } }
            }
            val progress = run.progress.value
            Triple(view(run), progress.end, progress.finished)
        } else {
            val exec = get(name, id)
            Triple(exec, exec.outputEnd, true)
        }
        val file = store.outputFile(name, id)
        val frames = withContext(Dispatchers.IO) { OutputLog.read(file, offset, limit, maxBytes) }
        val next = frames.lastOrNull()?.end ?: offset
        val complete = finished && next == limit

        return OutputSlice(if (complete && run != null) run.done.await() else exec, frames, next, complete)
    }

    suspend fun writeStdin(name: SandboxName, id: ExecId, bytes: ByteArray, close: Boolean) {
        val run = running[id]?.takeIf { it.exec.sandbox == name }
        val stdin = run?.process?.stdin
        conflictUnless(run != null) { "Exec `$id` is not running" }
        conflictUnless(stdin != null) { "Exec `$id` was started without stdin" }
        run.stdinLock.withLock {
            conflictUnless(run.stdinOpen) { "The stdin of exec `$id` is already closed" }
            val written = withContext(Dispatchers.IO) {
                try {
                    if (bytes.isNotEmpty()) {
                        stdin.write(bytes)
                        stdin.flush()
                    }
                    if (close) stdin.close()
                    true
                } catch (_: IOException) {
                    false
                }
            }
            conflictUnless(written) { "Exec `$id` no longer reads its stdin" }
            if (close) run.stdinOpen = false
        }
        sessions.touch(name)
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun conflictUnless(condition: Boolean, message: () -> String) {
        contract { returns() implies condition }
        if (!condition) throw RegolithError.Conflict(message())
    }

    /** Asks the exec to stop; returns at once, and cancelling a finished exec changes nothing. */
    suspend fun cancel(name: SandboxName, id: ExecId): Exec {
        val run = running[id]?.takeIf { it.exec.sandbox == name } ?: return get(name, id)
        run.cancelled = true
        scope.launch { terminate(run) }

        return view(run)
    }

    /** Marks every running exec of [name] as interrupted; call before the session is stopped. */
    fun interrupt(name: SandboxName, reason: StopReason) {
        running.values.filter { it.exec.sandbox == name }.forEach { run ->
            if (run.interruptedBy == null) run.interruptedBy = reason
        }
    }

    /** Waits for the execs of a stopped session to finish recording. */
    suspend fun awaitNone(name: SandboxName) {
        withTimeoutOrNull(SETTLE) { running.values.filter { it.exec.sandbox == name }.forEach { it.done.await() } }
    }

    private suspend fun supervise(run: Running, closeLog: () -> Unit) {
        val exec = run.exec
        val pumps = listOf(FrameKind.STDOUT to run.process.stdout, FrameKind.STDERR to run.process.stderr).map { (kind, stream) ->
            scope.launch(Dispatchers.IO) { pump(run, kind, stream) }
        }
        var timedOut = false
        val code = withTimeoutOrNull(exec.timeout) { run.process.awaitExit() } ?: run {
            timedOut = true
            terminate(run)
            withTimeoutOrNull(KILL_WAIT) { run.process.awaitExit() }
        }
        // a detached child inherits the pipes and can hold them open forever, so output gets a short
        // window to drain after the command itself has ended.
        withTimeoutOrNull(DRAIN) { pumps.joinAll() }
        run.process.detach()
        pumps.joinAll()

        val outcome = when {
            run.interruptedBy != null -> ExecOutcome.Interrupted(checkNotNull(run.interruptedBy))
            run.cancelled -> ExecOutcome.Cancelled
            timedOut || code == null -> ExecOutcome.TimedOut
            code == 0 -> ExecOutcome.Exited(0)
            else -> ExecOutcome.Exited(code, exitCause(run))
        }
        val finished = withContext(NonCancellable) {
            val (end, truncated) = run.writeLock.withLock {
                run.writer.finish()
                closeLog()
                run.writer.end to run.writer.truncated
            }
            val done = exec.copy(finishedAt = clock.now(), outcome = outcome, outputEnd = end, outputTruncated = truncated)
            store.save(done)
            run.exec = done
            run.stdinOpen = false
            done
        }
        run.done.complete(finished)
        run.progress.value = Progress(finished.outputEnd, finished.outputTruncated, finished = true)
        running.remove(exec.id)
        run.lease.close()
        log.info { "Exec finished: sandbox=[${exec.sandbox}] exec=[${exec.id}] outcome=[$outcome]" }
    }

    /**
     * The limit that explains a failed command, if one bit while it ran. Concurrent commands in one session
     * share the counters, so each failure that overlapped the event reports it — for all of them the session
     * was out of memory or processes.
     */
    private suspend fun exitCause(run: Running): ExitCause? {
        val before = run.limitsAtStart.await() ?: return null
        val after = limitEventsOrNull(run.exec.sandbox) ?: return null

        return when {
            after.oomKills > before.oomKills -> ExitCause.OOM_KILLED
            after.forksRefused > before.forksRefused -> ExitCause.PIDS_LIMITED
            else -> null
        }
    }

    private suspend fun limitEventsOrNull(name: SandboxName): LimitEvents? = try {
        runtime.limitEvents(name)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Reading limit events failed: sandbox=[$name]" }
        null
    }

    private suspend fun pump(run: Running, kind: FrameKind, stream: InputStream) {
        val buffer = ByteArray(READ_CHUNK)

        try {
            while (true) {
                val count = runInterruptible { stream.read(buffer) }
                if (count < 0) break
                run.writeLock.withLock {
                    run.writer.append(kind, buffer, count)
                    run.progress.value = Progress(run.writer.end, run.writer.truncated, finished = false)
                }
            }
        } catch (_: IOException) {
            // the stream was closed by detach; whatever arrived before that is already recorded.
        }
    }

    private suspend fun terminate(run: Running) {
        val exec = run.exec
        runCatching { runtime.signal(exec.sandbox, exec.id, Signal.TERM) }
            .onFailure { log.warn(it) { "SIGTERM failed: exec=[${exec.id}]" } }

        if (withTimeoutOrNull(GRACE) { run.process.awaitExit() } == null) {
            runCatching { runtime.signal(exec.sandbox, exec.id, Signal.KILL) }
                .onFailure { log.warn(it) { "SIGKILL failed: exec=[${exec.id}]" } }
        }
    }

    private suspend fun find(name: SandboxName, key: String): Exec? =
        running.values.firstOrNull { it.exec.sandbox == name && it.exec.idempotencyKey == key }?.let(::view)
            ?: store.execs(name).firstOrNull { it.idempotencyKey == key }

    private suspend fun prune(name: SandboxName) {
        val finished = store.execs(name).filter { it.finished }.sortedByDescending { it.startedAt }
        for (old in finished.drop(config.limits.execsRetained)) store.deleteExec(name, old.id)
    }

    private fun view(run: Running): Exec {
        val progress = run.progress.value

        return run.exec.copy(outputEnd = progress.end, outputTruncated = progress.truncated)
    }

    private companion object {
        val log = KotlinLogging.logger {}
        const val MAX_KEY_CHARS = 128
        const val READ_CHUNK = 16 * 1024
        const val TAIL_BYTES = 256 * 1024
        val GRACE = 5.seconds
        val KILL_WAIT = 5.seconds
        val DRAIN = 2.seconds
        val SETTLE = 30.seconds
    }
}

/** Resolves a sandbox path: relative paths start at the home, and a NUL can never reach a command. */
fun resolvePath(raw: String): String {
    requireValid(raw.isNotBlank()) { "The path is empty" }
    requireValid(raw.length <= MAX_PATH_CHARS) { "The path is longer than $MAX_PATH_CHARS characters" }
    requireValid('\u0000' !in raw) { "The path contains NUL" }

    return if (raw.startsWith('/')) raw else "${SandboxLayout.HOME}/$raw"
}

private const val MAX_PATH_CHARS = 4096
