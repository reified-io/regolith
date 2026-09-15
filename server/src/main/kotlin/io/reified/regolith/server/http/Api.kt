package io.reified.regolith.server.http

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.utils.io.readRemaining
import io.reified.regolith.protocol.CheckInfo
import io.reified.regolith.protocol.ErrorBody
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.HealthInfo
import io.reified.regolith.protocol.HealthStatus
import io.reified.regolith.protocol.PROBLEM_JSON
import io.reified.regolith.protocol.PROTOCOL_VERSION
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.protocol.ServerInfo
import io.reified.regolith.server.app.Services
import io.reified.regolith.server.domain.ExecId
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.SandboxId
import io.ktor.server.request.receiveChannel
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Installs the `/v1` API over [services]. */
fun Application.regolithApi(services: Services) {
    install(ContentNegotiation) { json(RegolithJson.strict) }
    install(SSE)
    install(BearerAuth) { token = services.config.token }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val (status, code, detail) = errorFor(cause)
            if (status == HttpStatusCode.InternalServerError) log.error(cause) { "Request failed: path=[${call.request.path()}]" }
            if (status == HttpStatusCode.ServiceUnavailable) call.response.headers.append(HttpHeaders.RetryAfter, "5")
            call.respondProblem(status, code, detail)
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondProblem(status, ErrorCodes.NOT_FOUND, "No such endpoint: ${call.request.path()}")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondProblem(status, ErrorCodes.INVALID_REQUEST, "Method not allowed on ${call.request.path()}")
        }
    }

    routing {
        get("/llms.txt") {
            call.respondText(LLMS_TXT, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
        }
        route("/v1") {
            get("/health") {
                val checks = services.health.snapshot()
                val ok = checks.values.all { it.ok }
                call.respond(
                    if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                    HealthInfo(
                        status = if (ok) HealthStatus.OK else HealthStatus.FAILING,
                        checks = checks.mapValues { (_, check) -> CheckInfo(if (check.ok) HealthStatus.OK else HealthStatus.FAILING, check.detail) },
                    ),
                )
            }
            get("/info") {
                call.respond(
                    ServerInfo(
                        version = services.version,
                        protocol = PROTOCOL_VERSION,
                        defaults = services.config.defaults.toWire(services.config.images.default),
                        limits = services.config.limits.toWire(services.config.images.allowed),
                        publishing = services.sites.available,
                    ),
                )
            }
            sandboxRoutes(services)
            execRoutes(services)
            fileRoutes(services)
        }
    }
}

/** Requires the bearer token on every endpoint except health; the token is compared in constant time. */
private class BearerAuthConfig {
    var token: String = ""
}

private val BearerAuth = createApplicationPlugin("RegolithBearerAuth", ::BearerAuthConfig) {
    val expected = pluginConfig.token.toByteArray()
    check(expected.isNotEmpty()) { "The API token is empty" }
    onCall { call ->
        if (call.request.path() in OPEN_PATHS) return@onCall
        val presented = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.toByteArray()
        if (presented == null || !MessageDigest.isEqual(presented, expected)) {
            call.respondProblem(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "A valid bearer token is required")
        }
    }
}

private const val BEARER_PREFIX = "Bearer "
private val OPEN_PATHS = setOf("/v1/health", "/llms.txt")

/** The endpoint reference for agents, bundled with the server so it always matches this build. */
private val LLMS_TXT: String = checkNotNull(object {}.javaClass.getResource("/llms.txt")) { "llms.txt is missing from the build" }.readText()

private fun errorFor(cause: Throwable): Triple<HttpStatusCode, String, String> = when (cause) {
    is RegolithError.Invalid -> Triple(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, cause.message.orEmpty())
    is RegolithError.NotFound -> Triple(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, cause.message.orEmpty())
    is RegolithError.Conflict -> Triple(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, cause.message.orEmpty())
    is RegolithError.Busy -> Triple(HttpStatusCode.Conflict, ErrorCodes.BUSY, cause.message.orEmpty())
    is RegolithError.CapacityExhausted -> Triple(HttpStatusCode.ServiceUnavailable, ErrorCodes.CAPACITY_EXHAUSTED, cause.message.orEmpty())
    is RegolithError.TooLarge -> Triple(HttpStatusCode.PayloadTooLarge, ErrorCodes.PAYLOAD_TOO_LARGE, cause.message.orEmpty())
    is RegolithError.InsufficientStorage -> Triple(HttpStatusCode.InsufficientStorage, ErrorCodes.INSUFFICIENT_STORAGE, cause.message.orEmpty())
    is RegolithError.Unavailable -> Triple(HttpStatusCode.ServiceUnavailable, ErrorCodes.UNAVAILABLE, cause.message.orEmpty())
    is RegolithError.NotImplemented -> Triple(HttpStatusCode.NotImplemented, ErrorCodes.NOT_IMPLEMENTED, cause.message.orEmpty())
    is SerializationException, is BadRequestException ->
        Triple(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, "Malformed request: ${cause.message.orEmpty().lineSequence().first()}")
    else -> Triple(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL, "The server failed to handle the request")
}

private val PROBLEM_TITLES = mapOf(
    ErrorCodes.UNAUTHORIZED to "Unauthorized",
    ErrorCodes.INVALID_REQUEST to "Invalid request",
    ErrorCodes.NOT_FOUND to "Not found",
    ErrorCodes.CONFLICT to "Conflict with the current state",
    ErrorCodes.BUSY to "Sandbox busy",
    ErrorCodes.CAPACITY_EXHAUSTED to "No session capacity",
    ErrorCodes.PAYLOAD_TOO_LARGE to "Payload too large",
    ErrorCodes.INSUFFICIENT_STORAGE to "Insufficient storage",
    ErrorCodes.UNAVAILABLE to "Unavailable",
    ErrorCodes.NOT_IMPLEMENTED to "Not implemented",
    ErrorCodes.INTERNAL to "Internal error",
)

private val PROBLEM_TYPE = ContentType.parse(PROBLEM_JSON)

/** Sends an RFC 9457 problem document; the only way an error leaves the server. */
internal suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, code: String, detail: String) {
    val body = ErrorBody(ErrorCodes.typeOf(code), PROBLEM_TITLES[code] ?: status.description, status.value, detail, code)
    respondText(RegolithJson.strict.encodeToString(ErrorBody.serializer(), body), PROBLEM_TYPE, status)
}

private const val MAX_JSON_BYTES = 1024 * 1024

/** Reads a JSON body with a size cap; an empty body yields [empty], or fails when there is none. */
internal suspend fun <T> ApplicationCall.jsonBody(serializer: KSerializer<T>, empty: T? = null): T {
    val bytes = receiveChannel().readRemaining(MAX_JSON_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_JSON_BYTES) throw RegolithError.TooLarge("JSON bodies are limited to $MAX_JSON_BYTES bytes")
    if (bytes.isEmpty()) return empty ?: throw RegolithError.Invalid("A JSON body is required")

    return RegolithJson.strict.decodeFromString(serializer, bytes.decodeToString())
}

internal fun ApplicationCall.sandboxId(): SandboxId = SandboxId.parse(parameters["id"].orEmpty())

internal fun ApplicationCall.execId(): ExecId = ExecId.parse(parameters["exec"].orEmpty())

/** The `maxBytes` a caller set: a positive whole number, or null when it set none. */
internal fun ApplicationCall.maxBytesParameter(): Long? {
    val raw = request.queryParameters["maxBytes"] ?: return null
    val value = raw.toLongOrNull() ?: throw RegolithError.Invalid("maxBytes must be a whole number")
    if (value <= 0) throw RegolithError.Invalid("maxBytes must be positive")

    return value
}

/** A bounded `waitSeconds` query parameter; absent means no waiting. */
internal fun ApplicationCall.waitParameter(max: Duration = MAX_WAIT): Duration {
    val raw = request.queryParameters["waitSeconds"] ?: return Duration.ZERO
    val seconds = raw.toIntOrNull() ?: throw RegolithError.Invalid("waitSeconds must be a whole number")
    if (seconds < 0) throw RegolithError.Invalid("waitSeconds must not be negative")

    return minOf(seconds.seconds, max)
}

internal val MAX_WAIT = 30.seconds
