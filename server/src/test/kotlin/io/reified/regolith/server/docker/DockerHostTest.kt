package io.reified.regolith.server.docker

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DockerHostTest {
    @Test
    fun `a rootful engine on linux is policed, with or without a named firewall backend`() {
        assertEquals(emptyList(), DockerHost.refusals(info()))
        assertEquals(emptyList(), DockerHost.refusals(info(backend = "iptables")))
        // an older daemon says nothing about its backend, and a compatible cli may say nothing at all.
        assertEquals(emptyList(), DockerHost.refusals("""{"ServerVersion":"27.5.1"}"""))
    }

    // each of these lets the floor install and read back clean while traffic leaves some other way
    @Test
    fun `a daemon whose containers leave past the host firewall is refused with the fix`() {
        assertContains(DockerHost.refusals(info(system = "Docker Desktop")).single(), "Docker Engine on Linux")
        assertContains(DockerHost.refusals(info(options = """"name=seccomp,profile=builtin","name=rootless"""")).single(), "rootful")
        assertContains(DockerHost.refusals(info(backend = "nftables")).single(), "\"firewall-backend\": \"iptables\"")
    }

    private fun info(system: String = "Debian GNU/Linux 13 (trixie)", options: String = """"name=seccomp,profile=builtin"""", backend: String? = null): String {
        val firewall = backend?.let { ""","FirewallBackend":{"Driver":"$it"}""" }.orEmpty()

        return """{"ServerVersion":"29.8.0","OperatingSystem":"$system","OSType":"linux","SecurityOptions":[$options]$firewall}"""
    }
}
