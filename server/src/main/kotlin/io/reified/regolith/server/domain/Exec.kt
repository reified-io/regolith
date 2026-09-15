package io.reified.regolith.server.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

@Serializable
sealed interface ExecCommand {
    /** A script for the sandbox shell, the way agents write commands. */
    @Serializable
    @SerialName("shell")
    data class Shell(val script: String) : ExecCommand {
        init {
            requireValid(script.isNotBlank()) { "The shell script is empty" }
            requireValid(script.length <= MAX_COMMAND_CHARS) { "The shell script is longer than $MAX_COMMAND_CHARS characters" }
        }
    }

    /** A program and its arguments, run without a shell. */
    @Serializable
    @SerialName("argv")
    data class Argv(val args: List<String>) : ExecCommand {
        init {
            requireValid(args.isNotEmpty() && args.first().isNotBlank()) { "argv needs a program" }
            requireValid(args.sumOf { it.length } <= MAX_COMMAND_CHARS) { "argv is longer than $MAX_COMMAND_CHARS characters" }
        }
    }

    companion object {
        const val MAX_COMMAND_CHARS = 64_000
    }
}

/** One command run in a sandbox, from start to outcome. */
@Serializable
data class Exec(
    val id: ExecId,
    val sandbox: SandboxId,
    val command: ExecCommand,
    val cwd: String,
    val env: Map<String, String>,
    val timeout: Duration,
    val stdin: Boolean,
    val idempotencyKey: String?,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val outcome: ExecOutcome? = null,
    val outputEnd: Long = 0,
    val outputTruncated: Boolean = false,
) {

    val finished: Boolean get() = outcome != null
}

@Serializable
sealed interface ExecOutcome {
    /** The command exited; [cause] names a session limit that explains a failure, when one does. */
    @Serializable
    @SerialName("exited")
    data class Exited(val code: Int, val cause: ExitCause? = null) : ExecOutcome

    @Serializable
    @SerialName("timed_out")
    data object TimedOut : ExecOutcome

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : ExecOutcome

    @Serializable
    @SerialName("interrupted")
    data class Interrupted(val reason: StopReason) : ExecOutcome
}

/** A session limit hit while a command ran, read from the session's cgroup counters. */
@Serializable
enum class ExitCause {
    /** The memory limit: the kernel killed a process in the session. */
    OOM_KILLED,

    /** The process limit: the session refused to start another process. */
    PIDS_LIMITED,
}

/**
 * Why a session ended. An exec interrupted by the end of its session records the same reason; the ones
 * that only ever end an idle session — [IDLE], [CAPACITY], [CPU_LIMIT] — never interrupt an exec.
 */
@Serializable
enum class StopReason {
    /** Stopped through the API. */
    STOPPED,

    /** No lease for the sandbox's idle window. */
    IDLE,

    /** The session reached its maximum lifetime. */
    SESSION_EXPIRED,

    /** Reclaimed, while idle, to make room for another sandbox's session. */
    CAPACITY,

    /** Burned more CPU than allowed while nothing of its own was running. */
    CPU_LIMIT,

    /** The network floor could not be restored. */
    POLICY_FAILED,
    SANDBOX_DELETED,
    SERVER_RESTARTED,
}

/** How and when a sandbox's last session ended. */
data class SessionEnd(val reason: StopReason, val at: Instant)

enum class EntryType { FILE, DIRECTORY, SYMLINK, OTHER }

/** A filesystem entry inside a sandbox, as the runtime reports it. */
data class FileEntry(
    val path: String,
    val name: String,
    val type: EntryType,
    val size: Long,
    val modifiedAt: Instant,
    val mode: Int,
)
