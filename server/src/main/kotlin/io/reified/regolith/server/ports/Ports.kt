package io.reified.regolith.server.ports

import io.reified.regolith.server.domain.Exec
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.FileEntry
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlin.time.Instant

/*
 * the application talks to the outside world only through these interfaces. each has one
 * production adapter; tests use fakes. an adapter never calls back into the application.
 */

/** The bridge every session joins, as the runtime created it. */
data class SandboxNetwork(val name: String, val bridge: String, val subnet: String, val gateway: String)

/**
 * A home ready to mount: the volume the runtime attaches at the sandbox home, and the block device
 * behind it when there is one, so the runtime can throttle its I/O.
 */
data class HomeMount(val volume: String, val device: String? = null)

/**
 * A started session's container and its address on [SandboxNetwork]; the address is null while the
 * session is detached from every network, which is what the `none` policy means.
 */
data class SessionHandle(val container: String, val address: String?)

data class ExecSpec(
    val id: ExecId,
    val command: ExecCommand,
    val cwd: String,
    val env: Map<String, String>,
    val stdin: Boolean,
)

/** The local end of a command running inside a session. */
interface RunningProcess {
    val stdout: InputStream
    val stderr: InputStream

    /** Null when the exec was started without stdin. */
    val stdin: OutputStream?

    suspend fun awaitExit(): Int

    /**
     * Lets go of the command: closes the streams and ends the local client. The command itself may
     * keep running — only a signal or the end of the session stops it.
     */
    fun detach()
}

enum class Signal { TERM, KILL }

/** How often a session's limits have bitten since it started: kernel OOM kills and refused forks. */
data class LimitEvents(val oomKills: Long, val forksRefused: Long)

/** Containers: sessions, the commands inside them, and their files. */
interface SandboxRuntime {
    /** Removes sessions left by a previous run of this namespace and makes sure the network exists. */
    suspend fun initialize(): SandboxNetwork

    /** Makes sure [image] is on the host, pulling it if it is not; a session start would do it anyway. */
    suspend fun pull(image: String)

    /**
     * Starts the session on [image], attached to the sandbox network, or detached when its policy is
     * `none`. The image is the one the sandbox's choice resolved to, which is whatever the server
     * offers for it now.
     */
    suspend fun startSession(sandbox: Sandbox, home: HomeMount, image: String): SessionHandle

    /** Attaches a running session to the sandbox network and returns its new address. */
    suspend fun attachNetwork(sandbox: SandboxId): String

    /**
     * Detaches a running session from every network. With no interface left it reaches nothing,
     * Docker's embedded DNS resolver included — which host rules alone could not stop, because that
     * resolver forwards queries from the daemon's own namespace.
     */
    suspend fun detachNetwork(sandbox: SandboxId)

    /** Removes the session's container, which ends every process in it; a no-op for no session. */
    suspend fun stopSession(sandbox: SandboxId)

    suspend fun exec(sandbox: SandboxId, spec: ExecSpec): RunningProcess

    /** CPU time the whole session has used since it started, in microseconds, from its cgroup. */
    suspend fun cpuMicros(sandbox: SandboxId): Long

    /** The session's limit counters, from its cgroup. */
    suspend fun limitEvents(sandbox: SandboxId): LimitEvents

    /** Signals every process started by one exec, detached descendants included. */
    suspend fun signal(sandbox: SandboxId, exec: ExecId, signal: Signal)

    suspend fun stat(sandbox: SandboxId, path: String): FileEntry

    /** Whether the sandbox user can open [path] for reading, which its mode alone does not decide. */
    suspend fun readable(sandbox: SandboxId, path: String): Boolean

    suspend fun list(sandbox: SandboxId, path: String, maxEntries: Int): List<FileEntry>

    suspend fun read(sandbox: SandboxId, path: String, sink: OutputStream, maxBytes: Long)

    /** Replaces [path] atomically, creating missing parent directories. */
    suspend fun write(sandbox: SandboxId, path: String, source: InputStream, maxBytes: Long): FileEntry

    suspend fun delete(sandbox: SandboxId, path: String, recursive: Boolean)

    /** Every regular file under a directory, with its size; the pre-check before a publish copies it. */
    suspend fun tree(sandbox: SandboxId, path: String, maxFiles: Int): List<TreeFile>

    /** Copies a directory out of the session into [destination] on the server, as a snapshot. */
    suspend fun copyOut(sandbox: SandboxId, path: String, destination: Path)
}

/** One file of a sandbox directory: where it is, relative to the directory, and how large. */
data class TreeFile(val path: String, val size: Long)

/** What the pages role allows a site to hold; it owns these, the control plane only respects them. */
data class SiteLimits(val maxFiles: Int, val maxFileBytes: Long, val maxSiteBytes: Long)

/** A site as the pages role reports it. */
data class PublishedSite(
    val site: String,
    val url: String,
    val release: String,
    val files: Int,
    val bytes: Long,
    val publishedAt: Instant,
)

/**
 * The public role, reached over HTTP. It is optional: without one configured the publish endpoints
 * answer `not_implemented`, and nothing else in the server changes.
 */
interface SitePublisher {
    /** The caps the pages role enforces, so a snapshot that cannot fit is refused before it is copied. */
    suspend fun limits(): SiteLimits

    /** Uploads the files under [snapshot] as one release and makes it the one visitors see. */
    suspend fun publish(site: String, snapshot: Path): PublishedSite

    suspend fun published(site: String): PublishedSite?

    suspend fun unpublish(site: String)
}

/** Persistent homes, each a fixed-size filesystem of its own. */
interface HomeStore {
    /** Releases attachments a previous run left behind; called after the runtime removed its sessions. */
    suspend fun recover()

    /** Creates the home on first use; the size of an existing home never changes. */
    suspend fun open(sandbox: SandboxId, sizeMb: Int): HomeMount

    /** Detaches a home after its session ended, keeping every file. */
    suspend fun close(sandbox: SandboxId)

    suspend fun destroy(sandbox: SandboxId)

    /** Free bytes left for homes on the host, for the storage guard. */
    suspend fun hostFreeBytes(): Long

    /** Every sandbox this namespace holds a home for, whether or not a record still claims it. */
    suspend fun list(): List<SandboxId>

    /**
     * Homes of this namespace whose name holds no sandbox id — left by a server from before ids, or
     * made by hand. No record can claim one and nothing here can open, adopt or delete it.
     */
    suspend fun unrecognized(): List<String>

    /** Size of the sandbox's existing home in MB, or null when it has none. */
    suspend fun sizeMb(sandbox: SandboxId): Int?
}

/** The host-level network policy under every session. */
interface NetworkEnforcer {
    /**
     * Installs the platform floor for [network] and proves it holds from a throwaway session,
     * with a live control on a refused address. Throws when either fails; the server then refuses
     * to start.
     */
    suspend fun install(network: SandboxNetwork)

    /**
     * Reads the installed rules back and restores any drift. Returns false only when the floor could
     * not be restored.
     */
    suspend fun verifyAndRepair(): Boolean

    /**
     * Applies one sandbox's policy to the traffic of [address], on top of the floor, replacing what was
     * applied before. An address with no policy applied reaches nothing, so a session is confined from
     * the moment it has an address. [policy] is never `none`: that session has no address.
     */
    suspend fun apply(sandbox: SandboxId, address: String, policy: NetworkPolicy)

    suspend fun release(sandbox: SandboxId)
}

/** Durable records of sandboxes and execs, and where exec output is written. */
interface StateStore {
    suspend fun sandboxes(): List<Sandbox>

    suspend fun save(sandbox: Sandbox)

    /** Deletes the sandbox record together with every exec record and output file. */
    suspend fun delete(sandbox: SandboxId)

    suspend fun execs(sandbox: SandboxId): List<Exec>

    suspend fun save(exec: Exec)

    suspend fun deleteExec(sandbox: SandboxId, id: ExecId)

    fun outputFile(sandbox: SandboxId, id: ExecId): Path
}
