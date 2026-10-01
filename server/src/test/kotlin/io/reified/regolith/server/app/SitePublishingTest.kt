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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.days

class SitePublishingTest {
    @Test
    fun `a directory of the sandbox becomes a site, and the rest of the home stays private`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("demo").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>hi</h1>")
            server.runtime.place(id, "/home/sandbox/dist/assets/app.js", "run()")
            server.runtime.place(id, "/home/sandbox/secret.env", "TOKEN=1")

            val published = server.sites.publish(id, "dist", until = null)
            val site = server.siteOf(id)

            assertEquals("https://$site.example.test", published.published.url)
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

            val address = server.sites.publish(first, "dist", until = null).published.url
            val other = server.sites.publish(second, "dist", until = null).published.url

            assertNotEquals(other, address)
            assertEquals(address, server.sites.publish(first, "dist", until = null).published.url, "republishing moved the site")
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
            val sites = SitePublishing(server.sandboxes, server.sessions, server.runtime, server.config, publisher, server.clock)
            val id = server.sandbox("small").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "far too long")

            val error = assertFailsWith<RegolithError.TooLarge> { sites.publish(id, "dist", until = null) }

            assertContains(error.message.orEmpty(), "larger than")
            assertEquals(emptyMap(), publisher.published)
            assertNull(server.sandboxes.require(id).site, "a refused publish took a label anyway")
        }
    }

    // the listing is a moment ago and the sandbox keeps writing: the copy itself is what is bounded
    @Test
    fun `a directory that grew past the caps after it was listed is still refused`() = runBlocking {
        TestServer().use { server ->
            val publisher = FakePublisher(SiteLimits(maxFiles = 10, maxFileBytes = 64, maxSiteBytes = 64))
            val id = server.sandbox("growing").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "small")
            val runtime = object : io.reified.regolith.server.ports.SandboxRuntime by server.runtime {
                override suspend fun tree(sandbox: io.reified.regolith.server.domain.SandboxId, path: String, maxFiles: Int) =
                    server.runtime.tree(sandbox, path, maxFiles).also {
                        server.runtime.place(sandbox, "/home/sandbox/dist/late.bin", "x".repeat(100))
                    }
            }
            val sites = SitePublishing(server.sandboxes, server.sessions, runtime, server.config, publisher, server.clock)

            assertFailsWith<RegolithError.TooLarge> { sites.publish(id, "dist", until = null) }

            assertEquals(emptyMap(), publisher.published)
        }
    }

    @Test
    fun `a site without an index page says so, since the numbers cannot`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("pageless").id
            server.runtime.place(id, "/home/sandbox/dist/docs/index.html", "<h1>nested</h1>")

            assertFalse(server.sites.publish(id, "dist", until = null).published.hasIndex)
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>top</h1>")
            assertTrue(server.sites.publish(id, "dist", until = null).published.hasIndex)
        }
    }

    @Test
    fun `a server with no pages role says so instead of failing oddly`() = runBlocking<Unit> {
        TestServer().use { server ->
            val sites = SitePublishing(server.sandboxes, server.sessions, server.runtime, server.config, publisher = null, server.clock)
            val id = server.sandbox("lonely").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "hi")

            assertFailsWith<RegolithError.NotImplemented> { sites.publish(id, "dist", until = null) }
        }
    }

    @Test
    fun `a site is taken down without touching the sandbox`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("bye").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>bye</h1>")
            server.sites.publish(id, "dist", until = null)
            val site = server.siteOf(id)

            server.sites.unpublish(id)

            assertNull(server.publisher.sites[site])
            assertNull(server.sandboxes.require(id).site)
            assertFailsWith<RegolithError.NotFound> { server.sites.published(id) }
            assertEquals("<h1>bye</h1>", server.runtime.content(id, "/home/sandbox/dist/index.html"))
        }
    }

    @Test
    fun `a publish without a term gets the default from now, never shortening a longer one, and the term moves either way`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("termed").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>hi</h1>")
            val start = server.clock.now()

            assertEquals(start + 30.days, server.sites.publish(id, "dist", until = null).until)
            server.clock.advance(5.days)
            assertEquals(start + 35.days, server.sites.publish(id, "dist", until = null).until, "updating the files did not renew the term")

            assertEquals(start + 200.days, server.sites.term(id, start + 200.days).until)
            assertEquals(start + 200.days, server.sites.publish(id, "dist", until = null).until, "updating the files shortened a term set on purpose")
            assertEquals(start + 6.days, server.sites.term(id, start + 6.days).until)
            assertEquals(start + 6.days, server.sites.published(id).until)
            assertEquals(start + 6.days, server.sandboxes.require(id).site?.until)
        }
    }

    @Test
    fun `a term already over, or beyond the server's ceiling, is refused`() = runBlocking<Unit> {
        TestServer(mapOf("REGOLITH_MAX_SITE_DAYS" to "90")).use { server ->
            val id = server.sandbox("bounded").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>hi</h1>")
            val now = server.clock.now()

            assertFailsWith<RegolithError.NotFound> { server.sites.term(id, now + 1.days) }
            assertFailsWith<RegolithError.Invalid> { server.sites.publish(id, "dist", until = now - 1.days) }
            assertNull(server.sandboxes.require(id).site, "a refused term took a label anyway")
            server.sites.publish(id, "dist", until = now + 90.days)
            assertFailsWith<RegolithError.Invalid> { server.sites.term(id, now + 91.days) }
        }
    }

    // the term is the truth and the sweep only carries it out: what a caller sees must not depend on
    // whether a tick has happened since the term ran out
    @Test
    fun `a site past its term is gone before the sweep reaches it`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("lapsed").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>hi</h1>")
            val first = server.sites.publish(id, "dist", until = server.clock.now() + 2.days)
            val site = server.siteOf(id)
            server.clock.advance(3.days)

            assertFailsWith<RegolithError.NotFound> { server.sites.published(id) }
            assertFailsWith<RegolithError.NotFound> { server.sites.term(id, server.clock.now() + 1.days) }
            assertTrue(server.publisher.sites[site] != null, "reading a lapsed site took it down")

            val again = server.sites.publish(id, "dist", until = null)

            assertNotEquals(first.published.url, again.published.url, "publishing renewed the lapsed site at its old address")
            assertNull(server.publisher.sites[site], "the lapsed site stayed up beside the new one")
            assertEquals(server.clock.now() + 30.days, again.until)
        }
    }

    @Test
    fun `taking down a lapsed site does what the sweep would`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("lapsed").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>hi</h1>")
            server.sites.publish(id, "dist", until = server.clock.now() + 2.days)
            val site = server.siteOf(id)
            server.clock.advance(3.days)

            server.sites.unpublish(id)

            assertNull(server.publisher.sites[site])
            assertNull(server.sandboxes.require(id).site)
        }
    }

    // a site nobody can reach again would be served forever: the sandbox is the only thing that knows it
    @Test
    fun `deleting the sandbox takes its site down with it`() = runBlocking {
        TestServer().use { server ->
            val id = server.sandbox("going").id
            server.runtime.place(id, "/home/sandbox/dist/index.html", "<h1>here</h1>")
            server.sites.publish(id, "dist", until = null)
            val site = server.siteOf(id)

            server.sandboxes.delete(id)

            assertNull(server.publisher.sites[site])
        }
    }
}
