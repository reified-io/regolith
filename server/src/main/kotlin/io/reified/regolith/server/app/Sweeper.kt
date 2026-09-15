package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.ports.HomeStore
import io.reified.regolith.server.ports.NetworkEnforcer
import io.reified.regolith.server.ports.SandboxNetwork
import kotlin.time.Clock

/**
 * Applies lifecycles: stops sessions past their maximum lifetime or idle window and deletes
 * sandboxes past retention.
 *
 * Idle means no lease — no running exec and no file transfer. A background server started by a
 * finished command holds no lease, so it ends with the idle session; that is the price of not
 * letting one command keep a slot forever.
 */
class Sweeper(
    private val sandboxes: Sandboxes,
    private val sessions: Sessions,
    private val clock: Clock,
) {

    suspend fun tick() {
        val now = clock.now()

        for ((id, session) in sessions.snapshot()) {
            val sandbox = sandboxes.find(id) ?: continue
            if (now >= session.expiresAt) {
                log.info { "Session reached its maximum lifetime: sandbox=[$id]" }
                sandboxes.stop(id, StopReason.SESSION_EXPIRED)
            } else if (!session.busy && now - session.lastActiveAt >= sandbox.lifecycle.idleStop) {
                sessions.stopIfIdle(id, StopReason.IDLE)
            }
        }

        for (sandbox in sandboxes.all()) {
            if (sessions.get(sandbox.id) == null && now >= sandbox.deleteAfter) {
                log.info { "Sandbox past retention: sandbox=[${sandbox.id}]" }
                sandboxes.delete(sandbox.id)
            }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

/**
 * Keeps the network floor in place. Installing it is a startup precondition; afterwards drift is
 * repaired in place, and only a floor that cannot be restored latches the service off, because the
 * sessions behind a missing floor would run unconfined.
 */
class NetworkGuard(
    private val enforcer: NetworkEnforcer,
    private val sandboxes: Sandboxes,
    private val sessions: Sessions,
    private val health: Health,
) {

    @Volatile
    private var latched = false

    suspend fun install(network: SandboxNetwork) {
        enforcer.install(network)
        health.report(Health.NETWORK, ok = true)
    }

    suspend fun tick() {
        if (latched) return
        val holds = try {
            enforcer.verifyAndRepair()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Network policy check failed" }
            false
        }
        if (holds) return
        latched = true
        health.report(Health.NETWORK, ok = false, detail = "The network policy could not be restored; sandboxes are stopped")
        log.error { "Network policy lost and not restorable; stopping every session" }
        for (id in sessions.snapshot().keys) sandboxes.stop(id, StopReason.POLICY_FAILED)
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
