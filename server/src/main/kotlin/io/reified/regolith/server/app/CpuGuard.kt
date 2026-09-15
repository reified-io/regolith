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

    suspend fun tick() {
        val live = sessions.snapshot()
        burns.keys.retainAll(live.keys)

        for ((id, session) in live) {
            // taken before the counter: activity that ends while it is read then marks the next interval too.
            val now = clock.now()
            val micros = try {
                runtime.cpuMicros(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Reading cpu usage failed: sandbox=[$id]" }
                continue
            }
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

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
