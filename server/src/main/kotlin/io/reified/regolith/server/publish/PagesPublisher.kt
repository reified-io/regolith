package io.reified.regolith.server.publish

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.reified.regolith.protocol.ErrorBody
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.protocol.pages.PagesLimits
import io.reified.regolith.protocol.pages.ReleaseFile
import io.reified.regolith.protocol.pages.ReleaseRequest
import io.reified.regolith.protocol.pages.ReleaseStarted
import io.reified.regolith.protocol.pages.SiteInfo
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.ports.PublishedSite
import io.reified.regolith.server.ports.SiteLimits
import io.reified.regolith.server.ports.SitePublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/**
 * The pages role, reached over HTTP. It is the one place that knows that API.
 *
 * A release is uploaded by content: the manifest goes first, the server answers with the blobs it does
 * not already hold, and only those are sent. Republishing a site that changed one page therefore costs
 * one file, and the release becomes visible only when it is activated.
 */
class PagesPublisher(
    baseUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient(CIO) { followRedirects = false; expectSuccess = false },
) : SitePublisher, AutoCloseable {

    private val base = baseUrl.trimEnd('/')

    @Volatile
    private var known: SiteLimits? = null

    override suspend fun limits(): SiteLimits {
        known?.let { return it }
        val limits = call(HttpMethod.Get, "/v1/limits", PagesLimits.serializer())

        return SiteLimits(limits.maxFiles, limits.maxFileBytes, limits.maxSiteBytes).also { known = it }
    }

    override suspend fun publish(site: String, snapshot: Path): PublishedSite {
        val files = withContext(Dispatchers.IO) {
            Files.walk(snapshot).use { paths ->
                paths.filter { it.isRegularFile() }.toList().map { file ->
                    ReleaseFile(file.relativeTo(snapshot).joinToString("/"), sha256(file), Files.size(file))
                }
            }
        }
        if (files.isEmpty()) throw RegolithError.Invalid("There is nothing to publish: the snapshot holds no files")

        val started = call(HttpMethod.Post, "/v1/sites/$site/releases", ReleaseStarted.serializer()) {
            contentType(ContentType.Application.Json)
            setBody(RegolithJson.strict.encodeToString(ReleaseRequest.serializer(), ReleaseRequest(files)))
        }
        val wanted = started.missing.toSet()

        for (file in files.distinctBy { it.hash }.filter { it.hash in wanted }) {
            val bytes = withContext(Dispatchers.IO) { snapshot.resolve(file.path).readBytes() }
            send(HttpMethod.Put, "/v1/blobs/${file.hash}") { setBody(bytes) }
        }

        return call(HttpMethod.Post, "/v1/sites/$site/releases/${started.release}/activate", SiteInfo.serializer()).toDomain()
    }

    override suspend fun published(site: String): PublishedSite? = try {
        call(HttpMethod.Get, "/v1/sites/$site", SiteInfo.serializer()).toDomain()
    } catch (e: RegolithError.NotFound) {
        null
    }

    override suspend fun unpublish(site: String) {
        send(HttpMethod.Delete, "/v1/sites/$site")
    }

    override fun close() = http.close()

    private suspend fun <T> call(method: HttpMethod, path: String, serializer: KSerializer<T>, block: HttpRequestBuilder.() -> Unit = {}): T =
        RegolithJson.lenient.decodeFromString(serializer, send(method, path, block).bodyAsText())

    private suspend fun send(method: HttpMethod, path: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val response = perform(method, path, block)
        if (response.status.isSuccess()) return response
        throw failure(response)
    }

    private suspend fun perform(method: HttpMethod, path: String, block: HttpRequestBuilder.() -> Unit): HttpResponse = try {
        http.request("$base$path") {
            this.method = method
            bearerAuth(token)
            block()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw RegolithError.Unavailable("The pages role does not answer: ${e.message}")
    } catch (e: GeneralSecurityException) {
        // the client reports an untrusted or mismatched certificate as a security exception, not an io one.
        throw RegolithError.Unavailable("The pages role's certificate is not trusted: ${e.message}")
    }

    /** Its problems are this project's problems, so a caller sees the reason rather than a status code. */
    private suspend fun failure(response: HttpResponse): RegolithError {
        val body = runCatching { RegolithJson.lenient.decodeFromString(ErrorBody.serializer(), response.bodyAsText()) }.getOrNull()
        val detail = body?.detail ?: "The pages role answered ${response.status.value}"

        return when (body?.code) {
            ErrorCodes.NOT_FOUND -> RegolithError.NotFound(detail)
            ErrorCodes.INVALID_REQUEST -> RegolithError.Invalid(detail)
            ErrorCodes.PAYLOAD_TOO_LARGE -> RegolithError.TooLarge(detail)
            ErrorCodes.CONFLICT -> RegolithError.Conflict(detail)
            ErrorCodes.UNAUTHORIZED -> RegolithError.Unavailable("The pages role refused this server's token")
            else -> RegolithError.Unavailable(detail)
        }
    }

    private fun SiteInfo.toDomain() = PublishedSite(name, url, release, files, bytes, publishedAt, hasIndex)

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
