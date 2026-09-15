package io.reified.regolith.server.app

import io.reified.regolith.server.support.TestServer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrphanSitesTest {
    private fun TestServer.claimed(): Set<String> = sandboxes.all().mapNotNull { it.site?.value }.toSet()

    @Test
    fun `a site no record claims is found and taken down, and a claimed one is left alone`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("author").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>mine</h1>")
            server.sites.publish(id, "dist")
            val mine = server.siteOf(id)
            server.publisher.stray("b4d2f8g3h5")
            val orphans = OrphanSites(server.publisher)

            assertEquals(listOf("b4d2f8g3h5"), orphans.find(server.claimed()))
            assertEquals(listOf("b4d2f8g3h5"), orphans.delete(server.claimed()))

            assertNull(server.publisher.sites["b4d2f8g3h5"])
            assertTrue(server.publisher.sites[mine] != null, "a claimed site was taken down")
            assertEquals(emptyList(), orphans.find(server.claimed()))
        }
    }

    @Test
    fun `a server without a pages role has no orphaned sites`() = runBlocking {
        assertEquals(emptyList(), OrphanSites(publisher = null).find(emptySet()))
        assertEquals(emptyList(), OrphanSites(publisher = null).delete(emptySet()))
    }
}
