package io.reified.regolith.sdk

import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.reified.regolith.protocol.CreateSandboxRequest
import io.reified.regolith.protocol.DirectoryListing
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecPage
import io.reified.regolith.protocol.ExecRequest
import io.reified.regolith.protocol.FileEntry
import io.reified.regolith.protocol.IDEMPOTENCY_KEY_HEADER
import io.reified.regolith.protocol.OutputKind
import io.reified.regolith.protocol.PublishRequest
import io.reified.regolith.protocol.PublishedSite
import io.reified.regolith.protocol.SandboxInfo
import io.reified.regolith.protocol.UpdateSandboxRequest
import java.io.ByteArrayOutputStream

/** One sandbox on the server. */
public class Sandbox internal constructor(private val client: RegolithClient, public val name: String) {
    private val path = "/v1/sandboxes/$name"

    /** File operations inside this sandbox. */
    public val files: SandboxFiles = SandboxFiles(client, path)

    /** Creates the sandbox, or returns the existing one unchanged. */
    public suspend fun getOrCreate(request: CreateSandboxRequest = CreateSandboxRequest()): SandboxInfo =
        client.call(HttpMethod.Put, path, SandboxInfo.serializer()) { with(client) { jsonBody(CreateSandboxRequest.serializer(), request) } }

    public suspend fun get(): SandboxInfo = client.call(HttpMethod.Get, path, SandboxInfo.serializer())

    /** Changes configuration; a network policy applies to a running session at once. */
    public suspend fun update(request: UpdateSandboxRequest): SandboxInfo =
        client.call(HttpMethod.Patch, path, SandboxInfo.serializer()) { with(client) { jsonBody(UpdateSandboxRequest.serializer(), request) } }

    /** Deletes the sandbox with its home; there is no undo. */
    public suspend fun delete() {
        client.send(HttpMethod.Delete, path)
    }

    /** Starts a session ahead of the first command; any operation would start one anyway. */
    public suspend fun start(): SandboxInfo = client.call(HttpMethod.Post, "$path/start", SandboxInfo.serializer())

    /** Ends the session, interrupting its commands; the home and its files stay. */
    public suspend fun stop(): SandboxInfo = client.call(HttpMethod.Post, "$path/stop", SandboxInfo.serializer())

    /**
     * Starts a command and returns at once. With an [idempotencyKey], retrying after a lost response
     * returns the exec the first attempt started instead of running the command twice.
     */
    public suspend fun startExec(request: ExecRequest, idempotencyKey: String? = null): Exec {
        val info = client.call(HttpMethod.Post, "$path/execs", ExecInfo.serializer()) {
            with(client) { jsonBody(ExecRequest.serializer(), request) }
            idempotencyKey?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
        }

        return Exec(client, path, info.id)
    }

    /**
     * Runs a command to its end and collects its output, keeping at most [maxOutputChars] characters
     * of each stream; the rest is counted in [ExecResult.truncated] rather than held in memory.
     */
    public suspend fun run(request: ExecRequest, idempotencyKey: String? = null, maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS): ExecResult {
        val exec = startExec(request, idempotencyKey)
        val stdout = BoundedText(maxOutputChars)
        val stderr = BoundedText(maxOutputChars)
        val combined = BoundedText(maxOutputChars)
        var serverTruncated = false
        exec.output().collect { frame ->
            when (frame.kind) {
                OutputKind.STDOUT -> stdout.append(frame.text)
                OutputKind.STDERR -> stderr.append(frame.text)
                OutputKind.GAP -> serverTruncated = true
            }
            if (frame.kind != OutputKind.GAP) combined.append(frame.text)
        }
        val info = exec.await()

        return ExecResult(
            info = info,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            combined = combined.toString(),
            truncated = serverTruncated || stdout.truncated || stderr.truncated || combined.truncated,
        )
    }

    /** Runs a shell script to its end; see [run]. */
    public suspend fun run(shell: String, timeoutSeconds: Int? = null): ExecResult =
        run(ExecRequest(shell = shell, timeoutSeconds = timeoutSeconds))

    /**
     * Publishes a directory of this sandbox to the server's pages role and returns its public address.
     *
     * What goes out is a snapshot taken now, not the home itself: the files keep changing afterwards and
     * the site does not. Publishing again replaces it atomically. [site] defaults to the sandbox's name.
     */
    public suspend fun publish(directory: String, site: String? = null): PublishedSite =
        client.call(HttpMethod.Post, "$path/publish", PublishedSite.serializer()) {
            with(client) { jsonBody(PublishRequest.serializer(), PublishRequest(directory, site)) }
        }

    /** What is published for this sandbox, or a `not_found` failure when nothing is. */
    public suspend fun site(): PublishedSite = client.call(HttpMethod.Get, "$path/site", PublishedSite.serializer())

    /** Takes the site down; the sandbox and its files are untouched. */
    public suspend fun unpublish() {
        client.send(HttpMethod.Delete, "$path/site")
    }

    /** A handle to an exec started earlier, for example by a previous process. */
    public fun exec(id: String): Exec = Exec(client, path, id)

    /** Recent execs, newest first. */
    public suspend fun execs(): List<ExecInfo> = client.call(HttpMethod.Get, "$path/execs", ExecPage.serializer()).execs

    public companion object {
        public const val DEFAULT_MAX_OUTPUT_CHARS: Int = 1_000_000
    }
}

/** Files inside one sandbox. A relative path resolves against the sandbox home. */
public class SandboxFiles internal constructor(private val client: RegolithClient, private val sandboxPath: String) {
    /** The file's bytes, as many as the server's `maxFileBytes` allows. */
    public suspend fun read(path: String): ByteArray = download(path, maxBytes = null)

    /**
     * The file's bytes, no more than [maxBytes] of them. The server refuses a larger file with
     * `payload_too_large` before sending any of it, and this client stops with the same failure the
     * moment a response runs past [maxBytes], so a server that ignores the bound cannot make it hold more.
     */
    public suspend fun read(path: String, maxBytes: Long): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }

        return download(path, maxBytes)
    }

    public suspend fun readText(path: String): String = read(path).decodeToString()

    /** The file as UTF-8 text, from no more than [maxBytes] of it; see [read]. */
    public suspend fun readText(path: String, maxBytes: Long): String = read(path, maxBytes).decodeToString()

    /** Replaces the file atomically, creating missing parent directories. */
    public suspend fun write(path: String, bytes: ByteArray): FileEntry =
        client.call(HttpMethod.Put, "$sandboxPath/files/content", FileEntry.serializer()) {
            url.parameters.append("path", path)
            setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
        }

    public suspend fun write(path: String, text: String): FileEntry = write(path, text.encodeToByteArray())

    public suspend fun list(path: String): List<FileEntry> =
        client.call(HttpMethod.Get, "$sandboxPath/files/entries", DirectoryListing.serializer()) { url.parameters.append("path", path) }.entries

    public suspend fun stat(path: String): FileEntry =
        client.call(HttpMethod.Get, "$sandboxPath/files/stat", FileEntry.serializer()) { url.parameters.append("path", path) }

    public suspend fun delete(path: String, recursive: Boolean = false) {
        client.send(HttpMethod.Delete, "$sandboxPath/files") {
            url.parameters.append("path", path)
            if (recursive) url.parameters.append("recursive", "true")
        }
    }

    private suspend fun download(path: String, maxBytes: Long?): ByteArray = client.stream(
        HttpMethod.Get,
        "$sandboxPath/files/content",
        configure = {
            url.parameters.append("path", path)
            maxBytes?.let { url.parameters.append("maxBytes", it.toString()) }
        },
    ) { response -> response.bodyAsChannel().readAtMost(path, maxBytes) }

    /** Reads the whole channel, failing as soon as it holds more than [maxBytes]; null reads it all. */
    private suspend fun ByteReadChannel.readAtMost(path: String, maxBytes: Long?): ByteArray {
        val kept = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)

        while (true) {
            val count = readAvailable(chunk)
            if (count < 0) break
            if (maxBytes != null && kept.size() + count > maxBytes) {
                cancel()
                throw RegolithException(HttpStatusCode.PayloadTooLarge.value, ErrorCodes.PAYLOAD_TOO_LARGE, "`$path` is larger than $maxBytes bytes")
            }
            kept.write(chunk, 0, count)
        }

        return kept.toByteArray()
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}

internal class BoundedText(private val max: Int) {
    private val builder = StringBuilder()

    var truncated: Boolean = false
        private set

    fun append(text: String) {
        val room = max - builder.length
        if (text.length > room) truncated = true
        if (room > 0) builder.append(text, 0, minOf(room, text.length))
    }

    override fun toString(): String = builder.toString()
}
