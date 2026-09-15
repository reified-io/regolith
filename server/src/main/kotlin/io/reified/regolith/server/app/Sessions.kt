package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.domain.ImageCatalog
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SessionEnd
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.ports.HomeStore
import io.reified.regolith.server.ports.NetworkEnforcer
import io.reified.regolith.server.ports.SandboxRuntime
import io.reified.regolith.server.ports.SessionHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Running sessions: at most one per sandbox, at most `maxSessions` in total.
 *
 * A session is published only after its network policy is in place, and nothing runs in it before
 * that: the container starts with an idle entrypoint, and commands arrive only through [acquire].
 * Work holds a [Lease] for as long as it runs; a session with a lease is never reclaimed or
 * idle-stopped, only stopped explicitly or at its maximum lifetime.
 *
 * A sandbox's image is resolved here, once per session: a sandbox that follows the server starts on
 * the image the server offers now, and the running session keeps the one it started with.
 */
class Sessions(
    private val runtime: SandboxRuntime,
    private val homes: HomeStore,
    private val network: NetworkEnforcer,
    private val health: Health,
    private val clock: Clock,
    private val maxSessions: Int,
    private val images: ImageCatalog,
) {

    class Session internal constructor(
        handle: SessionHandle,
        val startedAt: Instant,
        val expiresAt: Instant,
        val image: String,
    ) {

        @Volatile
        var handle: SessionHandle = handle
            internal set

        @Volatile
        var lastActiveAt: Instant = startedAt
            internal set

        internal val leases = AtomicInteger()

        val busy: Boolean get() = leases.get() > 0
    }

    /** Keeps a session from being reclaimed while work runs in it. */
    inner class Lease internal constructor(val sandbox: SandboxId, val session: Session) : AutoCloseable {
        private val released = java.util.concurrent.atomic.AtomicBoolean()

        override fun close() {
            if (released.compareAndSet(false, true)) {
                session.leases.decrementAndGet()
                session.lastActiveAt = clock.now()
            }
        }
    }

    private val live = ConcurrentHashMap<SandboxId, Session>()
    private val ends = ConcurrentHashMap<SandboxId, SessionEnd>()
    private val locks = KeyedLocks<SandboxId>()
    private val capacity = Mutex()

    fun get(sandbox: SandboxId): Session? = live[sandbox]

    /** How the sandbox's last session ended, while this server has been running. */
    fun lastEnd(sandbox: SandboxId): SessionEnd? = ends[sandbox]

    /** Drops what is remembered about a deleted sandbox, so a new one of the same sandbox starts clean. */
    fun forget(sandbox: SandboxId) {
        ends.remove(sandbox)
    }

    fun snapshot(): Map<SandboxId, Session> = live.toMap()

    /** Starts the session if needed and leases it; close the lease when the work is done. */
    suspend fun acquire(sandbox: Sandbox): Lease = locks.withLock(sandbox.id) {
        val session = live[sandbox.id] ?: start(sandbox)
        session.leases.incrementAndGet()
        session.lastActiveAt = clock.now()
        Lease(sandbox.id, session)
    }

    suspend fun <T> withLease(sandbox: Sandbox, action: suspend (Session) -> T): T =
        acquire(sandbox).use { action(it.session) }

    /** Marks activity that holds no lease, such as a write to a running command's stdin. */
    fun touch(sandbox: SandboxId) {
        live[sandbox]?.lastActiveAt = clock.now()
    }

    /**
     * Replaces the network policy of a running session; a stopped sandbox picks it up at its next start.
     * Every transition passes through a moment with no rules for the address, which the floor rejects,
     * so a change never opens more than either policy allows.
     */
    suspend fun applyNetwork(sandbox: Sandbox) = locks.withLock(sandbox.id) {
        val session = live[sandbox.id] ?: return@withLock
        val attached = session.handle.address
        val detached = sandbox.network == NetworkPolicy.None

        when {
            detached && attached != null -> {
                network.release(sandbox.id)
                runtime.detachNetwork(sandbox.id)
                session.handle = session.handle.copy(address = null)
            }
            !detached && attached == null -> {
                val address = runtime.attachNetwork(sandbox.id)
                session.handle = session.handle.copy(address = address)
                network.apply(sandbox.id, address, sandbox.network)
            }
            attached != null -> network.apply(sandbox.id, attached, sandbox.network)
        }
    }

    /** Ends the session, if any. The caller interrupts the execs in it first. */
    suspend fun stop(sandbox: SandboxId, reason: StopReason) = locks.withLock(sandbox) { stopLocked(sandbox, reason) }

    /** Ends the session only if it is still idle, for reclaiming, the idle sweep and the CPU guard. */
    suspend fun stopIfIdle(sandbox: SandboxId, reason: StopReason): Boolean = locks.withLock(sandbox) {
        val session = live[sandbox] ?: return@withLock false
        if (session.busy) return@withLock false
        stopLocked(sandbox, reason)
        true
    }

    /** Ends [session] only while it is still the sandbox's session, so one started since is never taken for it. */
    suspend fun stopIfCurrent(sandbox: SandboxId, session: Session, reason: StopReason): Boolean = locks.withLock(sandbox) {
        if (live[sandbox] !== session) return@withLock false
        stopLocked(sandbox, reason)
        true
    }

    suspend fun stopAll(reason: StopReason) {
        for (sandbox in live.keys.toList()) stop(sandbox, reason)
    }

    private suspend fun start(sandbox: Sandbox): Session = capacity.withLock {
        health.require(Health.NETWORK)
        health.require(Health.STORAGE)
        // before anything is reclaimed for it: a session with no image to start on never begins.
        val image = images.resolve(sandbox.imagePolicy)
        if (live.size >= maxSessions) reclaimOne()
        val home = homes.open(sandbox.id, sandbox.resources.homeMb)
        val handle = try {
            runtime.startSession(sandbox, home, image)
        } catch (e: Exception) {
            withContext(NonCancellable) { homes.close(sandbox.id) }
            throw e
        }

        try {
            handle.address?.let { network.apply(sandbox.id, it, sandbox.network) }
        } catch (e: CancellationException) {
            discard(sandbox.id)
            throw e
        } catch (e: Exception) {
            discard(sandbox.id)
            log.error(e) { "Network policy could not be applied: sandbox=[${sandbox.id}]" }
            throw RegolithError.Unavailable("The sandbox network policy could not be applied")
        }

        val now = clock.now()
        Session(handle, now, now + sandbox.lifecycle.maxSession, image).also {
            live[sandbox.id] = it
            log.info { "Session started: sandbox=[${sandbox.id}] image=[$image]" }
        }
    }

    /** Removes a container that was started but never published as a session. */
    private suspend fun discard(sandbox: SandboxId) = withContext(NonCancellable) {
        runtime.stopSession(sandbox)
        homes.close(sandbox)
    }

    private suspend fun reclaimOne() {
        val candidates = live.entries.filter { !it.value.busy }.sortedBy { it.value.lastActiveAt }

        for ((sandbox, _) in candidates) {
            if (stopIfIdle(sandbox, StopReason.CAPACITY)) {
                log.info { "Session reclaimed for capacity: sandbox=[$sandbox]" }
                return
            }
        }

        throw RegolithError.CapacityExhausted("All $maxSessions sessions are busy; try again shortly")
    }

    private suspend fun stopLocked(sandbox: SandboxId, reason: StopReason) {
        live.remove(sandbox) ?: return
        ends[sandbox] = SessionEnd(reason, clock.now())
        withContext(NonCancellable) {
            runCatching { network.release(sandbox) }.onFailure { log.warn(it) { "Network release failed: sandbox=[$sandbox]" } }
            runtime.stopSession(sandbox)
            homes.close(sandbox)
        }
        log.info { "Session stopped: sandbox=[$sandbox] reason=[$reason]" }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
