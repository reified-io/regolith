package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.PlatformFloor
import io.reified.regolith.server.ports.SandboxNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FirewallRulesTest {
    private val network = SandboxNetwork("regolith-sandboxes", "br-0123456789ab", "172.30.0.0/16", "172.30.0.1")
    private val prefix = FirewallRules.prefixFor("regolith")

    private fun render(sandboxes: Map<String, NetworkPolicy> = emptyMap()) =
        FirewallRules(prefix, network, PlatformFloor.refused + Cidr.parse("192.168.88.252"), sandboxes).render().lines()

    private fun List<String>.rules(chain: String) = filter { it.startsWith("-A $chain ") }

    @Test
    fun `every line belongs to this namespace's chains`() {
        val lines = render(mapOf("172.30.0.5" to NetworkPolicy.Public))

        assertEquals("*filter", lines.first())
        assertEquals("COMMIT", lines.last { it.isNotBlank() })
        lines.filter { it.startsWith(":") || it.startsWith("-") }.forEach { line ->
            val chain = line.removePrefix(":").removePrefix("-A ").removePrefix("-F ").substringBefore(' ')
            assertTrue(chain.startsWith(prefix), line)
        }
        assertNotEquals(prefix, FirewallRules.prefixFor("other"))
    }

    @Test
    fun `the floor and the meters come before anything that can return`() {
        val main = render().rules(prefix)
        val firstDispatch = main.indexOfFirst { it.endsWith("-j ${prefix}_D") || it.endsWith("-j ${prefix}_N") }
        val lastReject = main.indexOfLast { it.contains(" -d ") && it.endsWith("-j REJECT") }
        val meters = main.indices.filter { "hashlimit" in main[it] }

        assertTrue(lastReject < firstDispatch)
        assertTrue(meters.all { it < firstDispatch })
        assertTrue(main.any { it.contains("-d 169.254.0.0/16 -j REJECT") })
        assertTrue(main.any { it.contains("-d 192.168.88.252/32 -j REJECT") })
        assertTrue(main.all { "-i br-0123456789ab" in it || "-o br-0123456789ab" in it })
    }

    @Test
    fun `an address without a policy reaches nothing`() {
        val dispatch = render(mapOf("172.30.0.5" to NetworkPolicy.Public)).rules("${prefix}_D")

        assertEquals(2, dispatch.size)
        assertTrue(dispatch[0].startsWith("-A ${prefix}_D -s 172.30.0.5/32 -g ${prefix}_S"))
        assertEquals("-A ${prefix}_D -j REJECT", dispatch.last())
        assertEquals("-A ${prefix}_IN -i br-0123456789ab -j DROP", render().rules("${prefix}_IN").single())
    }

    @Test
    fun `an allowlist returns only its networks and rejects the rest`() {
        val lines = render(mapOf("172.30.0.7" to NetworkPolicy.Allowlist(listOf(Cidr.parse("140.82.112.0/20")))))
        val chain = lines.first { it.startsWith("-A ${prefix}_D -s 172.30.0.7/32") }.substringAfter("-g ")

        assertEquals(listOf("-A $chain -d 140.82.112.0/20 -j RETURN", "-A $chain -j REJECT"), lines.rules(chain))
        assertEquals(listOf("-A ${prefix}_N -d 1.1.1.1/32 -j RETURN", "-A ${prefix}_N -d 9.9.9.9/32 -j RETURN", "-A ${prefix}_N -j REJECT"), lines.rules("${prefix}_N"))
    }

    @Test
    fun `a detached sandbox is never rendered`() {
        assertFailsWith<IllegalArgumentException> { render(mapOf("172.30.0.9" to NetworkPolicy.None)) }
    }
}
