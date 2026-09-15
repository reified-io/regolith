package io.reified.regolith.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `a repository is a reference without its tag or digest`() {
        assertEquals("ghcr.io/example/sandbox", ImageRef.repositoryOf("ghcr.io/example/sandbox:3.1"))
        assertEquals("ghcr.io/example/sandbox", ImageRef.repositoryOf("ghcr.io/example/sandbox@sha256:abc"))
        assertEquals("registry.local:5000/sandbox", ImageRef.repositoryOf("registry.local:5000/sandbox:2026.09"))
        assertEquals("registry.local:5000/sandbox", ImageRef.repositoryOf("registry.local:5000/sandbox"))

        assertTrue(ImageRef.pinned("registry.local:5000/sandbox:2026.09"))
        assertTrue(ImageRef.pinned("example/sandbox@sha256:abc"))
        assertFalse(ImageRef.pinned("registry.local:5000/sandbox"))
        assertFalse(ImageRef.pinned("example/sandbox:latest"))
    }

    @Test
    fun `a catalogue resolves what a sandbox follows and refuses what it does not offer`() {
        val base = "ghcr.io/example/sandbox:3.1"
        val full = "ghcr.io/example/sandbox-full:3.1"
        val catalogue = ImageCatalog(base, listOf(base, full))

        assertEquals(base, catalogue.resolve(ImagePolicy.Default))
        assertEquals(full, catalogue.resolve(ImagePolicy.Track("ghcr.io/example/sandbox-full")))
        assertEquals("ghcr.io/example/sandbox:1.0", catalogue.resolve(ImagePolicy.Pin("ghcr.io/example/sandbox:1.0")))

        catalogue.requireAllowed(ImagePolicy.Track("ghcr.io/example/sandbox-full"))
        catalogue.requireAllowed(ImagePolicy.Pin(base))
        assertFailsWith<RegolithError.Invalid> { catalogue.requireAllowed(ImagePolicy.Track("ghcr.io/example/other")) }
        assertFailsWith<RegolithError.Invalid> { catalogue.requireAllowed(ImagePolicy.Pin("ghcr.io/example/sandbox:1.0")) }
        assertFailsWith<RegolithError.Invalid> { ImagePolicy.Pin("x".repeat(ImagePolicy.MAX_REFERENCE + 1)) }
    }

    @Test
    fun `a newer catalogue moves what follows it and leaves a pin alone`() {
        val old = ImageCatalog("ghcr.io/example/sandbox:3.1", listOf("ghcr.io/example/sandbox:3.1"))
        val new = ImageCatalog("ghcr.io/example/sandbox:4.0", listOf("ghcr.io/example/sandbox:4.0"))
        val tracked = ImagePolicy.Track("ghcr.io/example/sandbox")
        val pinned = ImagePolicy.Pin(old.default)

        assertEquals("ghcr.io/example/sandbox:4.0", new.resolve(ImagePolicy.Default))
        assertEquals("ghcr.io/example/sandbox:4.0", new.resolve(tracked))
        assertEquals("ghcr.io/example/sandbox:3.1", new.resolve(pinned))
        assertEquals("ghcr.io/example/sandbox:3.1", old.resolve(tracked))

        // a repository the server stopped offering strands its sandboxes until an operator moves them.
        val elsewhere = ImageCatalog("ghcr.io/example/other:1", listOf("ghcr.io/example/other:1"))
        assertNull(elsewhere.resolveOrNull(tracked))
        assertFailsWith<RegolithError.Unavailable> { elsewhere.resolve(tracked) }
        assertFailsWith<IllegalStateException> { ImageCatalog(old.default, listOf(old.default, new.default)) }
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
