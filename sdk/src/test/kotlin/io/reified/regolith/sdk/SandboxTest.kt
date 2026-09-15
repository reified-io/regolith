package io.reified.regolith.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.reified.regolith.protocol.ErrorCodes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val SANDBOX = "5f2b9c7d1e3a4f6b8c0d2e4f6a8b0c1d"

class SandboxTest {
    // a server of a release that does not know the bound sends the whole file, so the client has to hold the line itself.
    @Test
    fun `a file read stops at its bound even when the server sends more`() = runBlocking {
        val engine = MockEngine { respond(ByteArray(64) { 'x'.code.toByte() }, HttpStatusCode.OK) }

        RegolithClient("http://localhost", "test-token", HttpClient(engine)).use { client ->
            val files = client.sandbox(SANDBOX).files

            val refused = assertFailsWith<RegolithException> { files.read("notes/today.txt", maxBytes = 16) }

            assertEquals(ErrorCodes.PAYLOAD_TOO_LARGE, refused.code)
            assertEquals(64, files.read("notes/today.txt", maxBytes = 64).size)
        }
    }
}
