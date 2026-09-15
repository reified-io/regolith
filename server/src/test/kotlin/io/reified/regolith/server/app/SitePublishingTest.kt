package io.reified.regolith.server.app

import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.ports.SiteLimits
import io.reified.regolith.server.support.FakePublisher
import io.reified.regolith.server.support.TestServer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class SitePublishingTest {
    @Test
    fun `a directory of the sandbox becomes a site, and the rest of the home stays private`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("demo").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>hi</h1>")
            server.runtime.place(id, "/home/sandbox/dist/assets/app.js", "run()")
            server.runtime.place(id, "/home/sandbox/secret.env", "TOKEN=1")

            val published = server.sites.publish(id, "dist")
            val site = server.siteOf(id)

            assertEquals("https://$site.example.test", published.url)
            assertEquals(setOf("index.html", "assets/app.js"), server.publisher.published.getValue(site).keys)
            assertEquals("<h1>hi</h1>", server.publisher.published.getValue(site)["index.html"])
        }
    }

    // the address is public, so it is the server's to make up: nothing of the sandbox may be read from it
    @Test
    fun `the address carries neither the sandbox nor its alias, and publishing again keeps it`() = runBlocking {
        TestServer().use { server ->
            val first = server.sandbox("telegram:123456789").id
            val second = server.sandbox("telegram:987654321").id
            server.runtime.place(first, "/home/sandbox/dist/index.html", "<h1>hi</h1>")
            server.runtime.place(second, "/home/sandbox/dist/index.html", "<h1>hi</h1>")

            val address = server.sites.publish(first, "dist").url
            val other = server.sites.publish(second, "dist").url

            assertNotEquals(other, address)
            assertEquals(address, server.sites.publish(first, "dist").url, "republishing moved the site")
            for (secret in listOf(first.value, "telegram", "123456789")) {
                assertFalse(secret in address, "the address gives away $secret")
            }
        }
    }

    // the pages role owns its caps; the control plane asks for them and refuses before copying anything
    @Test
    fun `a snapshot too large for the pages role never leaves the sandbox`() = runBlocking {
        TestServer().use { server ->
            val publisher = FakePublisher(SiteLimits(maxFiles = 1, maxFileBytes = 4, maxSiteBytes = 8))
            val sites = SitePublishing(server.sandboxes, server.sessions, server.runtime, server.config, publisher)
            val id = server.sandbox("small").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "far too long")

            val error = assertFailsWith<RegolithError.TooLarge> { sites.publish(id, "dist") }

            assertContains(error.message.orEmpty(), "larger than")
            assertEquals(emptyMap(), publisher.published)
            assertNull(server.sandboxes.require(id).site, "a refused publish took a label anyway")
        }
    }

    @Test
    fun `a server with no pages role says so instead of failing oddly`() = runBlocking {
        TestServer().use { server ->
            val sites = SitePublishing(server.sandboxes, server.sessions, server.runtime, server.config, publisher = null)
            val id = server.sandbox("lonely").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "hi")

            assertFailsWith<RegolithError.NotImplemented> { sites.publish(id, "dist") }
        }
    }

    @Test
    fun `a site is taken down without touching the sandbox`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("bye").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>bye</h1>")
            server.sites.publish(id, "dist")
            val site = server.siteOf(id)

            server.sites.unpublish(id)

            assertNull(server.publisher.sites[site])
            assertNull(server.sandboxes.require(id).site)
            assertFailsWith<RegolithError.NotFound> { server.sites.published(id) }
            assertEquals("<h1>bye</h1>", server.runtime.content(id, "/home/sandbox/dist/index.html"))
        }
    }

    // a site nobody can reach again would be served forever: the sandbox is the only thing that knows it
    @Test
    fun `deleting the sandbox takes its site down with it`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("going").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>here</h1>")
            server.sites.publish(id, "dist")
            val site = server.siteOf(id)

            server.sandboxes.delete(id)

            assertNull(server.publisher.sites[site])
        }
    }
}
