package io.reified.regolith.pages

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.reified.regolith.protocol.ErrorBody
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.PROBLEM_JSON
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.protocol.pages.PagesLimits
import io.reified.regolith.protocol.pages.ReleaseRequest
import io.reified.regolith.protocol.pages.ReleaseStarted
import io.reified.regolith.protocol.pages.SiteInfo
import io.reified.regolith.protocol.pages.SiteList
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.security.MessageDigest

private val log = KotlinLogging.logger {}

/**
 * Where releases arrive. It listens on the loopback address unless an operator says otherwise, takes
 * one bearer token, and is the only surface that can change anything a visitor sees.
 *
 * A release is uploaded by content: the caller says which files it will serve and what they hash to,
 * the server answers with the blobs it does not already hold, and the release becomes visible only
 * when it is activated. Nothing is served from a half-finished upload.
 */
fun Application.pagesIntake(config: PagesConfig, store: ReleaseStore) {
    install(IntakeAuth) { token = config.token }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val (status, code, detail) = problemFor(cause)
            if (status == HttpStatusCode.InternalServerError) log.error(cause) { "Intake failed: path=[${call.request.path()}]" }
            call.respondProblem(status, code, detail)
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondProblem(status, ErrorCodes.NOT_FOUND, "No such endpoint: ${call.request.path()}")
        }
    }

    routing {
        route("/v1") {
            get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }

            get("/limits") {
                val limits = config.limits
                call.respondJson(
                    PagesLimits.serializer(),
                    PagesLimits(limits.maxFiles, limits.maxFileBytes, limits.maxSiteBytes, limits.releasesKept, limits.maxSites),
                )
            }

            put("/blobs/{hash}") {
                store.putBlob(call.parameters["hash"].orEmpty().lowercase(), call.receiveStream())
                call.respond(HttpStatusCode.NoContent)
            }

            route("/sites") {
                get {
                    val sites = store.sites().map { (name, release) -> info(config, name, release) }
                    call.respondJson(SiteList.serializer(), SiteList(sites))
                }

                route("/{site}") {
                    get {
                        val site = config.siteName(call.parameters["site"].orEmpty())
                        val release = store.currentRelease(site) ?: throw PagesError.NotFound("Nothing is published at `$site`")
                        call.respondJson(SiteInfo.serializer(), info(config, site, release))
                    }

                    delete {
                        store.delete(config.siteName(call.parameters["site"].orEmpty()))
                        call.respond(HttpStatusCode.NoContent)
                    }

                    post("/releases") {
                        val site = config.siteName(call.parameters["site"].orEmpty())
                        val request = call.jsonBody(ReleaseRequest.serializer())
                        call.respondJson(ReleaseStarted.serializer(), store.startRelease(site, request.files), HttpStatusCode.Created)
                    }

                    post("/releases/{release}/activate") {
                        val site = config.siteName(call.parameters["site"].orEmpty())
                        val release = store.activate(site, ReleaseId.parse(call.parameters["release"].orEmpty()))
                        call.respondJson(SiteInfo.serializer(), info(config, site, release))
                    }
                }
            }
        }
    }
}

private fun info(config: PagesConfig, site: SiteName, release: Release) = SiteInfo(
    name = site.value,
    url = config.url(site),
    release = release.id,
    files = release.manifest.files.size,
    bytes = release.manifest.bytes,
    publishedAt = release.createdAt,
)

private class IntakeAuthConfig {
    var token: String = ""
}

private val IntakeAuth = createApplicationPlugin("RegolithPagesAuth", ::IntakeAuthConfig) {
    val expected = pluginConfig.token.toByteArray()
    check(expected.isNotEmpty()) { "The intake token is empty" }
    onCall { call ->
        if (call.request.path() == "/v1/health") return@onCall
        val presented = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
            ?.substring(BEARER.length)
            ?.trim()
            ?.toByteArray()
        if (presented == null || !MessageDigest.isEqual(presented, expected)) {
            call.respondProblem(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "A valid bearer token is required")
        }
    }
}

private const val BEARER = "Bearer "
private const val MAX_JSON_BYTES = 4 * 1024 * 1024

private fun problemFor(cause: Throwable): Triple<HttpStatusCode, String, String> = when (cause) {
    is PagesError.Invalid -> Triple(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, cause.message.orEmpty())
    is PagesError.NotFound -> Triple(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, cause.message.orEmpty())
    is PagesError.Conflict -> Triple(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, cause.message.orEmpty())
    is PagesError.TooLarge -> Triple(HttpStatusCode.PayloadTooLarge, ErrorCodes.PAYLOAD_TOO_LARGE, cause.message.orEmpty())
    is SerializationException, is BadRequestException ->
        Triple(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, "Malformed request: ${cause.message.orEmpty().lineSequence().first()}")
    else -> Triple(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL, "The server failed to handle the request")
}

private val PROBLEM_TITLES = mapOf(
    ErrorCodes.UNAUTHORIZED to "Unauthorized",
    ErrorCodes.INVALID_REQUEST to "Invalid request",
    ErrorCodes.NOT_FOUND to "Not found",
    ErrorCodes.CONFLICT to "Conflict with the current state",
    ErrorCodes.PAYLOAD_TOO_LARGE to "Payload too large",
    ErrorCodes.INTERNAL to "Internal error",
)

private val PROBLEM_TYPE = ContentType.parse(PROBLEM_JSON)

internal suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, code: String, detail: String) {
    val body = ErrorBody(ErrorCodes.typeOf(code), PROBLEM_TITLES[code] ?: status.description, status.value, detail, code)
    respondText(RegolithJson.strict.encodeToString(ErrorBody.serializer(), body), PROBLEM_TYPE, status)
}

private suspend fun <T> ApplicationCall.respondJson(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(RegolithJson.strict.encodeToString(serializer, value), ContentType.Application.Json, status)
}

private suspend fun <T> ApplicationCall.jsonBody(serializer: KSerializer<T>): T {
    val bytes = receiveChannel().readRemaining(MAX_JSON_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_JSON_BYTES) throw PagesError.TooLarge("JSON bodies are limited to $MAX_JSON_BYTES bytes")
    if (bytes.isEmpty()) throw PagesError.Invalid("A JSON body is required")

    return RegolithJson.strict.decodeFromString(serializer, bytes.decodeToString())
}
