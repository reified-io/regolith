package io.reified.regolith.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.readRemaining
import io.reified.regolith.protocol.CreateSandboxRequest
import io.reified.regolith.protocol.DirectoryListing
import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecPage
import io.reified.regolith.protocol.ExecRequest
import io.reified.regolith.protocol.IDEMPOTENCY_KEY_HEADER
import io.reified.regolith.protocol.OutputFrame
import io.reified.regolith.protocol.OutputPage
import io.reified.regolith.protocol.PublishRequest
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.protocol.SandboxPage
import io.reified.regolith.protocol.UpdateSandboxRequest
import io.reified.regolith.server.app.Services
import io.reified.regolith.server.domain.Alias
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.requireValid
import kotlinx.io.readByteArray
import kotlin.time.Duration

internal fun Route.sandboxRoutes(services: Services) = route("/sandboxes") {
    fun info(sandbox: Sandbox) =
        sandbox.toInfo(services.sessions.get(sandbox.id), services.sessions.lastEnd(sandbox.id), services.config.images)

    get {
        val query = call.request.queryParameters
        query["alias"]?.let { raw ->
            val found = services.sandboxes.byAlias(Alias.parse(raw))

            return@get call.respond(SandboxPage(listOfNotNull(found?.let(::info))))
        }
        val labels = query.getAll("label").orEmpty().associate { raw ->
            val key = raw.substringBefore('=')
            requireValid('=' in raw && key.isNotEmpty()) { "A label filter is written as key=value" }
            key to raw.substringAfter('=')
        }
        val limit = query["limit"]?.let { it.toIntOrNull() ?: throw RegolithError.Invalid("limit must be a whole number") } ?: DEFAULT_PAGE
        val (page, next) = services.sandboxes.list(labels, limit, query["cursor"])
        call.respond(SandboxPage(page.map(::info), next))
    }
    post {
        val request = call.jsonBody(CreateSandboxRequest.serializer(), empty = CreateSandboxRequest())
        val (sandbox, created) = services.sandboxes.create(request.alias?.let(Alias::parse), request.toDomain())
        call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, info(sandbox))
    }

    route("/{id}") {
        get {
            call.respond(info(services.sandboxes.require(call.sandboxId())))
        }
        patch {
            val request = call.jsonBody(UpdateSandboxRequest.serializer())
            call.respond(info(services.sandboxes.update(call.sandboxId(), request.toDomain())))
        }
        delete {
            services.sandboxes.delete(call.sandboxId())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/start") {
            call.respond(info(services.sandboxes.start(call.sandboxId())))
        }
        post("/stop") {
            val id = call.sandboxId()
            services.sandboxes.stop(id)
            call.respond(info(services.sandboxes.require(id)))
        }
        post("/site") {
            val request = call.jsonBody(PublishRequest.serializer())
            call.respond(services.sites.publish(call.sandboxId(), request.path).toWire())
        }
        get("/site") {
            call.respond(services.sites.published(call.sandboxId()).toWire())
        }
        delete("/site") {
            services.sites.unpublish(call.sandboxId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

internal fun Route.execRoutes(services: Services) = route("/sandboxes/{id}/execs") {
    val execs = services.execs

    post {
        val request = call.jsonBody(ExecRequest.serializer())
        val sandbox = services.sandboxes.markUsed(call.sandboxId())
        val exec = execs.start(sandbox, request.toDomain(), call.request.headers[IDEMPOTENCY_KEY_HEADER])
        call.respond(HttpStatusCode.Created, exec.toInfo(execs.stdinOpen(exec.id)))
    }
    get {
        val sandbox = call.sandboxId()
        services.sandboxes.require(sandbox)
        call.respond(ExecPage(execs.list(sandbox).map { it.toInfo(execs.stdinOpen(it.id)) }))
    }

    route("/{exec}") {
        get {
            val exec = execs.await(call.sandboxId(), call.execId(), call.waitParameter())
            call.respond(exec.toInfo(execs.stdinOpen(exec.id)))
        }
        post("/cancel") {
            val exec = execs.cancel(call.sandboxId(), call.execId())
            call.respond(exec.toInfo(execs.stdinOpen(exec.id)))
        }
        post("/stdin") {
            val bytes = call.receiveChannel().readRemaining(MAX_STDIN_BYTES + 1L).readByteArray()
            if (bytes.size > MAX_STDIN_BYTES) throw RegolithError.TooLarge("One stdin write is limited to $MAX_STDIN_BYTES bytes")
            val close = call.request.queryParameters["close"] == "true"
            execs.writeStdin(call.sandboxId(), call.execId(), bytes, close)
            call.respond(HttpStatusCode.NoContent)
        }

        route("/output") {
            // the same resource as a live event stream, for browsers and clients without an sdk. only an
            // explicit text/event-stream selects it: a client sending */* keeps getting json pages.
            createChild(EventStreamRequested).apply {
                // a stream that cannot start is refused here, while the answer can still be a problem
                // document: once sse has sent its headers an error only ends the stream, and a client
                // reads an empty one as a command that printed nothing.
                install(BeforeStream) {
                    check = { call -> execs.read(call.sandboxId(), call.execId(), call.streamOffset(), maxBytes = 1, wait = Duration.ZERO) }
                }
                sse {
                    val sandbox = call.sandboxId()
                    val id = call.execId()
                    var offset = call.streamOffset()
                    while (true) {
                        val slice = execs.read(sandbox, id, offset, PAGE_BYTES, MAX_WAIT)
                        for (frame in slice.frames) {
                            val wire = frame.toWire()
                            send(ServerSentEvent(RegolithJson.strict.encodeToString(OutputFrame.serializer(), wire), wire.kind.name.lowercase(), frame.end.toString()))
                        }
                        offset = slice.nextOffset
                        if (slice.complete) {
                            val info = slice.exec.toInfo(stdinOpen = false)
                            send(ServerSentEvent(RegolithJson.strict.encodeToString(ExecInfo.serializer(), info), "end", offset.toString()))
                            break
                        }
                        if (slice.frames.isEmpty()) send(ServerSentEvent(comments = "waiting"))
                    }
                }
            }
            get {
                val query = call.request.queryParameters
                val offset = query["offset"]?.let { it.toLongOrNull() ?: throw RegolithError.Invalid("offset must be a whole number") } ?: 0L
                val maxBytes = call.maxBytesParameter()?.coerceAtMost(PAGE_BYTES.toLong())?.toInt() ?: PAGE_BYTES
                val slice = execs.read(call.sandboxId(), call.execId(), offset, maxBytes, call.waitParameter())
                val exec = slice.exec.toInfo(execs.stdinOpen(slice.exec.id))
                call.respond(OutputPage(slice.frames.map { it.toWire() }, slice.nextOffset, slice.complete, exec))
            }
        }
    }
}

internal fun Route.fileRoutes(services: Services) = route("/sandboxes/{id}/files") {
    val files = services.files

    fun ApplicationCall.pathParameter(): String =
        request.queryParameters["path"] ?: throw RegolithError.Invalid("The path query parameter is required")

    get("/content") {
        val sandbox = call.sandboxId()
        val path = call.pathParameter()
        val limit = files.openForRead(sandbox, path, call.maxBytesParameter())
        call.respondOutputStream(ContentType.Application.OctetStream, HttpStatusCode.OK) { files.read(sandbox, path, this, limit) }
    }
    put("/content") {
        val entry = files.write(call.sandboxId(), call.pathParameter(), call.receiveStream(), call.request.contentLength())
        call.respond(entry.toWire())
    }
    get("/entries") {
        val path = call.pathParameter()
        call.respond(DirectoryListing(path, files.list(call.sandboxId(), path).map { it.toWire() }))
    }
    get("/stat") {
        call.respond(files.stat(call.sandboxId(), call.pathParameter()).toWire())
    }
    delete {
        files.delete(call.sandboxId(), call.pathParameter(), recursive = call.request.queryParameters["recursive"] == "true")
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun ApplicationCall.streamOffset(): Long = (request.headers["Last-Event-ID"] ?: request.queryParameters["offset"])
    ?.let { it.toLongOrNull() ?: throw RegolithError.Invalid("offset must be a whole number") } ?: 0L

private class BeforeStreamConfig {
    var check: suspend (ApplicationCall) -> Unit = {}
}

private val BeforeStream = createRouteScopedPlugin("BeforeStream", ::BeforeStreamConfig) {
    val check = pluginConfig.check
    onCall { call -> check(call) }
}

private object EventStreamRequested : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation {
        val accept = context.call.request.headers.getAll(HttpHeaders.Accept).orEmpty()

        return if (accept.any { ContentType.Text.EventStream.toString() in it }) RouteSelectorEvaluation.Constant else RouteSelectorEvaluation.Failed
    }

    override fun toString(): String = "(accept text/event-stream)"
}

private const val DEFAULT_PAGE = 100
private const val PAGE_BYTES = 256 * 1024
private const val MAX_STDIN_BYTES = 1024 * 1024
