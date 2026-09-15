package io.reified.regolith.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Body of `POST /v1/sandboxes/{name}/execs`. Exactly one of [shell] and [argv] must be set.
 *
 * A relative [cwd] resolves against the sandbox home. With [stdin] unset the command reads an
 * empty input, so a program waiting for a keypress ends instead of hanging until its timeout.
 */
@Serializable
public data class ExecRequest(
    val shell: String? = null,
    val argv: List<String>? = null,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int? = null,
    val stdin: Boolean = false,
)

@Serializable
public enum class ExecStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("finished")
    FINISHED,
}

@Serializable
public enum class OutcomeType {
    /** The command exited on its own; see [ExecOutcome.exitCode]. */
    @SerialName("exited")
    EXITED,

    @SerialName("timed_out")
    TIMED_OUT,

    @SerialName("cancelled")
    CANCELLED,

    /** The session ended under the command; see [ExecOutcome.reason]. */
    @SerialName("interrupted")
    INTERRUPTED,
}

/**
 * How an exec ended. For [OutcomeType.EXITED], [reason] names a limit the session hit while the command
 * ran, when one explains the exit: [Reasons.OOM_KILLED] or [Reasons.PIDS_LIMITED]. For
 * [OutcomeType.INTERRUPTED] it says why the session ended. Accept values you do not know.
 */
@Serializable
public data class ExecOutcome(
    val type: OutcomeType,
    val exitCode: Int? = null,
    val reason: String? = null,
)

/**
 * Every reason this version of the server sends, in [ExecOutcome.reason] and [SessionEndInfo.reason].
 * Kept as strings rather than an enum, like [ErrorCodes], so a client never fails to decode a reason a
 * newer server adds.
 */
public object Reasons {
    /** The kernel killed a process for exceeding the session's memory limit while the command ran. */
    public const val OOM_KILLED: String = "oom_killed"

    /** The session hit its process limit while the command ran, so a fork failed. */
    public const val PIDS_LIMITED: String = "pids_limited"

    /** The session was stopped through the API. */
    public const val STOPPED: String = "stopped"

    /** No command or transfer for the sandbox's idle window. */
    public const val IDLE: String = "idle"

    /** The session reached its maximum lifetime. */
    public const val SESSION_EXPIRED: String = "session_expired"

    /** Reclaimed while idle, to start another sandbox's session. */
    public const val CAPACITY: String = "capacity"

    /** Processes left behind burned more CPU than allowed while no command ran. */
    public const val CPU_LIMIT: String = "cpu_limit"

    /** The network floor could not be restored. */
    public const val POLICY_FAILED: String = "policy_failed"

    /** The sandbox was deleted under the command. */
    public const val SANDBOX_DELETED: String = "sandbox_deleted"

    /** The server restarted under the command. */
    public const val SERVER_RESTARTED: String = "server_restarted"
}

/**
 * An exec as the server reports it. [outputEnd] is the offset just past the output recorded so
 * far; [outputTruncated] means output beyond the server's cap was dropped from the middle.
 */
@Serializable
public data class ExecInfo(
    val id: String,
    val shell: String? = null,
    val argv: List<String>? = null,
    val cwd: String? = null,
    val status: ExecStatus,
    val outcome: ExecOutcome? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val outputEnd: Long,
    val outputTruncated: Boolean,
    val stdinOpen: Boolean,
)

/** Recent execs of one sandbox, newest first. */
@Serializable
public data class ExecPage(
    val execs: List<ExecInfo>,
)

@Serializable
public enum class OutputKind {
    @SerialName("stdout")
    STDOUT,

    @SerialName("stderr")
    STDERR,

    /** Output dropped to stay within the cap; see [OutputFrame.droppedBytes]. */
    @SerialName("gap")
    GAP,
}

/**
 * One piece of recorded output. [end] is the offset to resume from after this frame.
 *
 * Frames of one stream keep their order. Stdout and stderr are separate pipes, so frames of the two
 * interleave in the order the server read them, which can differ slightly from the order a command
 * wrote them.
 */
@Serializable
public data class OutputFrame(
    val kind: OutputKind,
    val text: String = "",
    val droppedBytes: Long? = null,
    val end: Long,
)

/**
 * One page of `GET .../output`. [complete] means the exec has finished and every frame up to
 * [nextOffset] has been delivered: nothing more will ever appear.
 *
 * [exec] is the exec as the server saw it while it built this page, so a client that follows a
 * command sees its status, its outcome and its exit code without asking again between pages.
 */
@Serializable
public data class OutputPage(
    val frames: List<OutputFrame>,
    val nextOffset: Long,
    val complete: Boolean,
    val exec: ExecInfo,
)
