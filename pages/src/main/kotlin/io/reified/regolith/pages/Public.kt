package io.reified.regolith.pages

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * What visitors reach. It reads, and that is all it does: there is no route that changes anything, no
 * cookie is ever set, and a request that names no published site gets the same short answer as one
 * that names a file no release holds.
 */
fun Application.publicSites(config: PagesConfig, store: ReleaseStore) {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error(cause) { "Serving failed: host=[${call.request.host()}] path=[${call.request.path()}]" }
            call.harden(config)
            call.respondText("This page is unavailable.", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
        }
    }

    routing {
        get("{...}") {
            val site = call.site(config)
            val release = site?.let { store.currentRelease(it) }
            if (release == null) {
                call.harden(config)
                call.respondText("There is no site at this address.", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@get
            }
            call.serve(config, store, release)
        }
    }
}

/** The site a request is for: the label in front of the server's domain, and nothing else. */
private fun ApplicationCall.site(config: PagesConfig): SiteName? {
    val host = request.host().substringBefore(':').lowercase()
    val label = host.removeSuffix(".${config.domain}").takeIf { it != host } ?: return null

    return runCatching { config.siteName(label) }.getOrNull()
}

private suspend fun ApplicationCall.serve(config: PagesConfig, store: ReleaseStore, release: Release) {
    val wanted = SitePaths.requested(request.path())
    val entry = release.manifest.find(wanted)
        ?: release.manifest.find(SitePaths.directoryIndex(wanted))
        ?: return notFound(config, store, release)

    val tag = "\"${entry.hash}\""
    harden(config)
    response.header(HttpHeaders.ETag, tag)
    response.header(HttpHeaders.CacheControl, "public, max-age=$CACHE_SECONDS")

    if (request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any { it.trim().removePrefix("W/") == tag } == true) {
        return respond(HttpStatusCode.NotModified)
    }

    respond(LocalFileContent(store.blobPath(entry.hash).toFile(), ContentType.parse(entry.contentType)))
}

/** A site's own `404.html` when it has one, so a published app can answer for its unknown paths. */
private suspend fun ApplicationCall.notFound(config: PagesConfig, store: ReleaseStore, release: Release) {
    harden(config)
    response.header(HttpHeaders.CacheControl, "no-store")
    val page = release.manifest.find(SitePaths.NOT_FOUND_PAGE)
        ?: return respondText("Not found.", ContentType.Text.Plain, HttpStatusCode.NotFound)
    response.status(HttpStatusCode.NotFound)
    respond(LocalFileContent(store.blobPath(page.hash).toFile(), ContentType.parse(page.contentType)))
}

/**
 * Headers every answer carries. A published site is somebody else's code: it may not be framed, its
 * forms may not post to another origin, and its content type is taken at its word by no browser.
 */
private fun ApplicationCall.harden(config: PagesConfig) {
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Content-Security-Policy", config.csp)
}

private const val CACHE_SECONDS = 60
