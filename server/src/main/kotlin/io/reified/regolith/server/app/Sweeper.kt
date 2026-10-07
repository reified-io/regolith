package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.ports.HomeStore
import io.reified.regolith.server.ports.NetworkEnforcer
import io.reified.regolith.server.ports.SandboxNetwork
import io.reified.regolith.server.ports.SandboxRuntime
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Applies lifecycles: ends sessions whose container has exited, stops sessions past their maximum
 * lifetime or idle window, takes down sites past their term, and deletes sandboxes past retention.
 *
 * Idle means no lease — no running exec and no file transfer. A background server started by a
 * finished command holds no lease, so it ends with the idle session; that is the price of not
 * letting one command keep a slot forever.
 *
 * Retention takes only the home of a sandbox whose site is up: the address was handed to people who
 * never use the sandbox, so its being unused says nothing about the site, which has a term of its own.
 * The record stays as long as the site, so a site never outlives what knows its label.
 */
class Sweeper(
    private val sandboxes: Sandboxes,
    private val sessions: Sessions,
    private val execs: Execs,
    private val sites: SitePublishing,
    private val clock: Clock,
) {

    suspend fun tick() {
        val now = clock.now()

        for ((id, session) in sessions.snapshot()) {
            val sandbox = sandboxes.find(id) ?: continue
            if (execs.endIfExited(id, session)) continue
            if (now >= session.expiresAt) {
                log.info { "Session reached its maximum lifetime: sandbox=[$id]" }
                sandboxes.stop(id, StopReason.SESSION_EXPIRED)
            } else if (!session.busy && now - session.lastActiveAt >= sandbox.lifecycle.idleStop) {
                sessions.stopIfIdle(id, StopReason.IDLE)
            }
        }

        // without a pages role nothing can be taken down, and saying so every tick would say nothing new
        if (sites.available) {
            for (sandbox in sandboxes.all()) {
                val site = sandbox.site ?: continue
                if (now >= site.until) expire(sandbox)
            }
        }

        for (sandbox in sandboxes.all()) {
            if (sessions.get(sandbox.id) != null || now < sandbox.deleteAfter) continue
            if (sandbox.site == null || sandbox.homeReleasedAt == null) retain(sandbox)
        }
    }

    // a home that cannot be taken apart stays for the next tick; the sandboxes after it are still swept.
    private suspend fun retain(sandbox: Sandbox) {
        try {
            if (sandbox.site == null) {
                log.info { "Sandbox past retention: sandbox=[${sandbox.id}]" }
                sandboxes.delete(sandbox.id)
            } else {
                sandboxes.releaseHome(sandbox.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Sandbox past retention could not be swept: sandbox=[${sandbox.id}]" }
        }
    }

    // a pages role that cannot be reached leaves the site up and the record with it; the next tick
    // tries again, and the rest of the sweep goes on meanwhile.
    private suspend fun expire(sandbox: Sandbox) {
        try {
            sites.expire(sandbox.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Site past its term could not be taken down: sandbox=[${sandbox.id}] site=[${sandbox.site?.label}]" }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

/**
 * Keeps the network floor in place. Installing it is a startup precondition; afterwards drift is
 * repaired in place. A floor that cannot be repaired stops every session, because the sessions behind
 * a missing floor would run unconfined, and is then installed again the way it was at startup: the
 * sandbox network itself may be gone — pruned as unused between sessions — and the runtime makes it
 * again. Only a floor that cannot be re-established either latches the service off.
 */
class NetworkGuard(
    private val runtime: SandboxRuntime,
    private val enforcer: NetworkEnforcer,
    private val sandboxes: Sandboxes,
    private val sessions: Sessions,
    private val health: Health,
) {

    @Volatile
    private var latched = false

    @Volatile
    private var network: SandboxNetwork? = null

    suspend fun install(network: SandboxNetwork) {
        enforcer.install(network)
        this.network = network
        health.report(Health.NETWORK, ok = true)
    }

    suspend fun tick() {
        if (latched || repaired()) return
        // nothing may run behind a floor that is not in place, and nothing may start while it is put back.
        health.report(Health.NETWORK, ok = false, detail = "The network floor is being restored; sessions are refused")
        log.error { "Network policy lost; stopping every session" }
        if (!stopAll()) return
        // a start already past the health check finishes first, and none begins on a network without a floor.
        if (sessions.withoutStarts { reinstalled() }) return
        latched = true
        health.report(Health.NETWORK, ok = false, detail = "The network policy could not be restored; sandboxes are stopped")
        log.error { "Network policy not restorable; sandboxes are stopped until the server is restarted" }
        // the start that finished first may have published a session since the floor was found gone.
        stopAll()
    }

    /**
     * False when a session is still live afterwards: its policy would be rendered into the floor put back
     * under it, for an address another session may be given, so the next tick starts over instead.
     */
    private suspend fun stopAll(): Boolean {
        var stopped = true

        for (id in sessions.snapshot().keys) {
            try {
                sandboxes.stop(id, StopReason.POLICY_FAILED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessions.get(id) == null) continue
                log.error(e) { "Session would not stop: sandbox=[$id]" }
                stopped = false
            }
        }

        return stopped
    }

    private suspend fun repaired(): Boolean = try {
        enforcer.verifyAndRepair()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "Network policy check failed" }
        false
    }

    // the bridge the floor hangs off belongs to the sandbox network, so a floor that cannot be put back
    // on it is installed on whatever bridge the network has once the runtime has made sure it exists.
    private suspend fun reinstalled(): Boolean = try {
        val fresh = runtime.network()
        if (fresh != network) log.warn { "Sandbox network made again: bridge=[${network?.bridge}] now=[${fresh.bridge}]" }
        install(fresh)
        log.info { "Network floor restored: bridge=[${fresh.bridge}]" }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "Installing the network floor again failed" }
        false
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

/** Refuses new sessions and uploads while the host is short of space for homes. */
class StorageGuard(
    private val homes: HomeStore,
    private val health: Health,
    private val minFreeBytes: Long,
) {

    suspend fun tick() {
        val free = homes.hostFreeBytes()
        val ok = free >= minFreeBytes
        health.report(Health.STORAGE, ok, if (ok) null else "The host has $free bytes free, below the $minFreeBytes reserve")
    }
}
