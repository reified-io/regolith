package io.reified.regolith.server.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.reified.regolith.protocol.ErrorBody
import io.reified.regolith.protocol.PROBLEM_JSON
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.protocol.CreateSandboxRequest
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.ExecRequest
import io.reified.regolith.protocol.ExecStatus
import io.reified.regolith.protocol.HealthStatus
import io.reified.regolith.protocol.ImageMode
import io.reified.regolith.protocol.ImagePolicy
import io.reified.regolith.protocol.NetworkAllow
import io.reified.regolith.protocol.NetworkMode
import io.reified.regolith.protocol.NetworkPolicy
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.PROTOCOL_VERSION
import io.reified.regolith.protocol.OutputKind
import io.reified.regolith.protocol.SandboxState
import io.reified.regolith.protocol.UpdateSandboxRequest
import io.reified.regolith.sdk.RegolithException
import io.reified.regolith.server.app.Health
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.support.TEST_TOKEN
import io.reified.regolith.server.support.apiTest
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.reified.regolith.server.domain.NetworkPolicy as DomainPolicy

private const val FULL_REPOSITORY = "ghcr.io/example/sandbox-full"
private const val FULL_IMAGE = "$FULL_REPOSITORY:3.1"

class ApiTest {
    @Test
    fun `health is public while everything else needs the token`() = apiTest { _, client ->
        assertEquals(HealthStatus.OK, client.health().status)
        assertEquals(HttpStatusCode.Unauthorized, createClient {}.get("/v1/info").status)
        val wrong = createClient {}.get("/v1/info") { header(HttpHeaders.Authorization, "Bearer not-$TEST_TOKEN") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(PROBLEM_JSON, wrong.contentType()?.withoutParameters()?.toString())
        val problem = RegolithJson.lenient.decodeFromString(ErrorBody.serializer(), wrong.bodyAsText())
        assertEquals(ErrorBody("urn:regolith:error:unauthorized", "Unauthorized", 401, "A valid bearer token is required", ErrorCodes.UNAUTHORIZED), problem)
        assertEquals(PROTOCOL_VERSION, client.info().protocol)
    }

    @Test
    fun `a directory is published and its address comes back`() = apiTest { server, client ->
        val sandbox = client.getOrCreate("demo")
        server.runtime.place(SandboxId.parse(sandbox.id), "/home/sandbox/site/index.html", "<h1>hi</h1>")

        val published = sandbox.publish("site")
        val site = server.siteOf(SandboxId.parse(sandbox.id))
        assertEquals("https://$site.example.test", published.url)
        assertEquals(1, published.files)
        assertEquals("<h1>hi</h1>", server.publisher.published.getValue(site)["index.html"])

        assertEquals(true, client.info().publishing)
        assertEquals(published.release, sandbox.siteOrNull()?.release)
        assertTrue(sandbox.unpublish())
        assertNull(sandbox.siteOrNull())
        assertFalse(sandbox.unpublish(), "taking down a site that is not there is not a failure")

        // a site taken down is gone: publishing again is a new site, at an address of its own
        val again = sandbox.publish("site")
        assertNotEquals(published.url, again.url)
        assertFalse(sandbox.id in again.url || "demo" in again.url, "the address gives the sandbox away")
    }

    @Test
    fun `llms txt is served without a token and names every endpoint group`() = apiTest { _, _ ->
        val response = createClient {}.get("/llms.txt")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.startsWith("# Regolith"))
        listOf("POST /v1/sandboxes", "POST /v1/sandboxes/{id}/execs", "/output?offset", "/files/content", "application/problem+json").forEach {
            assertTrue(it in body, "llms.txt does not mention $it")
        }
    }

    @Test
    fun `an alias creates a sandbox once and then finds it again`() = apiTest { _, client ->
        val created = client.getOrCreate("user-23", CreateSandboxRequest(labels = mapOf("team" to "research"))).get()
        val again = client.getOrCreate("user-23", CreateSandboxRequest(labels = mapOf("team" to "design"))).get()

        assertEquals(created.id, again.id)
        assertEquals(created.createdAt, again.createdAt)
        assertEquals("research", again.labels["team"])
        assertEquals(SandboxState.STOPPED, again.state)
        assertEquals("user-23", again.alias)
        assertEquals(created.id, client.byAlias("user-23")?.id)
        assertNull(client.byAlias("nobody"))
        assertEquals(listOf(created.id), client.sandboxes(labels = mapOf("team" to "research")).sandboxes.map { it.id })
    }

    @Test
    fun `an alias belongs to one sandbox at a time`() = apiTest { _, client ->
        val held = client.getOrCreate("shared-alias")
        val other = client.create()

        val taken = assertFailsWith<RegolithException> { other.update(UpdateSandboxRequest(alias = "shared-alias")) }

        assertEquals(ErrorCodes.INVALID_REQUEST, taken.code)
        assertEquals(held.id, client.byAlias("shared-alias")?.id)
        assertNull(other.get().alias)

        // moving one leaves the sandbox it came from with no alias to be found by
        held.update(UpdateSandboxRequest(alias = "moved"))
        other.update(UpdateSandboxRequest(alias = "shared-alias"))

        assertEquals(other.id, client.byAlias("shared-alias")?.id)
        assertEquals(held.id, client.byAlias("moved")?.id)
    }

    @Test
    fun `a sandbox follows the server image, or the one it pins`() = apiTest(
        mapOf("REGOLITH_ALLOWED_IMAGES" to FULL_IMAGE),
    ) { server, client ->
        val default = server.config.images.default
        val sandbox = client.getOrCreate("user-23")
        val created = sandbox.get()

        assertEquals(ImagePolicy(ImageMode.DEFAULT), created.imagePolicy)
        assertEquals(default, created.image)
        assertEquals(default, sandbox.start().session?.image)
        assertEquals(default, server.runtime.startedOn[SandboxId.parse(sandbox.id)])

        // a pin applies from the next session; the running one keeps the image it started on.
        val pinned = sandbox.update(UpdateSandboxRequest(imagePolicy = ImagePolicy(ImageMode.PIN, FULL_IMAGE)))
        assertEquals(FULL_IMAGE, pinned.image)
        assertEquals(default, pinned.session?.image)
        sandbox.stop()
        assertEquals(FULL_IMAGE, sandbox.start().session?.image)

        val tracking = client.getOrCreate("user-24", CreateSandboxRequest(imagePolicy = ImagePolicy(ImageMode.TRACK, FULL_REPOSITORY))).get()
        assertEquals(FULL_IMAGE, tracking.image)
    }

    @Test
    fun `an image this server does not offer is refused`() = apiTest { _, client ->
        val refused = listOf(ImagePolicy(ImageMode.PIN, FULL_IMAGE), ImagePolicy(ImageMode.TRACK, FULL_REPOSITORY), ImagePolicy(ImageMode.TRACK))

        refused.forEachIndexed { index, policy ->
            val failure = assertFailsWith<RegolithException> { client.getOrCreate("user-2$index", CreateSandboxRequest(imagePolicy = policy)) }
            assertEquals(ErrorCodes.INVALID_REQUEST, failure.code, "$policy was not refused")
        }
    }

    @Test
    fun `malformed ids and misspelled fields are refused`() = apiTest { _, client ->
        val badId = createClient {}.get("/v1/sandboxes/not-an-id") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.BadRequest, badId.status)

        val typo = createClient {}.post("/v1/sandboxes") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"imag":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, typo.status)
        assertTrue(ErrorCodes.INVALID_REQUEST in typo.bodyAsText())

        val sandbox = client.getOrCreate("both")
        val both = assertFailsWith<RegolithException> { sandbox.run(ExecRequest(shell = "echo x", argv = listOf("echo"))) }
        assertEquals(ErrorCodes.INVALID_REQUEST, both.code)
    }

    @Test
    fun `run returns both streams and the exit code`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("runner")
        val result = sandbox.run("echo one; err two; echo three; exit 3")

        assertEquals(3, result.exitCode)
        assertEquals("one\nthree\n", result.stdout)
        assertEquals("two\n", result.stderr)
        // the two pipes are read independently, so only the order within each stream is promised.
        assertEquals(setOf("one", "two", "three"), result.combined.lines().filter { it.isNotEmpty() }.toSet())
    }

    @Test
    fun `output resumes from the end of any frame`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("resume")
        val exec = sandbox.startExec(ExecRequest(shell = "echo a; echo b; echo c"))
        val all = exec.output().toList()
        val rest = exec.output(fromOffset = all.first().end).toList()

        assertEquals(listOf("a\n", "b\n", "c\n"), all.map { it.text })
        assertTrue(all.all { it.kind == OutputKind.STDOUT })
        assertEquals(all.drop(1), rest)
    }

    @Test
    fun `one page of output keeps to its bound and says where the next one starts`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("pages")
        val exec = sandbox.startExec(ExecRequest(shell = "bytes 40000"))
        exec.await()

        // however small the bound, a page carries a frame, or a reader could never move past a large one.
        val first = exec.readOutput(maxBytes = 1)
        val rest = exec.readOutput(offset = first.nextOffset)

        assertEquals(1, first.frames.size)
        assertFalse(first.complete)
        assertTrue(rest.complete)
        assertEquals(40000, (first.frames + rest.frames).sumOf { it.text.length })
    }

    // a client that follows a running command needed a second request for its status after every page
    @Test
    fun `every page of output carries the exec it came from`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("state-on-page")
        val exec = sandbox.startExec(ExecRequest(shell = "bytes 40000"))
        exec.await()

        val first = exec.readOutput(maxBytes = 1)
        val rest = exec.readOutput(offset = first.nextOffset)

        assertEquals(exec.id, first.exec.id)
        assertEquals(ExecStatus.FINISHED, rest.exec.status)
        assertEquals(0, rest.exec.outcome?.exitCode)
        assertEquals(rest.nextOffset, rest.exec.outputEnd)
    }

    // the whole of a read a caller works to a deadline for: output, where to resume, and how it ended
    @Test
    fun `a budgeted read stops at the end of the output and reports the exec with it`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("budgeted")
        val running = sandbox.startExec(ExecRequest(shell = "sleep"))

        val nothing = running.readWithin(budget = Duration.ZERO, maxChars = 1024)

        assertEquals("", nothing.text)
        assertFalse(nothing.complete)
        assertEquals(ExecStatus.RUNNING, nothing.exec.status)
        running.cancel()

        val done = sandbox.startExec(ExecRequest(shell = "bytes 40000"))
        // a page carries a whole frame whatever the bound, so this stops asking at 100 rather than cutting
        val bounded = done.readWithin(budget = 5.seconds, maxChars = 100)
        val onwards = done.readWithin(offset = bounded.nextOffset, budget = 5.seconds, maxChars = 100_000)

        assertTrue(bounded.text.isNotEmpty() && bounded.text.length < 40_000)
        assertFalse(bounded.complete)
        assertEquals(40_000, bounded.text.length + onwards.text.length)
        assertTrue(onwards.complete)
        assertEquals(ExecStatus.FINISHED, onwards.exec.status)
    }

    @Test
    fun `a read that does not wait answers with what is recorded now`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("pages")
        val exec = sandbox.startExec(ExecRequest(shell = "sleep"))

        val page = exec.readOutput()

        assertTrue(page.frames.isEmpty())
        assertFalse(page.complete)
        exec.cancel()
    }

    @Test
    fun `a timeout, a cancel and a stop each end a command with their own outcome`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("endings")

        assertEquals(OutcomeType.TIMED_OUT, sandbox.run(ExecRequest(shell = "sleep", timeoutSeconds = 1)).info.outcome?.type)

        val cancelled = sandbox.startExec(ExecRequest(shell = "sleep"))
        cancelled.cancel()
        assertEquals(OutcomeType.CANCELLED, cancelled.await().outcome?.type)

        val stopped = sandbox.startExec(ExecRequest(shell = "sleep"))
        sandbox.stop()
        val outcome = stopped.await().outcome
        assertEquals(OutcomeType.INTERRUPTED, outcome?.type)
        assertEquals("stopped", outcome?.reason)
        assertEquals("stopped", sandbox.get().lastSessionEnd?.reason)
    }

    @Test
    fun `a command killed for memory says so, a plain failure does not`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("limits")

        val oom = sandbox.run("oom").info.outcome
        assertEquals(137, oom?.exitCode)
        assertEquals("oom_killed", oom?.reason)
        val plain = sandbox.run("exit 3").info.outcome
        assertEquals(3, plain?.exitCode)
        assertEquals(null, plain?.reason)
    }

    @Test
    fun `an idempotency key returns the exec the first attempt started`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("retry")
        val first = sandbox.startExec(ExecRequest(shell = "echo once"), idempotencyKey = "attempt-1")
        val second = sandbox.startExec(ExecRequest(shell = "echo once"), idempotencyKey = "attempt-1")

        assertEquals(first.id, second.id)
        assertEquals(1, sandbox.execs().size)
    }

    @Test
    fun `stdin reaches the command`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("stdin")
        val exec = sandbox.startExec(ExecRequest(shell = "cat", stdin = true))
        exec.writeStdin("hello ".encodeToByteArray())
        exec.writeStdin("world".encodeToByteArray(), close = true)

        assertEquals("hello world", exec.output().toList().joinToString("") { it.text })
        assertEquals(0, exec.await().outcome?.exitCode)
    }

    @Test
    fun `files round trip through the session`() = apiTest { _, client ->
        val files = client.getOrCreate("files").files
        files.write("notes/today.txt", "remember the milk")

        assertEquals("remember the milk", files.readText("notes/today.txt"))
        assertEquals(listOf("today.txt"), files.list("notes").map { it.name })
        assertEquals(17, files.stat("/home/sandbox/notes/today.txt").size)
        files.delete("notes/today.txt")
        assertEquals(ErrorCodes.NOT_FOUND, assertFailsWith<RegolithException> { files.stat("notes/today.txt") }.code)
    }

    @Test
    fun `a file read keeps to the reader's bound, refusing a larger file before sending any of it`() = apiTest { _, client ->
        val files = client.getOrCreate("files").files
        files.write("notes/today.txt", "remember the milk")

        assertEquals("remember the milk", files.readText("notes/today.txt", maxBytes = 17))
        val refused = assertFailsWith<RegolithException> { files.read("notes/today.txt", maxBytes = 16) }
        assertEquals(ErrorCodes.PAYLOAD_TOO_LARGE, refused.code)
        assertEquals(413, refused.status)

        for (bound in listOf("0", "lots")) {
            val response = createClient {}.get("/v1/sandboxes/files/files/content?path=notes/today.txt&maxBytes=$bound") {
                header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "maxBytes=$bound was accepted")
        }
    }

    @Test
    fun `a file the sandbox user cannot read is refused before any of it is sent`() = apiTest { server, client ->
        val files = client.getOrCreate("files").files
        files.write("notes/today.txt", "remember the milk")
        server.runtime.unreadable += "/home/sandbox/notes/today.txt"

        val refused = assertFailsWith<RegolithException> { files.readText("notes/today.txt") }

        assertEquals(ErrorCodes.INVALID_REQUEST, refused.code)
        assertEquals(400, refused.status)
    }

    @Test
    fun `a full disk refuses a write with a code of its own`() = apiTest { server, client ->
        val files = client.getOrCreate("files").files
        server.runtime.diskFull = true

        val refused = assertFailsWith<RegolithException> { files.write("notes/today.txt", "remember the milk") }

        assertEquals(ErrorCodes.INSUFFICIENT_STORAGE, refused.code)
        assertEquals(507, refused.status)
    }

    @Test
    fun `a network change reaches the running session and private space is refused`() = apiTest { server, client ->
        val sandbox = client.getOrCreate("network")
        sandbox.start()
        sandbox.update(UpdateSandboxRequest(network = NetworkPolicy(NetworkMode.ALLOWLIST, listOf(NetworkAllow("140.82.112.7/20")))))

        val applied = server.enforcer.applied[SandboxId.parse(sandbox.id)] as DomainPolicy.Allowlist
        assertEquals("140.82.112.0/20", applied.cidrs.single().value)
        val refused = assertFailsWith<RegolithException> {
            sandbox.update(UpdateSandboxRequest(network = NetworkPolicy(NetworkMode.ALLOWLIST, listOf(NetworkAllow("10.1.0.0/16")))))
        }
        assertEquals(HttpStatusCode.BadRequest.value, refused.status)
    }

    @Test
    fun `none detaches a running session and any other mode attaches it again under its policy`() = apiTest { server, client ->
        val sandbox = client.getOrCreate("switch")
        val name = SandboxId.parse(sandbox.id)
        sandbox.start()
        assertTrue(name in server.runtime.attached)

        sandbox.update(UpdateSandboxRequest(network = NetworkPolicy(NetworkMode.NONE)))
        assertTrue(name !in server.runtime.attached)
        assertTrue(!server.enforcer.applied.containsKey(name), "a detached session keeps no rules")

        sandbox.update(UpdateSandboxRequest(network = NetworkPolicy(NetworkMode.ALLOWLIST, listOf(NetworkAllow("1.1.1.1")))))
        assertTrue(name in server.runtime.attached)
        assertTrue(server.enforcer.applied[name] is DomainPolicy.Allowlist)
    }

    @Test
    fun `a sandbox created with none starts detached`() = apiTest { server, client ->
        val sandbox = client.getOrCreate("offline", CreateSandboxRequest(network = NetworkPolicy(NetworkMode.NONE)))
        sandbox.run("echo alone")

        assertTrue(SandboxId.parse(sandbox.id) !in server.runtime.attached)
        assertTrue(!server.enforcer.applied.containsKey(SandboxId.parse(sandbox.id)))
    }

    @Test
    fun `capacity reclaims an idle session and refuses when every session is busy`() = apiTest(mapOf("REGOLITH_MAX_SESSIONS" to "1")) { server, client ->
        val a = client.getOrCreate("a")
        val b = client.getOrCreate("b")
        a.start()
        b.startExec(ExecRequest(shell = "sleep"))
        assertEquals(setOf(SandboxId.parse(b.id)), server.runtime.sessions)

        val refused = assertFailsWith<RegolithException> { a.start() }
        assertEquals(ErrorCodes.CAPACITY_EXHAUSTED, refused.code)
    }

    @Test
    fun `the event stream carries frames and ends with the finished exec`() = apiTest { _, client ->
        val sandbox = client.getOrCreate("events")
        val exec = sandbox.startExec(ExecRequest(shell = "echo streamed"))
        exec.await()

        val body = createClient {}.get("/v1/sandboxes/${sandbox.id}/execs/${exec.id}/output") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
        }.bodyAsText()

        assertTrue("event: stdout" in body, body)
        assertTrue("streamed" in body, body)
        assertTrue("event: end" in body, body)
    }

    @Test
    fun `a failing network check refuses new sessions and shows in health`() = apiTest { server, client ->
        val sandbox = client.getOrCreate("unhealthy")
        server.health.report(Health.NETWORK, ok = false, detail = "policy lost")

        assertEquals(ErrorCodes.UNAVAILABLE, assertFailsWith<RegolithException> { sandbox.start() }.code)
        assertEquals(HealthStatus.FAILING, client.health().status)
    }

    @Test
    fun `deleting a sandbox removes its home and record`() = apiTest { server, client ->
        val sandbox = client.getOrCreate("gone")
        sandbox.run("echo bye")
        sandbox.delete()

        val missing = assertFailsWith<RegolithException> { sandbox.get() }
        assertEquals(ErrorCodes.NOT_FOUND, missing.code)
        assertTrue("does not exist" in missing.message.orEmpty(), "the error body must survive the status page: ${missing.message}")
        assertEquals(listOf(SandboxId.parse(sandbox.id)), server.homes.destroyed)
    }
}
