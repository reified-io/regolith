package io.reified.regolith.server.app

import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecOutcome
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.support.TestServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LifecycleTest {

    @Test
    fun `an idle session stops, a busy one does not`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("swept", SandboxRequest(lifecycle = LifecycleRequest(idleStop = 60.seconds)))
            val name = sandbox.id
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
            val sandbox = server.sandbox("swept", SandboxRequest(lifecycle = LifecycleRequest(idleStop = 5.minutes, maxSession = 10.minutes)))
            val name = sandbox.id
            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)

            server.clock.advance(11.minutes)
            server.sweeper.tick()

            assertEquals(ExecOutcome.Interrupted(StopReason.SESSION_EXPIRED), server.execs.await(name, exec.id, 10.seconds).outcome)
        }
    }

    @Test
    fun `a command whose container dies under it is interrupted, and the next one gets a fresh session`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("killed")
            val name = sandbox.id
            val running = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)

            server.runtime.exitContainer(name)

            assertEquals(ExecOutcome.Interrupted(StopReason.CONTAINER_EXITED), server.execs.await(name, running.id, 10.seconds).outcome)
            assertEquals(StopReason.CONTAINER_EXITED, server.sessions.lastEnd(name)?.reason)
            val next = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("echo hi")), null)
            assertEquals(ExecOutcome.Exited(0), server.execs.await(name, next.id, 10.seconds).outcome)
            assertEquals(2, server.runtime.started.count { it == name }, "the next command runs in a new container")
        }
    }

    @Test
    fun `a command that takes its container down with it is interrupted, though the daemon says so late`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("slow-to-stop")
            val name = sandbox.id
            val running = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)

            server.runtime.exitContainerAfter(name, code = 143, lag = 300.milliseconds)

            assertEquals(ExecOutcome.Interrupted(StopReason.CONTAINER_EXITED), server.execs.await(name, running.id, 10.seconds).outcome)
            val next = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("echo hi")), null)
            assertEquals(ExecOutcome.Exited(0), server.execs.await(name, next.id, 10.seconds).outcome)
        }
    }

    @Test
    fun `a command killed by a signal in a container that lives on stays its own failure`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("signaled")
            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("exit 143")), null)

            assertEquals(ExecOutcome.Exited(143), server.execs.await(sandbox.id, exec.id, 10.seconds).outcome)
            assertTrue(server.sessions.get(sandbox.id) != null)
        }
    }

    @Test
    fun `a command started into a container that already exited reports the session ending, not itself`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("dead")
            val name = sandbox.id
            server.sandboxes.start(name)
            server.runtime.exitContainer(name)

            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("echo hi")), null)

            assertEquals(ExecOutcome.Interrupted(StopReason.CONTAINER_EXITED), server.execs.await(name, exec.id, 10.seconds).outcome)
            assertNull(server.sessions.get(name))
        }
    }

    @Test
    fun `a failing command in a live container stays its own failure`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("failing")
            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("exit 7")), null)

            assertEquals(ExecOutcome.Exited(7), server.execs.await(sandbox.id, exec.id, 10.seconds).outcome)
            assertTrue(server.sessions.get(sandbox.id) != null)
        }
    }

    @Test
    fun `a cancel that cannot reach its processes ends the session, and only that command says canceled`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("flooded")
            val name = sandbox.id
            val flood = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)
            val other = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)
            server.runtime.unreachable += name

            server.execs.cancel(name, flood.id)

            assertEquals(ExecOutcome.Canceled, server.execs.await(name, flood.id, 10.seconds).outcome)
            assertEquals(ExecOutcome.Interrupted(StopReason.UNRESPONSIVE), server.execs.await(name, other.id, 10.seconds).outcome)
            assertEquals(StopReason.UNRESPONSIVE, server.sessions.lastEnd(name)?.reason)
        }
    }

    @Test
    fun `a command that cannot start where nothing else runs ends the session as unresponsive`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("full")
            val name = sandbox.id
            server.sandboxes.start(name)
            server.runtime.unreachable += name

            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("echo hi")), null)

            assertEquals(ExecOutcome.Interrupted(StopReason.UNRESPONSIVE), server.execs.await(name, exec.id, 10.seconds).outcome)
            assertEquals(StopReason.UNRESPONSIVE, server.sessions.lastEnd(name)?.reason)
            val next = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("echo hi")), null)
            assertEquals(ExecOutcome.Exited(0), server.execs.await(name, next.id, 10.seconds).outcome)
        }
    }

    @Test
    fun `a command that cannot start beside a running one is only its own failure`() = runBlocking<Unit> {
        TestServer().use { server ->
            val sandbox = server.sandbox("crowded")
            val name = sandbox.id
            val build = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)
            server.runtime.unreachable += name

            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("echo hi")), null)

            assertEquals(126, (server.execs.await(name, exec.id, 10.seconds).outcome as ExecOutcome.Exited).code)
            assertTrue(server.sessions.get(name) != null, "what fills a busy session may be a real build")
            server.runtime.unreachable -= name
            server.execs.cancel(name, build.id)
        }
    }

    @Test
    fun `a timeout that cannot reach its processes ends the session and still says timed out`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("stuck")
            val name = sandbox.id
            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep"), timeout = 1.seconds), null)
            server.runtime.unreachable += name

            assertEquals(ExecOutcome.TimedOut, server.execs.await(name, exec.id, 20.seconds).outcome)
            assertEquals(StopReason.UNRESPONSIVE, server.sessions.lastEnd(name)?.reason)
        }
    }

    @Test
    fun `retention deletes an unused sandbox, and an ephemeral one right after its session`() = runBlocking {
        TestServer().use { server ->
            val kept = server.sandbox("kept", SandboxRequest(lifecycle = LifecycleRequest(retain = 3.days))).id
            val sandbox = server.sandbox("ephemeral", SandboxRequest(lifecycle = LifecycleRequest(idleStop = 60.seconds, retain = 0.days)))
            val ephemeral = sandbox.id
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

    // the link was handed to people who never touch the sandbox: its being unused says nothing about the site
    @Test
    fun `retention takes only the home of a sandbox whose site is up, and the next use starts an empty one`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("author", SandboxRequest(lifecycle = LifecycleRequest(retain = 3.days))).id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>kept</h1>")
            server.sites.publish(id, "dist", until = server.clock.now() + 60.days)
            val site = server.siteOf(id)
            server.sandboxes.stop(id)

            server.clock.advance(4.days)
            server.sweeper.tick()
            server.sweeper.tick()

            val kept = server.sandboxes.require(id)
            assertEquals(server.clock.now(), kept.homeReleasedAt)
            assertEquals(listOf(id), server.homes.destroyed, "the home is released once")
            assertTrue(server.publisher.sites[site] != null, "the site went with the home")
            assertEquals(site, kept.site?.label?.value)

            server.sandboxes.start(id)

            assertNull(server.sandboxes.require(id).homeReleasedAt, "a use after the release still reads as released")
            assertTrue(id in server.homes.open)
        }
    }

    @Test
    fun `a home retention cannot take apart is left for the next tick, and the rest are still swept`() = runBlocking {
        TestServer().use { server ->
            val stuck = server.sandbox("a-stuck", SandboxRequest(lifecycle = LifecycleRequest(retain = 3.days))).id
            server.runtime.place(stuck, "/home/sandbox/dist/index.html", "<h1>stays</h1>")
            server.sites.publish(stuck, "dist", until = server.clock.now() + 60.days)
            server.sandboxes.stop(stuck)
            val kept = server.sandbox("b-kept", SandboxRequest(lifecycle = LifecycleRequest(retain = 3.days))).id
            server.runtime.place(kept, "/home/sandbox/dist/index.html", "<h1>goes</h1>")
            server.sites.publish(kept, "dist", until = server.clock.now() + 60.days)
            server.sandboxes.stop(kept)
            val unused = server.sandbox("c-unused", SandboxRequest(lifecycle = LifecycleRequest(retain = 3.days))).id
            server.homes.stuck += stuck

            server.clock.advance(4.days)
            server.sweeper.tick()

            assertNull(server.sandboxes.require(stuck).homeReleasedAt, "a home that could not go still reads as kept")
            assertEquals(server.clock.now(), server.sandboxes.require(kept).homeReleasedAt)
            assertNull(server.sandboxes.find(unused))

            server.homes.stuck -= stuck
            server.sweeper.tick()
            assertEquals(server.clock.now(), server.sandboxes.require(stuck).homeReleasedAt)
        }
    }

    @Test
    fun `a site past its term is taken down, and retention then takes the sandbox`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("author", SandboxRequest(lifecycle = LifecycleRequest(retain = 3.days))).id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>for a while</h1>")
            server.sites.publish(id, "dist", until = server.clock.now() + 10.days)
            val site = server.siteOf(id)
            server.sandboxes.stop(id)

            server.clock.advance(9.days)
            server.sweeper.tick()
            assertTrue(server.publisher.sites[site] != null, "the site was taken down before its term")

            server.clock.advance(2.days)
            server.sweeper.tick()

            assertNull(server.publisher.sites[site])
            assertNull(server.sandboxes.find(id), "retention left a sandbox whose site and use were both over")
        }
    }

    @Test
    fun `a session burning cpu with nothing of its own running is stopped and says why`() = runBlocking {
        TestServer().use { server ->
            val name = server.sandbox("burner").id
            server.sandboxes.start(name)
            val guard = CpuGuard(server.sessions, server.runtime, server.clock, limit = 60.seconds)

            server.guardTick(name, guard, cpuMicros = 0)
            server.guardTick(name, guard, cpuMicros = 40_000_000)
            assertTrue(server.sessions.get(name) != null, "40 unattended seconds are under the limit")
            server.guardTick(name, guard, cpuMicros = 70_000_000)

            assertNull(server.sessions.get(name))
            assertEquals(StopReason.CPU_LIMIT, server.sessions.lastEnd(name)?.reason)
        }
    }

    @Test
    fun `cpu spent by commands or around activity is never counted`() = runBlocking {
        TestServer().use { server ->
            val sandbox = server.sandbox("busy")
            val name = sandbox.id
            server.sandboxes.start(name)
            val guard = CpuGuard(server.sessions, server.runtime, server.clock, limit = 60.seconds)
            server.guardTick(name, guard, cpuMicros = 0)

            val exec = server.execs.start(sandbox, ExecRequest(ExecCommand.Shell("sleep")), null)
            server.guardTick(name, guard, cpuMicros = 500_000_000)
            server.execs.cancel(name, exec.id)
            server.execs.await(name, exec.id, 10.seconds)

            // the command ended after the last tick, so the interval it ended in is not unattended either.
            server.guardTick(name, guard, cpuMicros = 900_000_000)
            server.guardTick(name, guard, cpuMicros = 950_000_000)
            assertTrue(server.sessions.get(name) != null, "only 50 seconds were unattended")
        }
    }

    @Test
    fun `an idle session the cpu guard cannot read twice is stopped as unresponsive`() = runBlocking {
        TestServer().use { server ->
            val name = server.sandbox("unreadable").id
            server.sandboxes.start(name)
            val guard = CpuGuard(server.sessions, server.runtime, server.clock, limit = 60.seconds)
            server.runtime.unreachable += name

            server.guardTick(name, guard, cpuMicros = 0)
            assertTrue(server.sessions.get(name) != null, "one unreadable tick is not enough")
            server.guardTick(name, guard, cpuMicros = 0)

            assertNull(server.sessions.get(name))
            assertEquals(StopReason.UNRESPONSIVE, server.sessions.lastEnd(name)?.reason)
        }
    }

    @Test
    fun `the cpu guard leaves an exited container to the sweep, which says what happened`() = runBlocking {
        TestServer().use { server ->
            val name = server.sandbox("exited").id
            server.sandboxes.start(name)
            val guard = CpuGuard(server.sessions, server.runtime, server.clock, limit = 60.seconds)
            server.runtime.exitContainer(name)

            repeat(3) { server.guardTick(name, guard, cpuMicros = 0) }
            assertTrue(server.sessions.get(name) != null, "an exited container is not unresponsive")
            server.sweeper.tick()

            assertNull(server.sessions.get(name))
            assertEquals(StopReason.CONTAINER_EXITED, server.sessions.lastEnd(name)?.reason)
        }
    }

    private suspend fun TestServer.guardTick(name: SandboxId, guard: CpuGuard, cpuMicros: Long) {
        clock.advance(30.seconds)
        runtime.cpu[name] = cpuMicros
        guard.tick()
    }

    @Test
    fun `a lost network floor stops every session and is installed again on the network as it now is`() = runBlocking {
        TestServer().use { server ->
            val name = server.sandbox("floor").id
            server.sandboxes.start(name)
            val guard = NetworkGuard(server.runtime, server.enforcer, server.sandboxes, server.sessions, server.health)
            val madeAgain = server.runtime.network.copy(bridge = "br-made-again")

            server.runtime.network = madeAgain
            server.enforcer.holds = false
            guard.tick()

            assertNull(server.sessions.get(name))
            assertEquals(StopReason.POLICY_FAILED, server.sessions.lastEnd(name)?.reason)
            assertEquals(listOf(madeAgain), server.enforcer.installed)
            assertTrue(server.health.healthy)
            server.sandboxes.start(name)
            assertTrue(server.sessions.get(name) != null, "sessions start again once the floor is back")
        }
    }

    @Test
    fun `no session starts while the floor is being put back`() = runBlocking {
        TestServer().use { server ->
            val name = server.sandbox("held").id
            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch { server.sessions.withoutStarts { holding.complete(Unit); release.await() } }
            holding.await()

            val start = async { server.sandboxes.start(name) }
            assertNull(withTimeoutOrNull(200.milliseconds) { start.await() }, "the start waits for the floor")
            assertNull(server.sessions.get(name))
            release.complete(Unit)
            holder.join()
            start.await()

            assertTrue(server.sessions.get(name) != null, "and goes on once the floor is back")
        }
    }

    @Test
    fun `a network floor that cannot be installed again latches and stops every session`() = runBlocking {
        TestServer().use { server ->
            val name = server.sandbox("floor").id
            server.sandboxes.start(name)
            val guard = NetworkGuard(server.runtime, server.enforcer, server.sandboxes, server.sessions, server.health)

            server.enforcer.holds = false
            server.enforcer.installFails = true
            guard.tick()
            server.enforcer.installFails = false
            server.enforcer.holds = true
            guard.tick()

            assertNull(server.sessions.get(name))
            assertFalse(server.health.healthy, "the latch does not clear on its own")
        }
    }
}
