package io.reified.regolith.server.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import io.reified.regolith.protocol.HealthStatus
import io.reified.regolith.protocol.ImageMode
import io.reified.regolith.protocol.ImagePolicy
import io.reified.regolith.protocol.NetworkAllow
import io.reified.regolith.protocol.NetworkMode
import io.reified.regolith.protocol.NetworkPolicy
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.OutputKind
import io.reified.regolith.protocol.SandboxState
import io.reified.regolith.protocol.UpdateSandboxRequest
import io.reified.regolith.sdk.RegolithException
import io.reified.regolith.server.app.Health
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.support.TEST_TOKEN
import io.reified.regolith.server.support.apiTest
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(1, client.info().protocol)
    }

    @Test
    fun `a directory is published and its address comes back`() = apiTest { server, client ->
        val sandbox = client.sandbox("demo").also { it.getOrCreate() }
        server.runtime.place(io.reified.regolith.server.domain.SandboxName.parse("demo"), "/home/sandbox/site/index.html", "<h1>hi</h1>")

        val published = sandbox.publish("site")
        assertEquals("https://demo.example.test", published.url)
        assertEquals(1, published.files)
        assertEquals("<h1>hi</h1>", server.publisher.published.getValue("demo")["index.html"])

        assertEquals(true, client.info().publishing)
        assertEquals(published.release, sandbox.site().release)
        sandbox.unpublish()
        assertEquals("not_found", assertFailsWith<RegolithException> { sandbox.site() }.code)
    }

    @Test
    fun `llms txt is served without a token and names every endpoint group`() = apiTest { _, _ ->
        val response = createClient {}.get("/llms.txt")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.startsWith("# Regolith"))
        listOf("PUT /v1/sandboxes/{name}", "POST /v1/sandboxes/{name}/execs", "/output?offset", "/files/content", "application/problem+json").forEach {
            assertTrue(it in body, "llms.txt does not mention $it")
        }
    }

    @Test
    fun `put creates a sandbox once and then returns it unchanged`() = apiTest { _, client ->
        val sandbox = client.sandbox("user-23")
        val created = sandbox.getOrCreate(CreateSandboxRequest(labels = mapOf("team" to "research")))
        val again = sandbox.getOrCreate(CreateSandboxRequest(labels = mapOf("team" to "design")))

        assertEquals(created.createdAt, again.createdAt)
        assertEquals("research", again.labels["team"])
        assertEquals(SandboxState.STOPPED, again.state)
        assertEquals(listOf("user-23"), client.sandboxes(labels = mapOf("team" to "research")).sandboxes.map { it.name })
    }

    @Test
    fun `a sandbox follows the server image, or the one it pins`() = apiTest(
        mapOf("REGOLITH_ALLOWED_IMAGES" to FULL_IMAGE),
    ) { server, client ->
        val default = server.config.images.default
        val sandbox = client.sandbox("user-23")
        val created = sandbox.getOrCreate()

        assertEquals(ImagePolicy(ImageMode.DEFAULT), created.imagePolicy)
        assertEquals(default, created.image)
        assertEquals(default, sandbox.start().session?.image)
        assertEquals(default, server.runtime.startedOn[SandboxName.parse("user-23")])

        // a pin applies from the next session; the running one keeps the image it started on.
        val pinned = sandbox.update(UpdateSandboxRequest(imagePolicy = ImagePolicy(ImageMode.PIN, FULL_IMAGE)))
        assertEquals(FULL_IMAGE, pinned.image)
        assertEquals(default, pinned.session?.image)
        sandbox.stop()
        assertEquals(FULL_IMAGE, sandbox.start().session?.image)

        val tracking = client.sandbox("user-24").getOrCreate(CreateSandboxRequest(imagePolicy = ImagePolicy(ImageMode.TRACK, FULL_REPOSITORY)))
        assertEquals(FULL_IMAGE, tracking.image)
    }

    @Test
    fun `an image this server does not offer is refused`() = apiTest { _, client ->
        val refused = listOf(ImagePolicy(ImageMode.PIN, FULL_IMAGE), ImagePolicy(ImageMode.TRACK, FULL_REPOSITORY), ImagePolicy(ImageMode.TRACK))

        refused.forEachIndexed { index, policy ->
            val failure = assertFailsWith<RegolithException> { client.sandbox("user-2$index").getOrCreate(CreateSandboxRequest(imagePolicy = policy)) }
            assertEquals(ErrorCodes.INVALID_REQUEST, failure.code, "$policy was not refused")
        }
    }

    @Test
    fun `malformed names and misspelled fields are refused`() = apiTest { _, client ->
        val badName = createClient {}.put("/v1/sandboxes/Not_A_Label") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.BadRequest, badName.status)

        val typo = createClient {}.put("/v1/sandboxes/typo") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"imag":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, typo.status)
        assertTrue(ErrorCodes.INVALID_REQUEST in typo.bodyAsText())

        client.sandbox("both").getOrCreate()
        val both = assertFailsWith<RegolithException> { client.sandbox("both").run(ExecRequest(shell = "echo x", argv = listOf("echo"))) }
        assertEquals(ErrorCodes.INVALID_REQUEST, both.code)
    }

    @Test
    fun `run returns both streams and the exit code`() = apiTest { _, client ->
        val sandbox = client.sandbox("runner").also { it.getOrCreate() }
        val result = sandbox.run("echo one; err two; echo three; exit 3")

        assertEquals(3, result.exitCode)
        assertEquals("one\nthree\n", result.stdout)
        assertEquals("two\n", result.stderr)
        // the two pipes are read independently, so only the order within each stream is promised.
        assertEquals(setOf("one", "two", "three"), result.combined.lines().filter { it.isNotEmpty() }.toSet())
    }

    @Test
    fun `output resumes from the end of any frame`() = apiTest { _, client ->
        val sandbox = client.sandbox("resume").also { it.getOrCreate() }
        val exec = sandbox.startExec(ExecRequest(shell = "echo a; echo b; echo c"))
        val all = exec.output().toList()
        val rest = exec.output(fromOffset = all.first().end).toList()

        assertEquals(listOf("a\n", "b\n", "c\n"), all.map { it.text })
        assertTrue(all.all { it.kind == OutputKind.STDOUT })
        assertEquals(all.drop(1), rest)
    }

    @Test
    fun `a timeout, a cancel and a stop each end a command with their own outcome`() = apiTest { _, client ->
        val sandbox = client.sandbox("endings").also { it.getOrCreate() }

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
        val sandbox = client.sandbox("limits").also { it.getOrCreate() }

        val oom = sandbox.run("oom").info.outcome
        assertEquals(137, oom?.exitCode)
        assertEquals("oom_killed", oom?.reason)
        val plain = sandbox.run("exit 3").info.outcome
        assertEquals(3, plain?.exitCode)
        assertEquals(null, plain?.reason)
    }

    @Test
    fun `an idempotency key returns the exec the first attempt started`() = apiTest { _, client ->
        val sandbox = client.sandbox("retry").also { it.getOrCreate() }
        val first = sandbox.startExec(ExecRequest(shell = "echo once"), idempotencyKey = "attempt-1")
        val second = sandbox.startExec(ExecRequest(shell = "echo once"), idempotencyKey = "attempt-1")

        assertEquals(first.id, second.id)
        assertEquals(1, sandbox.execs().size)
    }

    @Test
    fun `stdin reaches the command`() = apiTest { _, client ->
        val sandbox = client.sandbox("stdin").also { it.getOrCreate() }
        val exec = sandbox.startExec(ExecRequest(shell = "cat", stdin = true))
        exec.writeStdin("hello ".encodeToByteArray())
        exec.writeStdin("world".encodeToByteArray(), close = true)

        assertEquals("hello world", exec.output().toList().joinToString("") { it.text })
        assertEquals(0, exec.await().outcome?.exitCode)
    }

    @Test
    fun `files round trip through the session`() = apiTest { _, client ->
        val files = client.sandbox("files").also { it.getOrCreate() }.files
        files.write("notes/today.txt", "remember the milk")

        assertEquals("remember the milk", files.readText("notes/today.txt"))
        assertEquals(listOf("today.txt"), files.list("notes").map { it.name })
        assertEquals(17, files.stat("/home/sandbox/notes/today.txt").size)
        files.delete("notes/today.txt")
        assertEquals(ErrorCodes.NOT_FOUND, assertFailsWith<RegolithException> { files.stat("notes/today.txt") }.code)
    }

    @Test
    fun `a network change reaches the running session and private space is refused`() = apiTest { server, client ->
        val sandbox = client.sandbox("network").also { it.getOrCreate() }
        sandbox.start()
        sandbox.update(UpdateSandboxRequest(network = NetworkPolicy(NetworkMode.ALLOWLIST, listOf(NetworkAllow("140.82.112.7/20")))))

        val applied = server.enforcer.applied[SandboxName.parse("network")] as DomainPolicy.Allowlist
        assertEquals("140.82.112.0/20", applied.cidrs.single().value)
        val refused = assertFailsWith<RegolithException> {
            sandbox.update(UpdateSandboxRequest(network = NetworkPolicy(NetworkMode.ALLOWLIST, listOf(NetworkAllow("10.1.0.0/16")))))
        }
        assertEquals(HttpStatusCode.BadRequest.value, refused.status)
    }

    @Test
    fun `none detaches a running session and any other mode attaches it again under its policy`() = apiTest { server, client ->
        val name = SandboxName.parse("switch")
        val sandbox = client.sandbox("switch").also { it.getOrCreate() }
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
        val sandbox = client.sandbox("offline")
        sandbox.getOrCreate(CreateSandboxRequest(network = NetworkPolicy(NetworkMode.NONE)))
        sandbox.run("echo alone")

        assertTrue(SandboxName.parse("offline") !in server.runtime.attached)
        assertTrue(!server.enforcer.applied.containsKey(SandboxName.parse("offline")))
    }

    @Test
    fun `capacity reclaims an idle session and refuses when every session is busy`() = apiTest(mapOf("REGOLITH_MAX_SESSIONS" to "1")) { server, client ->
        val a = client.sandbox("a").also { it.getOrCreate() }
        val b = client.sandbox("b").also { it.getOrCreate() }
        a.start()
        b.startExec(ExecRequest(shell = "sleep"))
        assertEquals(setOf(SandboxName.parse("b")), server.runtime.sessions)

        val refused = assertFailsWith<RegolithException> { a.start() }
        assertEquals(ErrorCodes.CAPACITY_EXHAUSTED, refused.code)
    }

    @Test
    fun `the event stream carries frames and ends with the finished exec`() = apiTest { _, client ->
        val sandbox = client.sandbox("events").also { it.getOrCreate() }
        val exec = sandbox.startExec(ExecRequest(shell = "echo streamed"))
        exec.await()

        val body = createClient {}.get("/v1/sandboxes/events/execs/${exec.id}/output") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
        }.bodyAsText()

        assertTrue("event: stdout" in body, body)
        assertTrue("streamed" in body, body)
        assertTrue("event: end" in body, body)
    }

    @Test
    fun `a failing network check refuses new sessions and shows in health`() = apiTest { server, client ->
        val sandbox = client.sandbox("unhealthy").also { it.getOrCreate() }
        server.health.report(Health.NETWORK, ok = false, detail = "policy lost")

        assertEquals(ErrorCodes.UNAVAILABLE, assertFailsWith<RegolithException> { sandbox.start() }.code)
        assertEquals(HealthStatus.FAILING, client.health().status)
    }

    @Test
    fun `deleting a sandbox removes its home and record`() = apiTest { server, client ->
        val sandbox = client.sandbox("gone").also { it.getOrCreate() }
        sandbox.run("echo bye")
        sandbox.delete()

        val missing = assertFailsWith<RegolithException> { sandbox.get() }
        assertEquals(ErrorCodes.NOT_FOUND, missing.code)
        assertTrue("does not exist" in missing.message.orEmpty(), "the error body must survive the status page: ${missing.message}")
        assertEquals(listOf(SandboxName.parse("gone")), server.homes.destroyed)
    }
}
