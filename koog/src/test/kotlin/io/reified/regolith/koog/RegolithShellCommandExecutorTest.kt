package io.reified.regolith.koog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecOutcome
import io.reified.regolith.protocol.ExecRequest
import io.reified.regolith.protocol.ExecStatus
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.OutputFrame
import io.reified.regolith.protocol.OutputKind
import io.reified.regolith.protocol.OutputPage
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.sdk.RegolithClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RegolithShellCommandExecutorTest {
    private val started = Instant.parse("2026-09-13T12:00:00Z")

    private fun executor(output: String, outcome: ExecOutcome, requests: MutableList<ExecRequest> = mutableListOf()): RegolithShellCommandExecutor {
        val info = ExecInfo(id = "e1", shell = "make", status = ExecStatus.RUNNING, startedAt = started, outputEnd = 0, outputTruncated = false, stdinOpen = false)
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            fun <T> json(serializer: KSerializer<T>, value: T) =
                respond(RegolithJson.strict.encodeToString(serializer, value), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            when {
                request.method == HttpMethod.Post && path == "/v1/sandboxes/box/execs" -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    requests += RegolithJson.strict.decodeFromString(ExecRequest.serializer(), body)
                    json(ExecInfo.serializer(), info)
                }
                path == "/v1/sandboxes/box/execs/e1/output" ->
                    json(OutputPage.serializer(), OutputPage(listOf(OutputFrame(OutputKind.STDOUT, output, end = 1)), nextOffset = 1, complete = true))
                path == "/v1/sandboxes/box/execs/e1" ->
                    json(ExecInfo.serializer(), info.copy(status = ExecStatus.FINISHED, outcome = outcome, outputEnd = 1))
                else -> error("Unexpected request ${request.method.value} $path")
            }
        }
        val client = RegolithClient("http://regolith.test", "token", HttpClient(engine))

        return RegolithShellCommandExecutor(client.sandbox("box"), maxOutputChars = 1_000)
    }

    @Test
    fun `a command runs in the sandbox with the directory and timeout the tool passed`() = runBlocking {
        val requests = mutableListOf<ExecRequest>()
        val result = executor("ok\n", ExecOutcome(OutcomeType.EXITED, exitCode = 2), requests).execute("make test", "project", 90)

        assertEquals("ok\n", result.output)
        assertEquals(2, result.exitCode)
        assertEquals(ExecRequest(shell = "make test", cwd = "project", timeoutSeconds = 90), requests.single())
    }

    @Test
    fun `long output keeps its beginning and its end, and a timeout has no exit code`() = runBlocking {
        val output = "BEGIN" + "x".repeat(5_000) + "END"
        val result = executor(output, ExecOutcome(OutcomeType.TIMED_OUT)).execute("make", null, 10)

        assertTrue(result.output.startsWith("BEGIN") && result.output.endsWith("END"))
        assertTrue(result.output.length <= 1_000)
        assertNull(result.exitCode)
    }
}
