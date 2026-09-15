package io.reified.regolith.server.config

import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.support.TEST_TOKEN
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class ServerConfigTest {
    private fun load(vararg env: Pair<String, String>) =
        ServerConfig.fromEnvironment(mapOf("REGOLITH_TOKEN" to TEST_TOKEN) + env, version = "1.2.3")

    @Test
    fun `defaults pin the sandbox image to the server version`() {
        val config = load()

        assertEquals("ghcr.io/reified-io/regolith-sandbox:1.2.3", config.images.default)
        assertEquals(listOf(config.images.default), config.images.allowed)
        assertEquals(NetworkPolicy.Public, config.defaults.network)
        assertEquals(900.seconds, config.defaults.lifecycle.idleStop)
        assertEquals(30.days, config.defaults.lifecycle.retain)
        assertFalse(TEST_TOKEN in config.toString())
    }

    @Test
    fun `there is no mode without a strong token`() {
        assertFailsWith<IllegalStateException> { ServerConfig.fromEnvironment(emptyMap()) }
        assertFailsWith<IllegalStateException> { ServerConfig.fromEnvironment(mapOf("REGOLITH_TOKEN" to "short")) }
    }

    @Test
    fun `the token can come from a file, but not from both places`() {
        val file = Files.createTempFile("regolith-token", "")

        try {
            Files.writeString(file, "$TEST_TOKEN\n")
            assertEquals(TEST_TOKEN, ServerConfig.fromEnvironment(mapOf("REGOLITH_TOKEN_FILE" to file.toString())).token)
            assertFailsWith<IllegalStateException> { load("REGOLITH_TOKEN_FILE" to file.toString()) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `images must be pinned`() {
        assertFailsWith<IllegalStateException> { load("REGOLITH_SANDBOX_IMAGE" to "debian") }
        assertFailsWith<IllegalStateException> { load("REGOLITH_SANDBOX_IMAGE" to "registry.local:5000/sandbox:latest") }
        assertEquals("registry.local:5000/sandbox:2026.09", load("REGOLITH_SANDBOX_IMAGE" to "registry.local:5000/sandbox:2026.09").images.default)
        assertEquals(2, load("REGOLITH_ALLOWED_IMAGES" to "example/sandbox@sha256:abc").images.allowed.size)
    }

    @Test
    fun `two allowed images cannot share a repository`() {
        assertFailsWith<IllegalStateException> { load("REGOLITH_ALLOWED_IMAGES" to "ghcr.io/reified-io/regolith-sandbox:1.2.2") }
    }

    @Test
    fun `defaults cannot exceed their ceilings`() {
        assertFailsWith<IllegalStateException> { load("REGOLITH_MEMORY_MB" to "8192", "REGOLITH_MAX_MEMORY_MB" to "4096") }
        assertFailsWith<IllegalStateException> { load("REGOLITH_NETWORK" to "open") }
        assertFailsWith<IllegalStateException> { load("REGOLITH_NAMESPACE" to "Has Spaces") }
    }

    @Test
    fun `host specific floor entries and io caps are validated`() {
        val config = load("REGOLITH_BLOCKED_CIDRS" to "203.0.113.9, 198.51.100.0/24", "REGOLITH_HOME_WRITE_BPS" to "none")

        assertEquals(listOf("203.0.113.9/32", "198.51.100.0/24"), config.blockedCidrs.map { it.value })
        assertEquals(null, config.homeWriteBps)
        assertEquals("100mb", config.homeReadBps)
        assertFailsWith<IllegalStateException> { load("REGOLITH_BLOCKED_CIDRS" to "not-an-address") }
        assertFailsWith<IllegalStateException> { load("REGOLITH_HOME_READ_BPS" to "fast") }
    }
}
