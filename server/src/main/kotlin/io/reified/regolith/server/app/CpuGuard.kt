package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.ports.SandboxRuntime
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Instant

/**
 * Stops sessions that burn CPU while nothing of their own is running.
 *
 * A process detached from a finished command costs one command and then runs for free: no timeout applies
 * to it, and the idle sweep cannot tell a miner at 100% from an empty container, because neither holds a
 * lease. So every live session's cgroup counter is read on each tick, and only the CPU spent over an
 * interval with no activity at all — no lease at the tick, no lease taken or released since the last one —
 * counts as unattended. A command's own cost is never counted: it was asked for, and its timeout bounds it.
 * Once a session's unattended total passes the limit, it is stopped if it is still idle.
 */
class CpuGuard(
    private val sessions: Sessions,
    private val runtime: SandboxRuntime,
    private val clock: Clock,
    private val limit: Duration,
) {

    private data class Burn(val session: Instant, val micros: Long, val measuredAt: Instant, val unattended: Duration)

    private val burns = ConcurrentHashMap<SandboxId, Burn>()

    /** Consecutive ticks a session could not be read, by the session they were counted against. */
    private val misses = ConcurrentHashMap<SandboxId, Pair<Instant, Int>>()

    suspend fun tick() {
        val live = sessions.snapshot()
        burns.keys.retainAll(live.keys)
        misses.keys.retainAll(live.keys)

        for ((id, session) in live) {
            // taken before the counter: activity that ends while it is read then marks the next interval too.
            val now = clock.now()
            val micros = try {
                runtime.cpuMicros(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Reading cpu usage failed: sandbox=[$id]" }
                unreadable(id, session)
                continue
            }
            misses.remove(id)
            val previous = burns[id]?.takeIf { it.session == session.startedAt }
            val unattended = when {
                previous == null -> Duration.ZERO
                session.busy || session.lastActiveAt >= previous.measuredAt || micros < previous.micros -> previous.unattended
                else -> previous.unattended + (micros - previous.micros).microseconds
            }
            burns[id] = Burn(session.startedAt, micros, now, unattended)
            if (unattended > limit && sessions.stopIfIdle(id, StopReason.CPU_LIMIT)) {
                burns.remove(id)
                log.warn { "Session stopped for unattended cpu: sandbox=[$id] unattended=[$unattended] limit=[$limit]" }
            }
        }
    }

    /**
     * The counter is read by a process started inside the session, so leftovers that fill its pids limit hide
     * it from this guard entirely while they burn. A session that stays unreadable across two ticks with
     * nothing of the caller's running is stopped; one whose container has exited is the sweep's to end.
     */
    private suspend fun unreadable(id: SandboxId, session: Sessions.Session) {
        val previous = misses[id]?.takeIf { it.first == session.startedAt }?.second ?: 0
        misses[id] = session.startedAt to previous + 1
        if (previous + 1 < UNREADABLE_TICKS || !running(id)) return

        if (sessions.stopIfIdle(id, StopReason.UNRESPONSIVE)) {
            misses.remove(id)
            log.warn { "Session stopped, unreadable while idle: sandbox=[$id]" }
        }
    }

    private suspend fun running(id: SandboxId): Boolean = try {
        runtime.isRunning(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Checking the session container failed: sandbox=[$id]" }
        true
    }

    private companion object {
        val log = KotlinLogging.logger {}
        const val UNREADABLE_TICKS = 2
    }
}
