package io.reified.regolith.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class JsonShapeTest {
    @Test
    fun `network policy uses lowercase modes and cidr objects`() {
        val policy = NetworkPolicy(NetworkMode.ALLOWLIST, listOf(NetworkAllow("140.82.112.0/20")))
        val json = RegolithJson.strict.encodeToString(NetworkPolicy.serializer(), policy)

        assertEquals("""{"mode":"allowlist","allow":[{"cidr":"140.82.112.0/20"}]}""", json)
    }

    @Test
    fun `an image policy uses lowercase modes and leaves out the image it does not need`() {
        fun json(policy: ImagePolicy) = RegolithJson.strict.encodeToString(ImagePolicy.serializer(), policy)

        assertEquals("""{"mode":"default"}""", json(ImagePolicy(ImageMode.DEFAULT)))
        assertEquals("""{"mode":"track","image":"ghcr.io/example/sandbox"}""", json(ImagePolicy(ImageMode.TRACK, "ghcr.io/example/sandbox")))
        assertEquals("""{"mode":"pin","image":"ghcr.io/example/sandbox:3.1"}""", json(ImagePolicy(ImageMode.PIN, "ghcr.io/example/sandbox:3.1")))
    }

    @Test
    fun `instants travel as iso strings`() {
        val frame = SessionInfo(
            startedAt = Instant.parse("2026-09-13T10:00:00Z"),
            lastActiveAt = Instant.parse("2026-09-13T10:05:00Z"),
            expiresAt = Instant.parse("2026-09-14T10:00:00Z"),
            image = "ghcr.io/example/sandbox:3.1",
        )
        val json = RegolithJson.strict.encodeToString(SessionInfo.serializer(), frame)

        assertEquals(
            """{"startedAt":"2026-09-13T10:00:00Z","lastActiveAt":"2026-09-13T10:05:00Z","expiresAt":"2026-09-14T10:00:00Z","image":"ghcr.io/example/sandbox:3.1"}""",
            json,
        )
    }

    @Test
    fun `strict decoding rejects a misspelled field while lenient decoding ignores it`() {
        val body = """{"shell":"true","timeoutSecond":5}"""

        assertFailsWith<SerializationException> { RegolithJson.strict.decodeFromString(ExecRequest.serializer(), body) }
        assertEquals("true", RegolithJson.lenient.decodeFromString(ExecRequest.serializer(), body).shell)
    }

    @Test
    fun `an id is hex of one fixed length and nothing else`() {
        val id = "5f2b9c7d1e3a4f6b8c0d2e4f6a8b0c1d"

        assertTrue(Ids.isId(id))
        assertEquals(Ids.LENGTH, id.length)
        listOf("", "user-23", "telegram:42", id.uppercase(), id + "0", id.dropLast(1)).forEach {
            assertFalse(Ids.isId(it), it)
        }
    }
}
