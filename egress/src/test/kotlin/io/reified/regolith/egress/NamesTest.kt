package io.reified.regolith.egress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun `a host name is lowercased and loses its trailing dot`() {
        assertEquals("files.example.com", Names.hostName("Files.Example.COM."))
    }

    @Test
    fun `an address or a malformed label is not a name`() {
        listOf("10.0.0.1", "1.2.3.4.", "", "-bad.example.com", "bad-.example.com", "a..b", "ex ample.com", "x".repeat(64) + ".com")
            .forEach { assertNull(Names.hostName(it), it) }
    }

    @Test
    fun `a plain pattern covers its own name and a wildcard only the names below it`() {
        assertTrue(Names.matches("example.com", "example.com"))
        assertFalse(Names.matches("example.com", "www.example.com"))
        assertTrue(Names.matches("*.example.com", "www.example.com"))
        assertTrue(Names.matches("*.example.com", "a.b.example.com"))
        assertFalse(Names.matches("*.example.com", "example.com"))
        assertFalse(Names.matches("*.example.com", "badexample.com"))
    }

    @Test
    fun `a refused network covers every address inside it`() {
        val network = Network.parse("10.0.0.0/8")

        assertTrue(network.contains(checkNotNull(Ipv4.parse("10.200.3.4"))))
        assertFalse(network.contains(checkNotNull(Ipv4.parse("11.0.0.1"))))
        assertTrue(Network.parse("0.0.0.0/0").contains(checkNotNull(Ipv4.parse("203.0.113.9"))))
    }

    @Test
    fun `before any table nothing is allowed and everything is refused`() {
        assertFalse(Policy.NONE.allows("172.30.0.5", "example.com"))
        assertTrue(Policy.NONE.refuses(checkNotNull(Ipv4.parse("93.184.215.14"))))
    }
}
