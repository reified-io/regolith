package io.reified.regolith.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainTest {
    @Test
    fun `sandbox names are dns labels`() {
        listOf("a", "user-23", "x".repeat(63)).forEach { SandboxName.parse(it) }
        listOf("", "-a", "a-", "Upper", "under_score", "dot.ted", "x".repeat(64)).forEach { raw ->
            assertFailsWith<RegolithError.Invalid>(raw) { SandboxName.parse(raw) }
        }
    }

    @Test
    fun `cidrs are canonical and refused space cannot be allowed`() {
        assertEquals("140.82.112.0/20", Cidr.parse("140.82.112.7/20").value)
        assertEquals("1.1.1.1/32", Cidr.parse("1.1.1.1").value)
        listOf("256.1.1.1", "1.1.1.1/33", "::1", "example.com").forEach { raw ->
            assertFailsWith<RegolithError.Invalid>(raw) { Cidr.parse(raw) }
        }

        listOf("10.0.0.0/8", "192.168.1.0/24", "169.254.169.254", "100.64.1.1").forEach { raw ->
            assertFailsWith<RegolithError.Invalid>(raw) { NetworkPolicy.Allowlist(listOf(Cidr.parse(raw))) }
        }
        NetworkPolicy.Allowlist(listOf(Cidr.parse("0.0.0.0/0"), Cidr.parse("8.8.8.8")))
    }

    @Test
    fun `commands and metadata are bounded`() {
        assertFailsWith<RegolithError.Invalid> { ExecCommand.Shell(" ") }
        assertFailsWith<RegolithError.Invalid> { ExecCommand.Argv(emptyList()) }
        assertFailsWith<RegolithError.Invalid> { Metadata.requireEnv(mapOf("1BAD" to "x")) }
        assertFailsWith<RegolithError.Invalid> { Metadata.requireEnv(mapOf("NUL" to "a${Char(0)}b")) }
        assertFailsWith<RegolithError.Invalid> { Metadata.requireLabels(mapOf("Key" to "x"), maxLabels = 4) }
        Metadata.requireEnv(mapOf("MULTI_LINE" to "first\nsecond"))
    }
}
