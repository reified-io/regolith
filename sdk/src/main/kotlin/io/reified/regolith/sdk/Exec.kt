package io.reified.regolith.sdk

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecStatus
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.OutputFrame
import io.reified.regolith.protocol.OutputKind
import io.reified.regolith.protocol.OutputPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
     * One page of output from [offset]. With [waitSeconds], the server holds the request until output past
     * [offset] arrives or the exec ends, up to its own ceiling; without it, the page holds what is recorded
     * now, which may be nothing. [maxBytes] bounds the page's text, though a page always carries at least
     * one frame when there is one to carry. The next page starts at [OutputPage.nextOffset], and
     * [OutputPage.complete] says there is no next page.
     */
    public suspend fun readOutput(offset: Long = 0, waitSeconds: Int = 0, maxBytes: Int? = null): OutputPage =
        client.call(HttpMethod.Get, "$path/output", OutputPage.serializer(), timeoutFor(waitSeconds)) {
            url.parameters.append("offset", offset.toString())
            if (waitSeconds > 0) url.parameters.append("waitSeconds", waitSeconds.toString())
            maxBytes?.let { url.parameters.append("maxBytes", it.toString()) }
        }

    /**
     * Output from [offset] until the exec ends, [budget] runs out or [maxChars] is reached, and the exec
     * as of the last page — everything needed to report a command that is still running and pick it up
     * again later, in one call.
     *
     * This is the read for a caller working to a deadline of its own, such as one answering a person
     * while the command keeps going. Each page asks the server to wait with what is left of [budget], and
     * a page that comes back empty ends the read: the server waited its share, so asking again would only
     * spin against it. Whatever did not fit is read by the next call from [OutputSoFar.nextOffset]; use
     * [output] instead to follow an exec to its end with no deadline.
     *
     * [maxChars] is where this stops asking for more, not a cut: a page carries at least one whole frame,
     * so the last one can take the text past it. Nothing is dropped, because [OutputSoFar.nextOffset]
     * has to name the end of what was actually read.
     */
    public suspend fun readWithin(offset: Long = 0, budget: Duration, maxChars: Int): OutputSoFar {
        require(maxChars > 0) { "maxChars must be positive" }
        val started = TimeSource.Monotonic.markNow()
        val text = StringBuilder()
        var next = offset
        var gapped = false

        while (true) {
            val left = (budget - started.elapsedNow()).inWholeSeconds.toInt().coerceAtLeast(0)
            val room = maxChars - text.length
            val page = readOutput(next, left, room)
            page.frames.forEach { frame ->
                if (frame.kind == OutputKind.GAP) gapped = true else text.append(frame.text)
            }
            next = page.nextOffset
            // the server holds a page for its whole wait, so an empty one means nothing more came in time
            val more = !page.complete && page.frames.isNotEmpty()
            if (!more || left == 0 || text.length >= maxChars) {
                return OutputSoFar(text.toString(), next, page.complete, gapped, page.exec)
            }
        }
    }

    /**
     * Output frames from [fromOffset] to the end of the exec, live while it runs: [readOutput] asked again
     * and again, each time waiting for more. Every frame carries the offset to resume from, so a collector
     * that failed can start a new flow exactly where it stopped.
     */
    public fun output(fromOffset: Long = 0): Flow<OutputFrame> = flow {
        pages(fromOffset) { page -> page.frames.forEach { emit(it) } }
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

    /**
     * Hands every page from [fromOffset] to [use], to the end of the exec, and returns the last one.
     *
     * A healthy server holds an empty page for its whole wait, so asking again costs nothing. One that
     * answers at once — something in front of it, or a server that ignores the wait — would turn this into
     * a spin, so an empty page is followed by a pause rather than another request straight away.
     */
    internal suspend fun pages(fromOffset: Long, use: suspend (OutputPage) -> Unit): OutputPage {
        var offset = fromOffset

        while (true) {
            val page = readOutput(offset, POLL_WAIT_SECONDS)
            use(page)
            offset = page.nextOffset
            if (page.complete) return page
            if (page.frames.isEmpty()) delay(IDLE_PAUSE)
        }
    }

    private fun timeoutFor(waitSeconds: Int): Long = waitSeconds * 1000L + RegolithClient.DEFAULT_TIMEOUT_MILLIS

    private companion object {
        const val POLL_WAIT_SECONDS = 20
        val IDLE_PAUSE = 1.seconds
    }
}

/**
 * Output read from one exec within a budget, and the exec it came from.
 *
 * [nextOffset] is where the next read starts, and [complete] says the exec has finished and every frame
 * up to it has been read. [gapped] means the server dropped output between the pieces of [text] to stay
 * under its own cap, which [ExecInfo.outputTruncated] on [exec] reports too.
 */
public class OutputSoFar(
    public val text: String,
    public val nextOffset: Long,
    public val complete: Boolean,
    public val gapped: Boolean,
    public val exec: ExecInfo,
)

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
