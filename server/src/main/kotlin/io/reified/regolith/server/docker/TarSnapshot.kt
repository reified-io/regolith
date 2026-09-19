package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.ports.SnapshotBounds
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/**
 * Unpacks a tar stream the sandbox's own `tar` wrote, keeping only its regular files, under one
 * directory on the server.
 *
 * The stream is untrusted, so it is bounded while it is read: past [SnapshotBounds] the reader stops
 * with [RegolithError.TooLarge] and the caller kills the writer, whatever the directory looked like
 * when it was listed. A name is rebuilt from its segments and never used as a path of its own, so an
 * entry cannot leave the destination. Symlinks, devices and anything else that is not a plain file
 * are skipped; a hard link is copied from the file it names, since the listing counted both.
 *
 * It reads the three shapes a `tar -c` produces: POSIX ustar, GNU (long names as `L` entries) and
 * pax (long names as `path` records), which covers GNU tar and busybox.
 */
internal object TarSnapshot {
    private const val BLOCK = 512
    private const val NAME_BYTES = 100
    private const val PREFIX_BYTES = 155
    private const val MAX_NAME_BYTES = 4096
    private const val COPY_CHUNK = 64 * 1024

    private const val REGULAR = '0'.code.toByte()
    private const val REGULAR_OLD: Byte = 0
    private const val HARD_LINK = '1'.code.toByte()
    private const val CONTIGUOUS = '7'.code.toByte()
    private const val GNU_LONG_NAME = 'L'.code.toByte()
    private const val PAX_HEADER = 'x'.code.toByte()

    /** Unpacks [input] into [destination]; the stream ended early is an [EOFException] for the caller to explain. */
    fun unpack(input: InputStream, destination: Path, bounds: SnapshotBounds) {
        val header = ByteArray(BLOCK)
        val tally = Tally(bounds)
        // a name announced by the entry before this one: a gnu `L` entry or a pax `path` record.
        var announced: String? = null

        while (true) {
            if (!readBlock(input, header)) throw EOFException("The archive ended without its end-of-archive blocks")
            if (header.all { it == 0.toByte() }) return
            val size = octal(header, 124, 12)
            val name = announced ?: headerName(header)
            announced = when (header[156]) {
                GNU_LONG_NAME -> text(input, size).trimEnd('\u0000')
                PAX_HEADER -> paxPath(text(input, size))
                REGULAR, REGULAR_OLD, CONTIGUOUS -> null.also { extract(input, destination, name, size, tally) }
                HARD_LINK -> null.also { link(input, destination, name, field(header, 157, NAME_BYTES), size, tally) }
                else -> null.also { skip(input, size) }
            }
            skip(input, padding(size))
        }
    }

    private fun extract(input: InputStream, destination: Path, name: String, size: Long, tally: Tally) {
        tally.count(name, size)
        val target = safePath(destination, name)
        if (target == null) {
            skip(input, size)
            return
        }
        target.parent.createDirectories()
        Files.newOutputStream(target).use { out -> copy(input, out, size) }
    }

    /** A hard link names a file already unpacked; it is copied, since the listing counted both. */
    private fun link(input: InputStream, destination: Path, name: String, linked: String, size: Long, tally: Tally) {
        val source = safePath(destination, linked)
        val target = safePath(destination, name)
        if (source != null && target != null && source.isRegularFile()) {
            tally.count(name, Files.size(source))
            target.parent.createDirectories()
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
        skip(input, size)
    }

    /** Files and bytes so far, refused the moment one bound is passed. */
    private class Tally(private val bounds: SnapshotBounds) {
        private var files = 0
        private var total = 0L

        fun count(name: String, size: Long) {
            files++
            total += size
            val refusal = when {
                files > bounds.maxFiles -> "The directory holds more than ${bounds.maxFiles} files"
                size > bounds.maxFileBytes -> "`$name` is larger than ${bounds.maxFileBytes} bytes"
                total > bounds.maxTotalBytes -> "The directory holds more than ${bounds.maxTotalBytes} bytes"
                else -> return
            }
            throw RegolithError.TooLarge(refusal)
        }
    }

    /**
     * Where an entry lands, or null for one that names nothing to store: a directory entry, or a
     * name every segment of which is `.`. A `..` segment is refused outright rather than skipped,
     * because an archive that carries one was not written by `tar -c` over a directory.
     */
    private fun safePath(destination: Path, name: String): Path? {
        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        requireValid(segments.none { it == ".." }) { "`$name` reaches outside the directory" }
        if (segments.isEmpty()) return null

        return segments.fold(destination) { path, segment -> path.resolve(segment) }
    }

    private fun headerName(header: ByteArray): String {
        val name = field(header, 0, NAME_BYTES)
        // only posix ustar has a prefix field; gnu's magic is `ustar ` and its bytes there mean other things.
        val ustar = String(header, 257, 6, StandardCharsets.US_ASCII) == "ustar\u0000"
        val prefix = if (ustar) field(header, 345, PREFIX_BYTES) else ""

        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, StandardCharsets.UTF_8).substringBefore('\u0000')

    /** The `path` record of a pax extended header: `<length> path=<value>\n`, among records of other keys. */
    private fun paxPath(records: String): String? {
        var rest = records

        while (rest.isNotEmpty()) {
            val length = rest.substringBefore(' ').toIntOrNull() ?: return null
            if (length <= 0 || length > rest.length) return null
            val record = rest.substring(0, length)
            rest = rest.substring(length)
            val key = record.substringAfter(' ').substringBefore('=')
            if (key == "path") return record.substringAfter('=').removeSuffix("\n")
        }

        return null
    }

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        val digits = String(header, offset, length, StandardCharsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        // a size no octal field can hold is written base-256; nothing that large may be published anyway.
        requireValid(digits.isNotEmpty() && digits.all { it in '0'..'7' }) { "The archive carries a size this server does not read" }

        return digits.toLong(radix = 8)
    }

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun text(input: InputStream, size: Long): String {
        requireValid(size <= MAX_NAME_BYTES) { "A file name in the archive is longer than $MAX_NAME_BYTES bytes" }
        val bytes = ByteArray(size.toInt())
        readFully(input, bytes, bytes.size)

        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(COPY_CHUNK)
        var left = size

        while (left > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (count < 0) throw EOFException("The archive ended inside a file")
            out.write(buffer, 0, count)
            left -= count
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size

        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) throw EOFException("The archive ended inside an entry")
            left--
        }
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        val first = input.read()
        if (first < 0) return false
        block[0] = first.toByte()
        readFully(input, block, block.size, from = 1)

        return true
    }

    private fun readFully(input: InputStream, into: ByteArray, length: Int, from: Int = 0) {
        var position = from

        while (position < length) {
            val count = input.read(into, position, length - position)
            if (count < 0) throw EOFException("The archive ended inside a header")
            position += count
        }
    }
}
