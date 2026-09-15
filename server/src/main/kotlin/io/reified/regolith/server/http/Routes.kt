package io.reified.regolith.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.requireValid
import kotlinx.io.readByteArray

internal fun Route.sandboxRoutes(services: Services) = route("/sandboxes") {
    fun info(sandbox: Sandbox) =
        sandbox.toInfo(services.sessions.get(sandbox.name), services.sessions.lastEnd(sandbox.name), services.config.images)

    get {
        val query = call.request.queryParameters
        val labels = query.getAll("label").orEmpty().associate { raw ->
            val key = raw.substringBefore('=')
            requireValid('=' in raw && key.isNotEmpty()) { "A label filter is written as key=value" }
            key to raw.substringAfter('=')
        }
        val limit = query["limit"]?.let { it.toIntOrNull() ?: throw RegolithError.Invalid("limit must be a whole number") } ?: DEFAULT_PAGE
        val (page, next) = services.sandboxes.list(labels, limit, query["cursor"])
        call.respond(SandboxPage(page.map(::info), next))
    }

    route("/{name}") {
        put {
            val request = call.jsonBody(CreateSandboxRequest.serializer(), empty = CreateSandboxRequest())
            val (sandbox, created) = services.sandboxes.getOrCreate(call.sandboxName(), request.toDomain())
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, info(sandbox))
        }
        get {
            call.respond(info(services.sandboxes.require(call.sandboxName())))
        }
        patch {
            val request = call.jsonBody(UpdateSandboxRequest.serializer())
            call.respond(info(services.sandboxes.update(call.sandboxName(), request.toDomain())))
        }
        delete {
            services.sandboxes.delete(call.sandboxName())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/start") {
            call.respond(info(services.sandboxes.start(call.sandboxName())))
        }
        post("/stop") {
            val name = call.sandboxName()
            services.sandboxes.stop(name)
            call.respond(info(services.sandboxes.require(name)))
        }
        post("/publish") {
            val request = call.jsonBody(PublishRequest.serializer())
            call.respond(services.sites.publish(call.sandboxName(), request.path, request.site).toWire())
        }
        get("/site") {
            call.respond(services.sites.published(call.sandboxName(), call.request.queryParameters["site"]).toWire())
        }
        delete("/site") {
            services.sites.unpublish(call.sandboxName(), call.request.queryParameters["site"])
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

internal fun Route.execRoutes(services: Services) = route("/sandboxes/{name}/execs") {
    val execs = services.execs

    post {
        val request = call.jsonBody(ExecRequest.serializer())
        val sandbox = services.sandboxes.markUsed(call.sandboxName())
        val exec = execs.start(sandbox, request.toDomain(), call.request.headers[IDEMPOTENCY_KEY_HEADER])
        call.respond(HttpStatusCode.Created, exec.toInfo(execs.stdinOpen(exec.id)))
    }
    get {
        val name = call.sandboxName()
        services.sandboxes.require(name)
        call.respond(ExecPage(execs.list(name).map { it.toInfo(execs.stdinOpen(it.id)) }))
    }

    route("/{id}") {
        get {
            val exec = execs.await(call.sandboxName(), call.execId(), call.waitParameter())
            call.respond(exec.toInfo(execs.stdinOpen(exec.id)))
        }
        post("/cancel") {
            val exec = execs.cancel(call.sandboxName(), call.execId())
            call.respond(exec.toInfo(execs.stdinOpen(exec.id)))
        }
        post("/stdin") {
            val bytes = call.receiveChannel().readRemaining(MAX_STDIN_BYTES + 1L).readByteArray()
            if (bytes.size > MAX_STDIN_BYTES) throw RegolithError.TooLarge("One stdin write is limited to $MAX_STDIN_BYTES bytes")
            val close = call.request.queryParameters["close"] == "true"
            execs.writeStdin(call.sandboxName(), call.execId(), bytes, close)
            call.respond(HttpStatusCode.NoContent)
        }

        route("/output") {
            // the same resource as a live event stream, for browsers and clients without an sdk. only an
            // explicit text/event-stream selects it: a client sending */* keeps getting json pages.
            createChild(EventStreamRequested).apply {
                sse {
                    val name = call.sandboxName()
                    val id = call.execId()
                    var offset = (call.request.headers["Last-Event-ID"] ?: call.request.queryParameters["offset"])
                        ?.let { it.toLongOrNull() ?: throw RegolithError.Invalid("offset must be a whole number") } ?: 0L
                    while (true) {
                        val slice = execs.read(name, id, offset, PAGE_BYTES, MAX_WAIT)
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
                val maxBytes = query["maxBytes"]?.toIntOrNull()?.coerceIn(1, PAGE_BYTES) ?: PAGE_BYTES
                val slice = execs.read(call.sandboxName(), call.execId(), offset, maxBytes, call.waitParameter())
                call.respond(OutputPage(slice.frames.map { it.toWire() }, slice.nextOffset, slice.complete))
            }
        }
    }
}

internal fun Route.fileRoutes(services: Services) = route("/sandboxes/{name}/files") {
    val files = services.files

    fun io.ktor.server.application.ApplicationCall.pathParameter(): String =
        request.queryParameters["path"] ?: throw RegolithError.Invalid("The path query parameter is required")

    get("/content") {
        val name = call.sandboxName()
        val path = call.pathParameter()
        files.openForRead(name, path)
        call.respondOutputStream(ContentType.Application.OctetStream, HttpStatusCode.OK) { files.read(name, path, this) }
    }
    put("/content") {
        val entry = files.write(call.sandboxName(), call.pathParameter(), call.receiveStream(), call.request.contentLength())
        call.respond(entry.toWire())
    }
    get("/entries") {
        val path = call.pathParameter()
        call.respond(DirectoryListing(path, files.list(call.sandboxName(), path).map { it.toWire() }))
    }
    get("/stat") {
        call.respond(files.stat(call.sandboxName(), call.pathParameter()).toWire())
    }
    delete {
        files.delete(call.sandboxName(), call.pathParameter(), recursive = call.request.queryParameters["recursive"] == "true")
        call.respond(HttpStatusCode.NoContent)
    }
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
