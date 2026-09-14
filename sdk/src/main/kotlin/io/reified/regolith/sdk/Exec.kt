package io.reified.regolith.sdk

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecStatus
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.OutputFrame
import io.reified.regolith.protocol.OutputPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One command in a sandbox. */
public class Exec internal constructor(private val client: RegolithClient, sandboxPath: String, public val id: String) {
    private val path = "$sandboxPath/execs/$id"

    /** Current state; with [waitSeconds] the server holds the request until the exec finishes or the wait ends. */
    public suspend fun info(waitSeconds: Int = 0): ExecInfo =
        client.call(HttpMethod.Get, path, ExecInfo.serializer(), timeoutFor(waitSeconds)) {
            if (waitSeconds > 0) url.parameters.append("waitSeconds", waitSeconds.toString())
        }

    /** Suspends until the exec has finished. */
    public suspend fun await(): ExecInfo {
        while (true) {
            val info = info(POLL_WAIT_SECONDS)
            if (info.status == ExecStatus.FINISHED) return info
        }
    }

    /**
     * Output frames from [fromOffset] to the end of the exec, live while it runs. Every frame carries
     * the offset to resume from, so a collector that failed can start a new flow exactly where it
     * stopped.
     */
    public fun output(fromOffset: Long = 0): Flow<OutputFrame> = flow {
        var offset = fromOffset

        while (true) {
            val page = client.call(HttpMethod.Get, "$path/output", OutputPage.serializer(), timeoutFor(POLL_WAIT_SECONDS)) {
                url.parameters.append("offset", offset.toString())
                url.parameters.append("waitSeconds", POLL_WAIT_SECONDS.toString())
            }
            for (frame in page.frames) emit(frame)
            offset = page.nextOffset
            if (page.complete) break
        }
    }

    /** Stops the command: SIGTERM, then SIGKILL after a grace period. Cancelling a finished exec changes nothing. */
    public suspend fun cancel(): ExecInfo = client.call(HttpMethod.Post, "$path/cancel", ExecInfo.serializer())

    /** Writes to the command's stdin; the exec must have been started with `stdin = true`. */
    public suspend fun writeStdin(bytes: ByteArray, close: Boolean = false) {
        client.send(HttpMethod.Post, "$path/stdin") {
            if (close) url.parameters.append("close", "true")
            setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
        }
    }

    public suspend fun closeStdin(): Unit = writeStdin(ByteArray(0), close = true)

    private fun timeoutFor(waitSeconds: Int): Long = waitSeconds * 1000L + RegolithClient.DEFAULT_TIMEOUT_MILLIS

    private companion object {
        const val POLL_WAIT_SECONDS = 20
    }
}

/** A finished exec and the output collected from it. */
public class ExecResult(
    public val info: ExecInfo,
    public val stdout: String,
    public val stderr: String,
    /** Both streams interleaved in the order the server read them; see [io.reified.regolith.protocol.OutputFrame]. */
    public val combined: String,
    /** Whether any output was left out, by the server's cap or by the collector's. */
    public val truncated: Boolean,
) {
    /** The exit code, or null when the command did not exit on its own. */
    public val exitCode: Int? get() = info.outcome?.takeIf { it.type == OutcomeType.EXITED }?.exitCode
}
