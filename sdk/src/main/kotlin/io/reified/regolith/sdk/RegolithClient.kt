package io.reified.regolith.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.reified.regolith.protocol.ErrorBody
import io.reified.regolith.protocol.HealthInfo
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.protocol.SandboxPage
import io.reified.regolith.protocol.ServerInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * A client for one Regolith server.
 *
 * Pass an [httpClient] to share connections or to test against an in-process server; the client
 * never relies on its plugins, and closing this client leaves a passed one open. Redirects are
 * never followed, so the bearer token cannot be relayed to another origin.
 */
public class RegolithClient(
    baseUrl: String,
    private val token: String,
    httpClient: HttpClient? = null,
) : AutoCloseable {

    private val base = baseUrl.trimEnd('/')
    private val root = httpClient ?: HttpClient(CIO)
    private val ownsRoot = httpClient == null
    private val http = root.config {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout)
    }

    init {
        require(token.isNotBlank()) { "A Regolith API token is required" }
    }

    /** A handle to the sandbox [name]; nothing is sent until one of its operations is called. */
    public fun sandbox(name: String): Sandbox = Sandbox(this, name)

    /** Sandboxes carrying every label in [labels], one page at a time. */
    public suspend fun sandboxes(labels: Map<String, String> = emptyMap(), limit: Int? = null, cursor: String? = null): SandboxPage =
        call(HttpMethod.Get, "/v1/sandboxes", SandboxPage.serializer()) {
            labels.forEach { (key, value) -> url.parameters.append("label", "$key=$value") }
            limit?.let { url.parameters.append("limit", it.toString()) }
            cursor?.let { url.parameters.append("cursor", it) }
        }

    /** Server version, defaults and limits. */
    public suspend fun info(): ServerInfo = call(HttpMethod.Get, "/v1/info", ServerInfo.serializer())

    /** Health, readable with a failing status too: a failing server answers 503 with the same body. */
    public suspend fun health(): HealthInfo {
        val response = http.request("$base/v1/health") { method = HttpMethod.Get }

        return RegolithJson.lenient.decodeFromString(HealthInfo.serializer(), response.bodyAsText())
    }

    override fun close() {
        http.close()
        if (ownsRoot) root.close()
    }

    internal suspend fun <T> call(
        method: HttpMethod,
        path: String,
        response: KSerializer<T>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val result = send(method, path, timeoutMillis) {
            accept(ContentType.Application.Json)
            configure()
        }

        return RegolithJson.lenient.decodeFromString(response, result.bodyAsText())
    }

    internal suspend fun send(
        method: HttpMethod,
        path: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = http.request("$base$path") {
            this.method = method
            bearerAuth(token)
            timeout { requestTimeoutMillis = timeoutMillis }
            // called through its name: a bare build() would resolve to the builder's own build method.
            configure()
        }
        if (!response.status.isSuccess()) throw failure(response)

        return response
    }

    internal fun <T> HttpRequestBuilder.jsonBody(serializer: KSerializer<T>, value: T) {
        setBody(TextContent(RegolithJson.lenient.encodeToString(serializer, value), ContentType.Application.Json))
    }

    private suspend fun failure(response: HttpResponse): RegolithException {
        val text = response.bodyAsText()
        val status = response.status
        val body = try {
            RegolithJson.lenient.decodeFromString(ErrorBody.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        return if (body != null) {
            RegolithException(status.value, body.code, body.detail)
        } else {
            RegolithException(status.value, "http_${status.value}", text.take(MAX_ERROR_CHARS).ifBlank { status.description })
        }
    }

    internal companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val MAX_ERROR_CHARS = 500
    }
}

/**
 * A request the server refused. [code] is one of the stable codes in
 * [io.reified.regolith.protocol.ErrorCodes], or `http_<status>` when the response came from
 * something other than Regolith, such as a proxy in front of it.
 */
public class RegolithException(
    public val status: Int,
    public val code: String,
    message: String,
) : RuntimeException("$code: $message")
