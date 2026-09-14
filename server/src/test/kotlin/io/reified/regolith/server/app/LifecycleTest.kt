package io.reified.regolith.server.app

import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecOutcome
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.support.TestServer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LifecycleTest {
    private val name = SandboxName.parse("swept")

    @Test
    fun `an idle session stops, a busy one does not`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandboxes.getOrCreate(name, SandboxRequest(lifecycle = LifecycleRequest(idleStop = 60.seconds))).first
            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)

            server.clock.advance(2.minutes)
            server.sweeper.tick()
            assertTrue(server.sessions.get(name) != null, "a running command keeps its session")

            server.execs.cancel(name, exec.id)
            server.execs.await(name, exec.id, 10.seconds)
            server.clock.advance(2.minutes)
            server.sweeper.tick()
            assertNull(server.sessions.get(name))
            assertTrue(server.sandboxes.find(name) != null, "stopping a session keeps the sandbox")
        }
    }

    @Test
    fun `the maximum lifetime interrupts even a busy session`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandboxes.getOrCreate(name, SandboxRequest(lifecycle = LifecycleRequest(idleStop = 5.minutes, maxSession = 10.minutes))).first
            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)

            server.clock.advance(11.minutes)
            server.sweeper.tick()

            assertEquals(ExecOutcome.Interrupted(StopReason.SESSION_EXPIRED), server.execs.await(name, exec.id, 10.seconds).outcome)
        }
    }

    @Test
    fun `retention deletes an unused sandbox, and an ephemeral one right after its session`() = runBlocking {
        TestServer().use { server ->
            val kept = SandboxName.parse("kept")
            server.sandboxes.getOrCreate(kept, SandboxRequest(lifecycle = LifecycleRequest(retain = 3.days)))
            val ephemeral = SandboxName.parse("ephemeral")
            val sandbox = server.sandboxes.getOrCreate(ephemeral, SandboxRequest(lifecycle = LifecycleRequest(idleStop = 60.seconds, retain = 0.days))).first
            server.sandboxes.start(ephemeral)
            assertEquals(ExecOutcome.Exited(0), server.execs.await(ephemeral, server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("echo hi")), null).id, 10.seconds).outcome)

            server.clock.advance(2.minutes)
            server.sweeper.tick()
            server.sweeper.tick()
            assertNull(server.sandboxes.find(ephemeral))
            assertTrue(server.sandboxes.find(kept) != null)

            server.clock.advance(4.days)
            server.sweeper.tick()
            assertNull(server.sandboxes.find(kept))
            assertEquals(listOf(ephemeral, kept), server.homes.destroyed)
        }
    }

    @Test
    fun `a session burning cpu with nothing of its own running is stopped and says why`() = runBlocking {
        TestServer().use { server ->
            server.sandboxes.getOrCreate(name, SandboxRequest())
            server.sandboxes.start(name)
            val guard = CpuGuard(server.sessions, server.runtime, server.clock, limit = 60.seconds)

            server.guardTick(guard, cpuMicros = 0)
            server.guardTick(guard, cpuMicros = 40_000_000)
            assertTrue(server.sessions.get(name) != null, "40 unattended seconds are under the limit")
            server.guardTick(guard, cpuMicros = 70_000_000)

            assertNull(server.sessions.get(name))
            assertEquals(StopReason.CPU_LIMIT, server.sessions.lastEnd(name)?.reason)
        }
    }

    @Test
    fun `cpu spent by commands or around activity is never counted`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandboxes.getOrCreate(name, SandboxRequest()).first
            server.sandboxes.start(name)
            val guard = CpuGuard(server.sessions, server.runtime, server.clock, limit = 60.seconds)
            server.guardTick(guard, cpuMicros = 0)

            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)
            server.guardTick(guard, cpuMicros = 500_000_000)
            server.execs.cancel(name, exec.id)
            server.execs.await(name, exec.id, 10.seconds)

            // the command ended after the last tick, so the interval it ended in is not unattended either.
            server.guardTick(guard, cpuMicros = 900_000_000)
            server.guardTick(guard, cpuMicros = 950_000_000)
            assertTrue(server.sessions.get(name) != null, "only 50 seconds were unattended")
        }
    }

    private suspend fun TestServer.guardTick(guard: CpuGuard, cpuMicros: Long) {
        clock.advance(30.seconds)
        runtime.cpu[name] = cpuMicros
        guard.tick()
    }

    @Test
    fun `a network floor that cannot be restored latches and stops every session`() = runBlocking {
        TestServer().use { server ->
            server.sandboxes.getOrCreate(name, SandboxRequest())
            server.sandboxes.start(name)
            val guard = NetworkGuard(server.enforcer, server.sandboxes, server.sessions, server.health)

            server.enforcer.holds = false
            guard.tick()
            server.enforcer.holds = true
            guard.tick()

            assertNull(server.sessions.get(name))
            assertFalse(server.health.healthy, "the latch does not clear on its own")
        }
    }
}
