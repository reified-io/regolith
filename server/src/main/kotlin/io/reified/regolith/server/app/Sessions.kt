package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.domain.ImageCatalog
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SessionEnd
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.domain.SandboxName
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
    inner class Lease internal constructor(val name: SandboxName, val session: Session) : AutoCloseable {
        private val released = java.util.concurrent.atomic.AtomicBoolean()

        override fun close() {
            if (released.compareAndSet(false, true)) {
                session.leases.decrementAndGet()
                session.lastActiveAt = clock.now()
            }
        }
    }

    private val live = ConcurrentHashMap<SandboxName, Session>()
    private val ends = ConcurrentHashMap<SandboxName, SessionEnd>()
    private val locks = KeyedLocks<SandboxName>()
    private val capacity = Mutex()

    fun get(name: SandboxName): Session? = live[name]

    /** How the sandbox's last session ended, while this server has been running. */
    fun lastEnd(name: SandboxName): SessionEnd? = ends[name]

    /** Drops what is remembered about a deleted sandbox, so a new one of the same name starts clean. */
    fun forget(name: SandboxName) {
        ends.remove(name)
    }

    fun snapshot(): Map<SandboxName, Session> = live.toMap()

    /** Starts the session if needed and leases it; close the lease when the work is done. */
    suspend fun acquire(sandbox: Sandbox): Lease = locks.withLock(sandbox.name) {
        val session = live[sandbox.name] ?: start(sandbox)
        session.leases.incrementAndGet()
        session.lastActiveAt = clock.now()
        Lease(sandbox.name, session)
    }

    suspend fun <T> withLease(sandbox: Sandbox, action: suspend (Session) -> T): T =
        acquire(sandbox).use { action(it.session) }

    /** Marks activity that holds no lease, such as a write to a running command's stdin. */
    fun touch(name: SandboxName) {
        live[name]?.lastActiveAt = clock.now()
    }

    /**
     * Replaces the network policy of a running session; a stopped sandbox picks it up at its next start.
     * Every transition passes through a moment with no rules for the address, which the floor rejects,
     * so a change never opens more than either policy allows.
     */
    suspend fun applyNetwork(sandbox: Sandbox) = locks.withLock(sandbox.name) {
        val session = live[sandbox.name] ?: return@withLock
        val name = sandbox.name
        val attached = session.handle.address
        val detached = sandbox.network == NetworkPolicy.None

        when {
            detached && attached != null -> {
                network.release(name)
                runtime.detachNetwork(name)
                session.handle = session.handle.copy(address = null)
            }
            !detached && attached == null -> {
                val address = runtime.attachNetwork(name)
                session.handle = session.handle.copy(address = address)
                network.apply(name, address, sandbox.network)
            }
            attached != null -> network.apply(name, attached, sandbox.network)
        }
    }

    /** Ends the session, if any. The caller interrupts the execs in it first. */
    suspend fun stop(name: SandboxName, reason: StopReason) = locks.withLock(name) { stopLocked(name, reason) }

    /** Ends the session only if it is still idle, for reclaiming, the idle sweep and the CPU guard. */
    suspend fun stopIfIdle(name: SandboxName, reason: StopReason): Boolean = locks.withLock(name) {
        val session = live[name] ?: return@withLock false
        if (session.busy) return@withLock false
        stopLocked(name, reason)
        true
    }

    suspend fun stopAll(reason: StopReason) {
        for (name in live.keys.toList()) stop(name, reason)
    }

    private suspend fun start(sandbox: Sandbox): Session = capacity.withLock {
        health.require(Health.NETWORK)
        health.require(Health.STORAGE)
        // before anything is reclaimed for it: a session with no image to start on never begins.
        val image = images.resolve(sandbox.imagePolicy)
        if (live.size >= maxSessions) reclaimOne()
        val home = homes.open(sandbox.name, sandbox.resources.homeMb)
        val handle = try {
            runtime.startSession(sandbox, home, image)
        } catch (e: Exception) {
            withContext(NonCancellable) { homes.close(sandbox.name) }
            throw e
        }

        try {
            handle.address?.let { network.apply(sandbox.name, it, sandbox.network) }
        } catch (e: CancellationException) {
            discard(sandbox.name)
            throw e
        } catch (e: Exception) {
            discard(sandbox.name)
            log.error(e) { "Network policy could not be applied: sandbox=[${sandbox.name}]" }
            throw RegolithError.Unavailable("The sandbox network policy could not be applied")
        }

        val now = clock.now()
        Session(handle, now, now + sandbox.lifecycle.maxSession, image).also {
            live[sandbox.name] = it
            log.info { "Session started: sandbox=[${sandbox.name}] image=[$image]" }
        }
    }

    /** Removes a container that was started but never published as a session. */
    private suspend fun discard(name: SandboxName) = withContext(NonCancellable) {
        runtime.stopSession(name)
        homes.close(name)
    }

    private suspend fun reclaimOne() {
        val candidates = live.entries.filter { !it.value.busy }.sortedBy { it.value.lastActiveAt }

        for ((name, _) in candidates) {
            if (stopIfIdle(name, StopReason.CAPACITY)) {
                log.info { "Session reclaimed for capacity: sandbox=[$name]" }
                return
            }
        }

        throw RegolithError.CapacityExhausted("All $maxSessions sessions are busy; try again shortly")
    }

    private suspend fun stopLocked(name: SandboxName, reason: StopReason) {
        live.remove(name) ?: return
        ends[name] = SessionEnd(reason, clock.now())
        withContext(NonCancellable) {
            runCatching { network.release(name) }.onFailure { log.warn(it) { "Network release failed: sandbox=[$name]" } }
            runtime.stopSession(name)
            homes.close(name)
        }
        log.info { "Session stopped: sandbox=[$name] reason=[$reason]" }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
