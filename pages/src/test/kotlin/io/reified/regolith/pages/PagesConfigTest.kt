package io.reified.regolith.pages

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PagesConfigTest {
    private val base = mapOf("REGOLITH_PAGES_DOMAIN" to "sites.example.test", "REGOLITH_PAGES_TOKEN" to TOKEN)

    @Test
    fun `each listener takes tls of its own, and neither implies the other`() {
        val public = PagesConfig.fromEnvironment(base + mapOf("REGOLITH_PAGES_TLS_CERT" to "/certs/sites.pem", "REGOLITH_PAGES_TLS_KEY" to "/certs/sites.key"))
        assertEquals(Path.of("/certs/sites.pem"), public.tls?.certificate)
        assertNull(public.apiTls, "tls for visitors leaves the intake as it was")

        val intake = PagesConfig.fromEnvironment(base + mapOf("REGOLITH_PAGES_API_TLS_CERT" to "/certs/pages.pem", "REGOLITH_PAGES_API_TLS_KEY" to "/certs/pages.key"))
        assertNull(intake.tls)
        assertEquals(Path.of("/certs/pages.key"), intake.apiTls?.key)
        assertNull(intake.apiTls?.clientCa)
    }

    @Test
    fun `half of an intake certificate is refused by name`() {
        val halfPair = assertFailsWith<IllegalStateException> { PagesConfig.fromEnvironment(base + mapOf("REGOLITH_PAGES_API_TLS_CERT" to "/certs/pages.pem")) }
        assertEquals("Set both REGOLITH_PAGES_API_TLS_CERT and REGOLITH_PAGES_API_TLS_KEY, or neither", halfPair.message)
        val caAlone = assertFailsWith<IllegalStateException> { PagesConfig.fromEnvironment(base + mapOf("REGOLITH_PAGES_API_TLS_CLIENT_CA" to "/certs/ca.pem")) }
        assertEquals("REGOLITH_PAGES_API_TLS_CLIENT_CA needs REGOLITH_PAGES_API_TLS_CERT and REGOLITH_PAGES_API_TLS_KEY", caAlone.message)
    }
}
