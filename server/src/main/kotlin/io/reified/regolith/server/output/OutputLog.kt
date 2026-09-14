package io.reified.regolith.server.output

import io.reified.regolith.server.domain.RegolithError
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/*
 * the recorded output of one exec: a file of frames, each `0xFE kind length(u32 be) payload`.
 *
 * an offset is a byte position of a frame boundary, so a client resumes exactly where it stopped.
 * frames are appended in the order the pipes were read, one stream at a time. stdout and stderr payloads
 * are always valid utf-8: the writer holds back a character split across two reads and replaces
 * malformed bytes, so a frame never has to be re-joined with its neighbour to be decoded. 0xFE
 * cannot occur in utf-8, which lets a reader reject an offset that does not start a frame.
 */

enum class FrameKind(val code: Byte) {
    STDOUT(1),
    STDERR(2),

    /** Output dropped to stay under the cap; the payload is the dropped byte count in ASCII digits. */
    GAP(3),
    ;

    companion object {
        fun of(code: Byte): FrameKind? = entries.firstOrNull { it.code == code }
    }
}

/** A decoded frame; [end] is the offset just past it. */
data class Frame(val kind: FrameKind, val text: String, val end: Long) {
    val droppedBytes: Long? get() = if (kind == FrameKind.GAP) text.toLong() else null
}

object OutputLog {
    const val MAGIC: Byte = 0xFE.toByte()
    const val HEADER_BYTES = 6
    const val MAX_PAYLOAD = 64 * 1024

    /**
     * Reads whole frames starting at [offset], stopping before [limit] — the end the writer has
     * published, never the current file length, which may hold a frame still being written.
     */
    fun read(file: Path, offset: Long, limit: Long, maxPayloadBytes: Int): List<Frame> {
        if (offset < 0 || offset > limit) throw RegolithError.Invalid("Offset $offset is outside the recorded output")
        if (offset == limit) return emptyList()
        val frames = ArrayList<Frame>()
        RandomAccessFile(file.toFile(), "r").use { raf ->
            var position = offset
            var payloadTotal = 0
            val header = ByteArray(HEADER_BYTES)
            while (position < limit) {
                raf.seek(position)
                raf.readFully(header)
                val kind = FrameKind.of(header[1])
                val length = ByteBuffer.wrap(header, 2, 4).int
                if (header[0] != MAGIC || kind == null || length !in 0..MAX_PAYLOAD) {
                    throw RegolithError.Invalid("Offset $offset does not start an output frame")
                }
                if (frames.isNotEmpty() && payloadTotal + length > maxPayloadBytes) break
                val payload = ByteArray(length)
                raf.readFully(payload)
                position += HEADER_BYTES + length
                payloadTotal += length
                frames += Frame(kind, String(payload, StandardCharsets.UTF_8), position)
            }
        }

        return frames
    }

    /** Offset just past the last complete frame, for a log whose writer died mid-frame. */
    fun validEnd(file: Path): Long {
        if (!file.toFile().exists()) return 0
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val size = raf.length()
            var position = 0L
            val header = ByteArray(HEADER_BYTES)
            while (position + HEADER_BYTES <= size) {
                raf.seek(position)
                raf.readFully(header)
                val length = ByteBuffer.wrap(header, 2, 4).int
                if (header[0] != MAGIC || FrameKind.of(header[1]) == null || length !in 0..MAX_PAYLOAD) break
                if (position + HEADER_BYTES + length > size) break
                position += HEADER_BYTES + length
            }
            return position
        }
    }
}

/**
 * Appends frames to one exec's log and keeps it under [maxBytes].
 *
 * Output fills the log from the start until only [tailBytes] of the budget remain. From then on the
 * newest [tailBytes] are kept in memory, and [finish] writes a gap frame followed by that tail, so a
 * long run keeps both how it started and how it ended. While a run is past the cap, live readers see
 * nothing new until it finishes; [truncated] tells them why.
 */
class OutputLogWriter(
    private val out: OutputStream,
    private val maxBytes: Long,
    private val tailBytes: Int,
) {

    init {
        require(tailBytes > 0 && maxBytes > tailBytes * 2L) { "The output cap must leave room for the head and the tail" }
    }

    /** Offset just past the last frame written to [out]. */
    var end: Long = 0
        private set

    var truncated: Boolean = false
        private set

    private val carry = mutableMapOf<FrameKind, ByteArray>()
    private val tail = ArrayDeque<Pair<FrameKind, ByteArray>>()
    private var tailSize = 0L
    private var dropped = 0L
    private var finished = false

    fun append(kind: FrameKind, bytes: ByteArray, length: Int = bytes.size) {
        check(!finished) { "The log is finished" }
        require(kind != FrameKind.GAP) { "Gap frames are written by the log itself" }
        if (length == 0) return
        val pending = (carry.remove(kind) ?: EMPTY) + bytes.copyOf(length)
        val complete = Utf8.completePrefix(pending)
        if (complete < pending.size) carry[kind] = pending.copyOfRange(complete, pending.size)
        if (complete > 0) emitText(kind, String(pending, 0, complete, StandardCharsets.UTF_8))
    }

    /** Flushes held-back bytes and writes the gap and the tail, if the cap was reached. */
    fun finish() {
        if (finished) return
        for ((kind, bytes) in carry.entries.toList()) emitText(kind, String(bytes, StandardCharsets.UTF_8))
        carry.clear()
        finished = true
        if (!truncated) return
        writeFrame(FrameKind.GAP, dropped.toString().toByteArray(StandardCharsets.US_ASCII))
        for ((kind, payload) in tail) writeFrame(kind, payload)
        tail.clear()
        out.flush()
    }

    private fun emitText(kind: FrameKind, text: String) {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        var start = 0

        while (start < utf8.size) {
            val stop = Utf8.boundaryAtOrBefore(utf8, minOf(start + OutputLog.MAX_PAYLOAD, utf8.size))
            accept(kind, utf8.copyOfRange(start, stop))
            start = stop
        }
    }

    private fun accept(kind: FrameKind, payload: ByteArray) {
        val size = OutputLog.HEADER_BYTES + payload.size

        if (!truncated && end + size <= maxBytes - tailBytes - GAP_RESERVE) {
            writeFrame(kind, payload)
            out.flush()
            return
        }

        truncated = true
        tail.addLast(kind to payload)
        tailSize += size

        while (tailSize > tailBytes) {
            val (oldestKind, oldest) = tail.removeFirst()
            tailSize -= OutputLog.HEADER_BYTES + oldest.size
            val excess = tailSize + OutputLog.HEADER_BYTES + oldest.size - tailBytes
            if (tail.isEmpty() && excess < oldest.size) {
                // a single frame larger than the whole tail keeps its own end.
                val cut = Utf8.boundaryAtOrAfter(oldest, excess.toInt())
                dropped += cut
                val kept = oldest.copyOfRange(cut, oldest.size)
                tail.addFirst(oldestKind to kept)
                tailSize += OutputLog.HEADER_BYTES + kept.size
                break
            }
            dropped += oldest.size
        }
    }

    private fun writeFrame(kind: FrameKind, payload: ByteArray) {
        val header = ByteBuffer.allocate(OutputLog.HEADER_BYTES).put(OutputLog.MAGIC).put(kind.code).putInt(payload.size)
        out.write(header.array())
        out.write(payload)
        end += OutputLog.HEADER_BYTES + payload.size
    }

    private companion object {
        val EMPTY = ByteArray(0)

        // room for the gap frame itself: a header and up to 19 digits.
        const val GAP_RESERVE = OutputLog.HEADER_BYTES + 19
    }
}

internal object Utf8 {
    /** Length of the longest prefix of [bytes] that does not end inside a multi-byte sequence. */
    fun completePrefix(bytes: ByteArray): Int {
        val size = bytes.size

        for (back in 1..minOf(3, size)) {
            val b = bytes[size - back].toInt() and 0xFF
            if (b and 0xC0 == 0x80) continue
            val needed = when {
                b and 0x80 == 0 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> return size
            }
            return if (needed > back) size - back else size
        }

        return size
    }

    /** The largest index at or before [index] that does not split a character in valid [utf8]. */
    fun boundaryAtOrBefore(utf8: ByteArray, index: Int): Int {
        var i = index
        while (i in 1 until utf8.size && utf8[i].toInt() and 0xC0 == 0x80) i--

        return i
    }

    /** The smallest index at or after [index] that does not split a character in valid [utf8]. */
    fun boundaryAtOrAfter(utf8: ByteArray, index: Int): Int {
        var i = index
        while (i < utf8.size && utf8[i].toInt() and 0xC0 == 0x80) i++

        return i
    }
}
