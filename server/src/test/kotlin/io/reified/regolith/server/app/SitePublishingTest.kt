package io.reified.regolith.server.app

import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.ports.SiteLimits
import io.reified.regolith.server.support.FakePublisher
import io.reified.regolith.server.support.TestServer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class SitePublishingTest {
    @Test
    fun `a directory of the sandbox becomes a site, and the rest of the home stays private`() = runBlocking {
        TestServer().use { server ->
            val name = server.sandboxes.getOrCreate(SandboxName.parse("demo"), SandboxRequest()).first.name
            server.runtime.place(name, "/home/sandbox/dist/index.html", "<h1>hi</h1>")
            server.runtime.place(name, "/home/sandbox/dist/assets/app.js", "run()")
            server.runtime.place(name, "/home/sandbox/secret.env", "TOKEN=1")

            val published = server.sites.publish(name, "dist", site = null)

            assertEquals("demo", published.site)
            assertEquals("https://demo.example.test", published.url)
            assertEquals(setOf("index.html", "assets/app.js"), server.publisher.published.getValue("demo").keys)
            assertEquals("<h1>hi</h1>", server.publisher.published.getValue("demo")["index.html"])
        }
    }

    // the pages role owns its caps; the control plane asks for them and refuses before copying anything
    @Test
    fun `a snapshot too large for the pages role never leaves the sandbox`() = runBlocking {
        TestServer().use { server ->
            val publisher = FakePublisher(SiteLimits(maxFiles = 1, maxFileBytes = 4, maxSiteBytes = 8))
            val sites = SitePublishing(server.sandboxes, server.sessions, server.runtime, server.config, publisher)
            val name = server.sandboxes.getOrCreate(SandboxName.parse("small"), SandboxRequest()).first.name
            server.runtime.place(name, "/home/sandbox/dist/index.html", "far too long")

            val error = assertFailsWith<RegolithError.TooLarge> { sites.publish(name, "dist", site = null) }
            assertContains(error.message.orEmpty(), "larger than")
            assertEquals(emptyMap(), publisher.published)
        }
    }

    @Test
    fun `a server with no pages role says so instead of failing oddly`() = runBlocking {
        TestServer().use { server ->
            val sites = SitePublishing(server.sandboxes, server.sessions, server.runtime, server.config, publisher = null)
            val name = server.sandboxes.getOrCreate(SandboxName.parse("lonely"), SandboxRequest()).first.name
            server.runtime.place(name, "/home/sandbox/dist/index.html", "hi")

            assertFailsWith<RegolithError.NotImplemented> { sites.publish(name, "dist", site = null) }
        }
    }

    @Test
    fun `a site is taken down without touching the sandbox`() = runBlocking {
        TestServer().use { server ->
            val name = server.sandboxes.getOrCreate(SandboxName.parse("bye"), SandboxRequest()).first.name
            server.runtime.place(name, "/home/sandbox/dist/index.html", "<h1>bye</h1>")
            server.sites.publish(name, "dist", site = null)

            server.sites.unpublish(name, site = null)

            assertNull(server.publisher.sites["bye"])
            assertFailsWith<RegolithError.NotFound> { server.sites.published(name, site = null) }
            assertEquals("<h1>bye</h1>", server.runtime.content(name, "/home/sandbox/dist/index.html"))
        }
    }
}
