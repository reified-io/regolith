package io.reified.regolith.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.reified.regolith.protocol.ErrorCodes
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private const val SANDBOX = "5f2b9c7d1e3a4f6b8c0d2e4f6a8b0c1d"

class RegolithClientTest {
    @Test
    fun `a refusal keeps the server's sentence and how long to wait`() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"type":"urn:regolith:error:capacity_exhausted","title":"No session capacity","status":503,"detail":"All 2 sessions are busy","code":"capacity_exhausted"}""",
                HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.ContentType to listOf("application/problem+json"), HttpHeaders.RetryAfter to listOf("5")),
            )
        }

        RegolithClient("http://localhost", "test-token", HttpClient(engine)).use { client ->
            val refused = assertFailsWith<RegolithException> { client.info() }

            assertEquals(ErrorCodes.CAPACITY_EXHAUSTED, refused.code)
            assertEquals("All 2 sessions are busy", refused.detail)
            assertEquals(5.seconds, refused.retryAfter)
        }
    }

    @Test
    fun `an answer from something in front of the server is kept as it came`() = runBlocking {
        val engine = MockEngine { respond("Bad gateway", HttpStatusCode.BadGateway) }

        RegolithClient("http://localhost", "test-token", HttpClient(engine)).use { client ->
            val refused = assertFailsWith<RegolithException> { client.info() }

            assertEquals("http_502", refused.code)
            assertEquals("Bad gateway", refused.detail)
            assertNull(refused.retryAfter)
        }
    }

    @Test
    fun `no answer is one exception whatever the engine threw, and it names no address`() = runBlocking {
        val failures = listOf(
            ConnectException("Connection refused: 10.0.0.4:8080"),
            UnresolvedAddressException(),
            HttpRequestTimeoutException("http://10.0.0.4:8080/v1/sandboxes/5f2b9c7d1e3a4f6b8c0d2e4f6a8b0c1d/files/content", 120_000),
        )

        for (failure in failures) {
            RegolithClient("http://10.0.0.4:8080", "test-token", HttpClient(MockEngine { throw failure })).use { client ->
                val sandbox = client.sandbox(SANDBOX)
                val operations = listOf<suspend () -> Unit>({ client.info() }, { sandbox.delete() }, { sandbox.files.read("notes/today.txt", 16) })

                for (operation in operations) {
                    val unanswered = assertFailsWith<RegolithConnectionException> { operation() }
                    assertEquals(failure::class, unanswered.cause?.let { it::class })
                    assertFalse("10.0.0.4" in unanswered.message.orEmpty(), unanswered.message)
                }
            }
        }
    }

    // an alias reached this call before ids existed, and nothing stopped it from landing in a path.
    @Test
    fun `anything but an id is refused before it reaches a request path`() {
        RegolithClient("http://localhost", "test-token", HttpClient(MockEngine { respond("", HttpStatusCode.OK) })).use { client ->
            listOf("user-23", "", "../info", SANDBOX.uppercase(), SANDBOX + "0").forEach { raw ->
                assertFailsWith<IllegalArgumentException>(raw) { client.sandbox(raw) }
            }
            assertFailsWith<IllegalArgumentException> { client.sandbox(SANDBOX).exec("user-23") }
            assertEquals(SANDBOX, client.sandbox(SANDBOX).id)
        }
    }
}
