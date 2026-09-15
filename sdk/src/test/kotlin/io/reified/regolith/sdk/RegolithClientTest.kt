package io.reified.regolith.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.reified.regolith.protocol.ErrorCodes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

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
}
