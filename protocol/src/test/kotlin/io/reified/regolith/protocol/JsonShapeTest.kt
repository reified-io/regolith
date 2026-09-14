package io.reified.regolith.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class JsonShapeTest {
    @Test
    fun `network policy uses lowercase modes and cidr objects`() {
        val policy = NetworkPolicy(NetworkMode.ALLOWLIST, listOf(NetworkAllow("140.82.112.0/20")))
        val json = RegolithJson.strict.encodeToString(NetworkPolicy.serializer(), policy)

        assertEquals("""{"mode":"allowlist","allow":[{"cidr":"140.82.112.0/20"}]}""", json)
    }

    @Test
    fun `instants travel as iso strings`() {
        val frame = SessionInfo(
            startedAt = Instant.parse("2026-09-13T10:00:00Z"),
            lastActiveAt = Instant.parse("2026-09-13T10:05:00Z"),
            expiresAt = Instant.parse("2026-09-14T10:00:00Z"),
        )
        val json = RegolithJson.strict.encodeToString(SessionInfo.serializer(), frame)

        assertEquals(
            """{"startedAt":"2026-09-13T10:00:00Z","lastActiveAt":"2026-09-13T10:05:00Z","expiresAt":"2026-09-14T10:00:00Z"}""",
            json,
        )
    }

    @Test
    fun `strict decoding rejects a misspelled field while lenient decoding ignores it`() {
        val body = """{"shell":"true","timeoutSecond":5}"""

        assertFailsWith<SerializationException> { RegolithJson.strict.decodeFromString(ExecRequest.serializer(), body) }
        assertEquals("true", RegolithJson.lenient.decodeFromString(ExecRequest.serializer(), body).shell)
    }
}
