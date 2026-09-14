package io.reified.regolith.server.output

import io.reified.regolith.server.domain.RegolithError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutputLogTest {
    private val file: Path = Files.createTempFile("regolith-output", ".log")

    @AfterTest
    fun cleanUp() {
        Files.deleteIfExists(file)
    }

    private fun write(maxBytes: Long = 1024 * 1024, tailBytes: Int = 4096, block: OutputLogWriter.() -> Unit): OutputLogWriter =
        Files.newOutputStream(file).use { out -> OutputLogWriter(out, maxBytes, tailBytes).apply(block).apply { finish() } }

    @Test
    fun `frames read back in order with resumable offsets`() {
        val writer = write {
            append(FrameKind.STDOUT, "build started\n".encodeToByteArray())
            append(FrameKind.STDERR, "warning: unused\n".encodeToByteArray())
            append(FrameKind.STDOUT, "done\n".encodeToByteArray())
        }
        val frames = OutputLog.read(file, 0, writer.end, Int.MAX_VALUE)

        assertEquals(listOf("build started\n", "warning: unused\n", "done\n"), frames.map { it.text })
        assertEquals(listOf(FrameKind.STDOUT, FrameKind.STDERR, FrameKind.STDOUT), frames.map { it.kind })
        assertEquals(frames.drop(1), OutputLog.read(file, frames[0].end, writer.end, Int.MAX_VALUE))
        assertEquals(writer.end, frames.last().end)
    }

    @Test
    fun `a character split across two reads arrives whole`() {
        val snowman = "☃".encodeToByteArray()
        val writer = write {
            append(FrameKind.STDOUT, "a".encodeToByteArray() + snowman.copyOfRange(0, 1))
            append(FrameKind.STDOUT, snowman.copyOfRange(1, 3) + "b".encodeToByteArray())
        }

        assertEquals("a☃b", OutputLog.read(file, 0, writer.end, Int.MAX_VALUE).joinToString("") { it.text })
    }

    @Test
    fun `malformed bytes become replacement characters instead of breaking a frame`() {
        val writer = write { append(FrameKind.STDERR, byteArrayOf('o'.code.toByte(), 0xC3.toByte(), 0x28, 'k'.code.toByte())) }

        assertEquals("o�(k", OutputLog.read(file, 0, writer.end, Int.MAX_VALUE).single().text)
    }

    @Test
    fun `past the cap the log keeps the head, a gap and the tail`() {
        val line = "0123456789".repeat(10) + "\n"
        val writer = write(maxBytes = 20_000, tailBytes = 2_000) {
            repeat(1_000) { index -> append(FrameKind.STDOUT, "$index:$line".encodeToByteArray()) }
        }
        val frames = OutputLog.read(file, 0, writer.end, Int.MAX_VALUE)
        val gap = frames.single { it.kind == FrameKind.GAP }

        assertTrue(writer.truncated)
        assertTrue(writer.end <= 20_000, "log is ${writer.end} bytes")
        assertTrue(frames.first().text.startsWith("0:"))
        assertTrue(frames.last().text.startsWith("999:"))
        assertTrue(checkNotNull(gap.droppedBytes) > 50_000)
    }

    @Test
    fun `an offset inside a frame is refused`() {
        val writer = write { append(FrameKind.STDOUT, "hello".encodeToByteArray()) }

        assertFailsWith<RegolithError.Invalid> { OutputLog.read(file, 3, writer.end, Int.MAX_VALUE) }
        assertFailsWith<RegolithError.Invalid> { OutputLog.read(file, writer.end + 1, writer.end, Int.MAX_VALUE) }
    }

    @Test
    fun `a page stops at the byte budget but always holds one frame`() {
        val writer = write { repeat(3) { append(FrameKind.STDOUT, "x".repeat(100).encodeToByteArray()) } }

        assertEquals(1, OutputLog.read(file, 0, writer.end, 10).size)
        assertEquals(2, OutputLog.read(file, 0, writer.end, 200).size)
    }

    @Test
    fun `the valid end ignores a frame cut short by a crash`() {
        val writer = write { repeat(2) { append(FrameKind.STDOUT, "complete".encodeToByteArray()) } }
        val firstEnd = OutputLog.read(file, 0, writer.end, Int.MAX_VALUE).first().end
        Files.write(file, Files.readAllBytes(file).copyOf(writer.end.toInt() - 3))

        assertEquals(firstEnd, OutputLog.validEnd(file))
    }
}
