package io.reified.regolith.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A client for one Regolith server.
 *
 * Pass an [httpClient] to share connections or to test against an in-process server; the client
 * never relies on its plugins, and closing this client leaves a passed one open. Redirects are
 * never followed, so the bearer token cannot be relayed to another origin.
 *
 * Every operation fails in one of two ways: [RegolithException] when the server answered with a
 * refusal, and [RegolithConnectionException] when no answer came, whichever engine [httpClient] runs.
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
    public suspend fun health(): HealthInfo = exchange {
        val response = http.request("$base/v1/health") { method = HttpMethod.Get }
        RegolithJson.lenient.decodeFromString(HealthInfo.serializer(), response.bodyAsText())
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
    ): T = exchange {
        val result = answer(method, path, timeoutMillis) {
            accept(ContentType.Application.Json)
            configure()
        }
        RegolithJson.lenient.decodeFromString(response, result.bodyAsText())
    }

    internal suspend fun send(
        method: HttpMethod,
        path: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = exchange { answer(method, path, timeoutMillis, configure) }

    /**
     * Like [send], but hands the response to [read] while its body is still arriving, so a body that is
     * large, or larger than its reader allows, is never held whole.
     */
    internal suspend fun <T> stream(
        method: HttpMethod,
        path: String,
        configure: HttpRequestBuilder.() -> Unit,
        read: suspend (HttpResponse) -> T,
    ): T = exchange {
        http.prepareRequest("$base$path") { authorized(method, DEFAULT_TIMEOUT_MILLIS, configure) }.execute { response ->
            if (!response.status.isSuccess()) throw failure(response)
            read(response)
        }
    }

    private suspend fun answer(
        method: HttpMethod,
        path: String,
        timeoutMillis: Long,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        val response = http.request("$base$path") { authorized(method, timeoutMillis, configure) }
        if (!response.status.isSuccess()) throw failure(response)

        return response
    }

    /**
     * One exchange with the server. Every engine fails to connect, resolve or finish in time in its own
     * exception types, and names the address in them; a caller gets one type, and no address.
     */
    private inline fun <T> exchange(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw RegolithConnectionException(e)
    } catch (e: UnresolvedAddressException) {
        throw RegolithConnectionException(e)
    }

    private fun HttpRequestBuilder.authorized(method: HttpMethod, timeoutMillis: Long, configure: HttpRequestBuilder.() -> Unit) {
        this.method = method
        bearerAuth(token)
        timeout { requestTimeoutMillis = timeoutMillis }
        // called through its name: a bare build() would resolve to the builder's own build method.
        configure()
    }

    internal fun <T> HttpRequestBuilder.jsonBody(serializer: KSerializer<T>, value: T) {
        setBody(TextContent(RegolithJson.lenient.encodeToString(serializer, value), ContentType.Application.Json))
    }

    private suspend fun failure(response: HttpResponse): RegolithException {
        val text = response.bodyAsText()
        val status = response.status
        // only the delay form: a server that names a date instead leaves the wait to the caller.
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds
        val body = try {
            RegolithJson.lenient.decodeFromString(ErrorBody.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        return if (body != null) {
            RegolithException(status.value, body.code, body.detail, retryAfter)
        } else {
            RegolithException(status.value, "http_${status.value}", text.take(MAX_ERROR_CHARS).ifBlank { status.description }, retryAfter)
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
 * something other than Regolith, such as a proxy in front of it. [detail] explains this refusal to
 * people, and [retryAfter] is how long the server asked a client to wait before trying again, when it
 * asked.
 */
public class RegolithException(
    public val status: Int,
    public val code: String,
    public val detail: String,
    public val retryAfter: Duration? = null,
) : RuntimeException("$code: $detail")

/**
 * No answer came from the server: it could not be reached, or it did not respond in time. The message
 * never names the server's address, so it can be shown as it is; [cause] holds what the HTTP engine
 * threw.
 */
public class RegolithConnectionException(cause: Throwable) :
    IOException("No answer from the Regolith server: it could not be reached or did not respond in time", cause)
