package io.reified.regolith.pages

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.reified.regolith.protocol.ErrorBody
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.protocol.pages.ReleaseStarted
import io.reified.regolith.protocol.pages.SiteInfo
import io.reified.regolith.protocol.pages.SiteList
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TOKEN = "pages-test-token-that-is-long-enough"

internal fun config(dir: Path, domain: String = "example.test"): PagesConfig = PagesConfig(
    bind = "127.0.0.1",
    port = 0,
    apiBind = "127.0.0.1",
    apiPort = 0,
    token = TOKEN,
    domain = domain,
    scheme = "https",
    dir = dir,
    reserved = setOf("www", "api"),
    csp = "frame-ancestors 'none'; form-action 'self'",
    limits = PagesConfig.Limits(maxFiles = 10, maxFileBytes = 4096, maxSiteBytes = 8192, releasesKept = 2, maxSites = 3),
)

class PagesApiTest {
    private fun intakeTest(block: suspend ApplicationTestBuilder.(PagesConfig) -> Unit) {
        val dir = Files.createTempDirectory("regolith-pages-api")

        try {
            testApplication {
                val settings = config(dir)
                application { pagesIntake(settings, ReleaseStore(dir, settings.limits)) }
                block(settings)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a release is uploaded by content and goes live only when activated`() = intakeTest {
        val page = "<h1>hello</h1>"
        val started = client.post("/v1/sites/demo/releases") {
            bearer()
            contentType(ContentType.Application.Json)
            setBody("""{"files":[{"path":"index.html","hash":"${sha256(page)}","size":${page.length}}]}""")
        }
        assertEquals(HttpStatusCode.Created, started.status)
        val release = RegolithJson.lenient.decodeFromString(ReleaseStarted.serializer(), started.bodyAsText())
        assertEquals(listOf(sha256(page)), release.missing)

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/sites/demo") { bearer() }.status)

        assertEquals(HttpStatusCode.NoContent, client.put("/v1/blobs/${sha256(page)}") { bearer(); setBody(page) }.status)
        val activated = client.post("/v1/sites/demo/releases/${release.release}/activate") { bearer() }
        val info = RegolithJson.lenient.decodeFromString(SiteInfo.serializer(), activated.bodyAsText())

        assertEquals("https://demo.example.test", info.url)
        assertEquals(1, info.files)
        assertEquals(page.length.toLong(), info.bytes)

        val listed = RegolithJson.lenient.decodeFromString(SiteList.serializer(), client.get("/v1/sites") { bearer() }.bodyAsText())
        assertEquals(listOf("demo"), listed.sites.map { it.name })

        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/sites/demo") { bearer() }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/sites/demo") { bearer() }.status)
    }

    @Test
    fun `intake is closed without the token, and says so as a problem document`() = intakeTest {
        val response = client.get("/v1/sites")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("application/problem+json", response.contentType()?.withoutParameters()?.toString())
        val problem = RegolithJson.lenient.decodeFromString(ErrorBody.serializer(), response.bodyAsText())
        assertEquals(ErrorCodes.UNAUTHORIZED, problem.code)

        assertEquals(HttpStatusCode.OK, client.get("/v1/health").status)
    }

    @Test
    fun `a reserved label cannot be published`() = intakeTest {
        val response = client.post("/v1/sites/api/releases") {
            bearer()
            contentType(ContentType.Application.Json)
            setBody("""{"files":[{"path":"index.html","hash":"${sha256("x")}","size":1}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "reserved")
    }

    @Test
    fun `a path that leaves the site is refused before a byte is stored`() = intakeTest {
        val response = client.post("/v1/sites/demo/releases") {
            bearer()
            contentType(ContentType.Application.Json)
            setBody("""{"files":[{"path":"../escape.html","hash":"${sha256("x")}","size":1}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("stay inside the site" in response.bodyAsText())
    }
}

internal fun io.ktor.client.request.HttpRequestBuilder.bearer() {
    header(HttpHeaders.Authorization, "Bearer $TOKEN")
}
