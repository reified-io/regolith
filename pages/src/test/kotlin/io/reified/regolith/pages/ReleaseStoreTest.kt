package io.reified.regolith.pages

import io.reified.regolith.protocol.pages.ReleaseFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val LIMITS = PagesConfig.Limits(maxFiles = 10, maxFileBytes = 1024, maxSiteBytes = 4096, releasesKept = 2, maxSites = 3)

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** A clock a test moves by hand. */
private class MutableClock(private var current: Instant = Instant.parse("2026-09-13T12:00:00Z")) : Clock {
    override fun now(): Instant = current

    fun advance(by: Duration) {
        current += by
    }
}

class ReleaseStoreTest {
    private val root: Path = Files.createTempDirectory("regolith-pages-test")
    private val store = ReleaseStore(root, LIMITS)
    private val site = SiteName.parse("demo")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private suspend fun publish(vararg files: Pair<String, String>): Release {
        val entries = files.map { (path, body) -> ReleaseFile(path, sha256(body), body.length.toLong()) }
        val started = store.startRelease(site, entries)

        for ((path, body) in files) {
            if (sha256(body) in started.missing) store.putBlob(sha256(body), body.byteInputStream())
        }

        return store.activate(site, ReleaseId.parse(started.release))
    }

    @Test
    fun `a release becomes visible only once it is activated`() = runBlocking {
        val started = store.startRelease(site, listOf(ReleaseFile("index.html", sha256("hi"), 2)))
        assertEquals(listOf(sha256("hi")), started.missing)
        assertNull(store.currentRelease(site))

        val tooEarly = assertFailsWith<PagesError.Conflict> { store.activate(site, ReleaseId.parse(started.release)) }
        assertContains(tooEarly.message.orEmpty(), "never uploaded")

        store.putBlob(sha256("hi"), "hi".byteInputStream())
        store.activate(site, ReleaseId.parse(started.release))
        assertEquals("index.html", store.currentRelease(site)?.manifest?.files?.single()?.path)
    }

    // the hash is the file's whole identity, so bytes that do not match it can never be stored under it
    @Test
    fun `bytes that do not match the hash are refused`() = runBlocking {
        val error = assertFailsWith<PagesError.Invalid> { store.putBlob(sha256("hi"), "not hi".byteInputStream()) }
        assertContains(error.message.orEmpty(), "hash to")
        assertTrue(!store.blobPath(sha256("hi")).exists())
    }

    @Test
    fun `a file already stored is not asked for twice`() = runBlocking {
        publish("index.html" to "<h1>one</h1>", "app.js" to "run()")
        val second = store.startRelease(
            site,
            listOf(ReleaseFile("index.html", sha256("<h1>one</h1>"), 12), ReleaseFile("app.js", sha256("changed()"), 9)),
        )
        assertEquals(listOf(sha256("changed()")), second.missing)
    }

    @Test
    fun `old releases and the blobs only they named are swept up`() = runBlocking {
        publish("index.html" to "one")
        publish("index.html" to "two")
        publish("index.html" to "three")
        publish("index.html" to "four")

        val kept = root.resolve("sites/demo/releases").listDirectoryEntries("*.json")
        assertEquals(LIMITS.releasesKept, kept.size)
        assertTrue(!store.blobPath(sha256("one")).exists(), "a blob no release names any more is deleted")
        assertTrue(store.blobPath(sha256("four")).exists(), "the live release keeps its files")
    }

    // a publisher that gave up on one site never publishes it again, so its leftovers go with anyone's next publish
    @Test
    fun `a release started and never activated is swept up with the next publish of any site`() = runBlocking {
        val clock = MutableClock()
        val store = ReleaseStore(root, LIMITS, clock)
        val other = SiteName.parse("abandoned")
        val started = store.startRelease(other, listOf(ReleaseFile("index.html", sha256("half"), 4)))
        store.putBlob(sha256("half"), "half".byteInputStream())

        clock.advance(7.hours)
        val entries = listOf(ReleaseFile("index.html", sha256("live"), 4))
        val live = store.startRelease(site, entries)
        store.putBlob(sha256("live"), "live".byteInputStream())
        store.activate(site, ReleaseId.parse(live.release))

        assertTrue(!root.resolve("sites/abandoned/releases/${started.release}.json").exists(), "the abandoned release stayed")
        assertTrue(!store.blobPath(sha256("half")).exists(), "the blob only the abandoned release named stayed")
        assertTrue(store.blobPath(sha256("live")).exists())
    }

    @Test
    fun `taking a site down removes its releases and its files`() = runBlocking {
        publish("index.html" to "bye")
        assertTrue(store.delete(site))
        assertNull(store.currentRelease(site))
        assertTrue(!store.blobPath(sha256("bye")).exists())
        assertEquals(emptyList(), store.sites())
    }

    @Test
    fun `caps are the server's, not the caller's`() = runBlocking {
        val many = (1..LIMITS.maxFiles + 1).map { ReleaseFile("file$it.txt", sha256("$it"), 1) }
        assertFailsWith<PagesError.Invalid> { store.startRelease(site, many) }
        assertFailsWith<PagesError.TooLarge> { store.startRelease(site, listOf(ReleaseFile("big.bin", sha256("big"), 2048))) }

        val oversize = "x".repeat(LIMITS.maxFileBytes.toInt() + 1)
        assertFailsWith<PagesError.TooLarge> { store.putBlob(sha256(oversize), oversize.byteInputStream()) }
    }
}
