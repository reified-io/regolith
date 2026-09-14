package io.reified.regolith.pages

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.reified.regolith.protocol.pages.ReleaseFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

private const val HOST = "demo.example.test"

class PublicSitesTest {
    private fun siteTest(files: Map<String, String>, block: suspend ApplicationTestBuilder.() -> Unit) {
        val dir = Files.createTempDirectory("regolith-pages-public")

        try {
            val settings = config(dir)
            val store = ReleaseStore(dir, settings.limits)
            runBlocking {
                val site = SiteName.parse("demo")
                val started = store.startRelease(site, files.map { (path, body) -> ReleaseFile(path, sha256(body), body.length.toLong()) })
                files.values.forEach { store.putBlob(sha256(it), it.byteInputStream()) }
                store.activate(site, ReleaseId.parse(started.release))
            }
            testApplication {
                application { publicSites(settings, store) }
                block()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the root and a directory both mean their index`() = siteTest(
        mapOf("index.html" to "<h1>home</h1>", "about/index.html" to "<h1>about</h1>", "assets/app.js" to "run()"),
    ) {
        assertEquals("<h1>home</h1>", client.get("/") { host() }.bodyAsText())
        assertEquals("<h1>about</h1>", client.get("/about/") { host() }.bodyAsText())
        // a clean url with no trailing slash means the same page
        assertEquals("<h1>about</h1>", client.get("/about") { host() }.bodyAsText())

        val asset = client.get("/assets/app.js") { host() }
        assertEquals("run()", asset.bodyAsText())
        assertEquals("text/javascript; charset=utf-8", asset.headers[HttpHeaders.ContentType])
    }

    // a published site is somebody else's code: it may not be framed and may not post a form elsewhere
    @Test
    fun `every answer carries the hardening headers`() = siteTest(mapOf("index.html" to "<h1>home</h1>")) {
        val response = client.get("/") { host() }
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("frame-ancestors 'none'; form-action 'self'", response.headers["Content-Security-Policy"])
        assertNull(response.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `an unchanged file is answered with its hash instead of its bytes`() = siteTest(mapOf("index.html" to "<h1>home</h1>")) {
        val first = client.get("/") { host() }
        val tag = first.headers[HttpHeaders.ETag]
        assertEquals("\"${sha256("<h1>home</h1>")}\"", tag)

        val second = client.get("/") {
            host()
            header(HttpHeaders.IfNoneMatch, tag.orEmpty())
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
        assertEquals("", second.bodyAsText())
    }

    @Test
    fun `a site answers for its own unknown paths when it ships a page for them`() = siteTest(
        mapOf("index.html" to "<h1>home</h1>", "404.html" to "<h1>lost</h1>"),
    ) {
        val response = client.get("/nothing/here") { host() }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("<h1>lost</h1>", response.bodyAsText())
    }

    @Test
    fun `a host that names no published site gets nothing`() = siteTest(mapOf("index.html" to "<h1>home</h1>")) {
        for (host in listOf("nobody.example.test", "example.test", "demo.example.test.evil.test", "api.example.test")) {
            val response = client.get("/") { header(HttpHeaders.Host, host) }
            assertEquals(HttpStatusCode.NotFound, response.status, "$host was served something")
        }
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.host() {
    header(HttpHeaders.Host, HOST)
}
