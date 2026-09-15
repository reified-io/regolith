package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.ports.SnapshotBounds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The tar reader against archives GNU tar writes in each of its formats. GNU tar is on every
 * machine the build runs on; the sandbox image's tar is the same program, or busybox writing the
 * ustar shape covered here.
 */
class TarSnapshotTest {
    private val root: Path = Files.createTempDirectory("regolith-tar-test")
    private val source: Path = root.resolve("source").createDirectories()
    private val roomy = SnapshotBounds(maxFiles = 100, maxFileBytes = 1 shl 20, maxTotalBytes = 1 shl 20)
    // past the 100 bytes a plain header holds, at a shape every format can carry, ustar included.
    private val longName = "a".repeat(60) + "/" + "b".repeat(60) + "/" + "c".repeat(60) + ".txt"

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun archive(vararg options: String): ByteArray {
        val process = ProcessBuilder(listOf("tar", "-C", source.toString()) + options + listOf("-cf", "-", ".")).redirectErrorStream(false).start()
        val bytes = process.inputStream.readAllBytes()
        assertEquals(0, process.waitFor(), process.errorStream.readAllBytes().decodeToString())

        return bytes
    }

    private fun unpacked(name: String, bytes: ByteArray, bounds: SnapshotBounds = roomy): Map<String, String> {
        val destination = root.resolve(name)
        TarSnapshot.unpack(ByteArrayInputStream(bytes), destination, bounds)

        return Files.walk(destination).use { paths ->
            paths.filter { it.isRegularFile() }.toList().associate { it.relativeTo(destination).joinToString("/") to it.readText() }
        }
    }

    private fun populate() {
        source.resolve("index.html").writeText("<h1>hi</h1>")
        source.resolve("assets").createDirectories().resolve("app.js").writeText("run()")
        source.resolve(longName).also { it.parent.createDirectories() }.writeText("long")
        Files.createSymbolicLink(source.resolve("escape"), Path.of("/etc/passwd"))
        Files.createLink(source.resolve("copy.js"), source.resolve("assets/app.js"))
    }

    @Test
    fun `every format gnu tar writes unpacks to the same files, with links left behind`() {
        populate()
        val expected = mapOf("index.html" to "<h1>hi</h1>", "assets/app.js" to "run()", longName to "long", "copy.js" to "run()")

        for (format in listOf("gnu", "posix", "ustar")) {
            assertEquals(expected, unpacked(format, archive("--format=$format")), "format $format")
            assertFalse(root.resolve(format).resolve("escape").exists(), "a symlink was recreated from a $format archive")
        }
    }

    @Test
    fun `the copy stops the moment a bound is passed`() {
        populate()
        val gnu = archive("--format=gnu")

        val files = assertFailsWith<RegolithError.TooLarge> { unpacked("files", gnu, roomy.copy(maxFiles = 2)) }
        assertContains(files.message.orEmpty(), "more than 2 files")
        val file = assertFailsWith<RegolithError.TooLarge> { unpacked("file", gnu, roomy.copy(maxFileBytes = 5)) }
        assertContains(file.message.orEmpty(), "larger than 5 bytes")
        val total = assertFailsWith<RegolithError.TooLarge> { unpacked("total", gnu, roomy.copy(maxTotalBytes = 20)) }
        assertContains(total.message.orEmpty(), "more than 20 bytes")
    }

    // the archive is written by the sandbox: a name is rebuilt from its segments, never trusted as a path
    @Test
    fun `an entry that reaches outside the directory is refused, and a stream that ends early is an eof`() {
        val escaping = ByteArrayOutputStream().apply {
            write(header("../outside.txt", size = 2))
            write("hi".toByteArray())
            write(ByteArray(510))
            write(ByteArray(1024))
        }.toByteArray()

        assertFailsWith<RegolithError.Invalid> { unpacked("escape", escaping) }
        assertFalse(root.resolve("outside.txt").exists())

        val cut = ByteArrayOutputStream().apply {
            write(header("cut.txt", size = 100))
            write("only a little".toByteArray())
        }.toByteArray()
        assertFailsWith<EOFException> { unpacked("cut", cut) }
    }

    /** A ustar header for a regular file; the checksum is what makes tar itself accept it. */
    private fun header(name: String, size: Long): ByteArray {
        val block = ByteArray(512)
        name.toByteArray().copyInto(block, 0)
        "0000644\u0000".toByteArray().copyInto(block, 100)
        "0001750\u0000".toByteArray().copyInto(block, 108)
        "0001750\u0000".toByteArray().copyInto(block, 116)
        size.toString(radix = 8).padStart(11, '0').plus("\u0000").toByteArray().copyInto(block, 124)
        "00000000000\u0000".toByteArray().copyInto(block, 136)
        "        ".toByteArray().copyInto(block, 148)
        block[156] = '0'.code.toByte()
        "ustar\u0000".toByteArray().copyInto(block, 257)
        "00".toByteArray().copyInto(block, 263)
        val sum = block.sumOf { it.toInt() and 0xFF }
        sum.toString(radix = 8).padStart(6, '0').plus("\u0000 ").toByteArray().copyInto(block, 148)

        return block
    }
}
