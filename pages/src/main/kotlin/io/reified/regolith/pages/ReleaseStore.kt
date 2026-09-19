package io.reified.regolith.pages

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.protocol.RegolithJson
import io.reified.regolith.protocol.pages.ReleaseFile
import io.reified.regolith.protocol.pages.ReleaseStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Releases on disk, addressed by content:
 *
 * ```
 * blobs/<first two hex>/<sha-256>
 * sites/<site>/releases/<release>.json
 * sites/<site>/current
 * ```
 *
 * A release is immutable, so publishing the same file twice stores one blob and a rollback is a
 * pointer away. Activation writes `current` through a temporary file and an atomic rename: a visitor
 * sees the old release or the new one, never a mixture. Nothing here ever maps a request path onto a
 * filesystem path — serving looks a manifest key up and reads the blob it names.
 */
class ReleaseStore(private val root: Path, private val limits: PagesConfig.Limits, private val clock: Clock = Clock.System) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val blobs = Mutex()
    private val active = ConcurrentHashMap<String, Release>()

    /** Records the files a release will serve and reports the blobs the server does not have yet. */
    suspend fun startRelease(site: SiteName, files: List<ReleaseFile>): ReleaseStarted = withSite(site) {
        requireValid(files.isNotEmpty()) { "A release holds at least one file" }
        requireValid(files.size <= limits.maxFiles) { "A site holds at most ${limits.maxFiles} files, asked for ${files.size}" }
        val entries = files.map { file ->
            requireValid(file.size >= 0) { "A file size is not negative" }
            if (file.size > limits.maxFileBytes) throw PagesError.TooLarge("`${file.path}` is larger than ${limits.maxFileBytes / MIB} MB")
            val path = SitePaths.clean(file.path)
            FileEntry(path, hash(file.hash), file.size, ContentTypes.of(path))
        }
        requireValid(entries.map { it.path }.toSet().size == entries.size) { "Two files of a release name the same path" }
        val total = entries.sumOf { it.size }
        if (total > limits.maxSiteBytes) throw PagesError.TooLarge("A site holds at most ${limits.maxSiteBytes / MIB} MB, asked for ${total / MIB} MB")

        if (currentRelease(site) == null && sitesOnDisk().size >= limits.maxSites) {
            throw PagesError.Conflict("This server holds its limit of ${limits.maxSites} sites")
        }

        val release = Release(ReleaseId.random().value, site.value, Manifest(entries), clock.now())
        io {
            releasesDir(site).createDirectories()
            write(releaseFile(site, release.id), Release.serializer(), release)
        }
        ReleaseStarted(release.id, entries.map { it.hash }.distinct().filterNot { io { blobPath(it).exists() } })
    }

    /**
     * Stores one blob, verifying its hash while it is written and refusing anything past the file cap.
     * The bytes are hashed as they arrive, so a caller cannot have a file stored under another's name.
     */
    suspend fun putBlob(hash: String, input: InputStream): Unit = io {
        val digest = MessageDigest.getInstance("SHA-256")
        val target = blobPath(hash(hash))

        if (target.exists()) {
            input.close()
            return@io
        }

        target.parent.createDirectories()
        val temporary = createTempFile(target.parent, "incoming-", ".part")
        var size = 0L

        try {
            Files.newOutputStream(temporary).use { out ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    if (size > limits.maxFileBytes) throw PagesError.TooLarge("A file is at most ${limits.maxFileBytes / MIB} MB")
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
            val written = digest.digest().toHexString()
            if (written != hash) throw PagesError.Invalid("The uploaded bytes hash to $written, not $hash")
            temporary.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.deleteIfExists()
        }
    }

    /** Makes a release the one visitors see. Every blob must be stored first, or nothing changes. */
    suspend fun activate(site: SiteName, id: ReleaseId): Release = withSite(site) {
        val release = io { readRelease(site, id) } ?: throw PagesError.NotFound("No release $id for `$site`")
        val missing = release.manifest.files.map { it.hash }.distinct().filterNot { io { blobPath(it).exists() } }
        if (missing.isNotEmpty()) throw PagesError.Conflict("${missing.size} of the release's files were never uploaded")
        val stored = io { release.manifest.files.sumOf { Files.size(blobPath(it.hash)) } }
        if (stored > limits.maxSiteBytes) throw PagesError.TooLarge("A site holds at most ${limits.maxSiteBytes / MIB} MB, the release is ${stored / MIB} MB")

        io { writeText(currentFile(site), release.id) }
        active[site.value] = release
        log.info { "Release published: site=[$site] release=[${release.id}] files=[${release.manifest.files.size}] bytes=[$stored]" }
        prune(site, release)
        release
    }

    /** The release visitors are served, or null when the site was never published or was taken down. */
    suspend fun currentRelease(site: SiteName): Release? {
        active[site.value]?.let { return it }
        val id = io { currentFile(site).takeIf { it.exists() }?.readText()?.trim() } ?: return null
        val release = io { readRelease(site, ReleaseId.parse(id)) } ?: return null
        active[site.value] = release

        return release
    }

    /** Takes a site down and forgets every release of it; the blobs go with the next sweep. */
    suspend fun delete(site: SiteName): Boolean = withSite(site) {
        val dir = siteDir(site)
        if (!io { dir.exists() }) return@withSite false
        io { dir.toFile().deleteRecursively() }
        active.remove(site.value)
        log.info { "Site taken down: site=[$site]" }
        collect()
        true
    }

    /** Every site that has something published. */
    suspend fun sites(): List<Pair<SiteName, Release>> = sitesOnDisk().mapNotNull { name ->
        currentRelease(name)?.let { name to it }
    }

    fun blobPath(hash: String): Path = root.resolve("blobs").resolve(hash.take(2)).resolve(hash)

    private suspend fun sitesOnDisk(): List<SiteName> = io {
        val dir = root.resolve("sites")
        if (!dir.exists()) return@io emptyList()
        dir.listDirectoryEntries().filter { it.isDirectory() }.mapNotNull { runCatching { SiteName.parse(it.name) }.getOrNull() }
    }.sortedBy { it.value }

    /** Keeps the newest releases and the active one, then sweeps abandoned releases and unused blobs. */
    private suspend fun prune(site: SiteName, keep: Release) {
        io {
            val releases = releasesDir(site).takeIf { it.exists() }?.listDirectoryEntries("*.json").orEmpty()
                .mapNotNull { file -> runCatching { read(file, Release.serializer()) }.getOrNull()?.let { file to it } }
            val ordered = releases.sortedByDescending { it.second.createdAt }
            val retained = ordered.take(limits.releasesKept).map { it.second.id }.toSet() + keep.id
            for ((file, release) in ordered) {
                if (release.id !in retained) file.deleteIfExists()
            }
        }
        collect()
    }

    /**
     * Drops releases that were started and never activated, across every site, then deletes blobs no
     * release names any more. Cheap: a manifest is small and there are few of them.
     *
     * An abandoned release is one older than [STALE_RELEASE] that is not the site's current one. Every
     * site is looked at, not only the one that was just published, because a site whose publisher gave
     * up is exactly the one that will not be published again. A site whose lock is held is skipped: its
     * releases are being changed by the holder, and the next sweep gets it.
     */
    private suspend fun collect() = blobs.withLock {
        val cutoff = clock.now() - STALE_RELEASE
        io {
            val referenced = mutableSetOf<String>()
            val sites = root.resolve("sites")
            if (sites.exists()) {
                for (site in sites.listDirectoryEntries().filter { it.isDirectory() }) {
                    val lock = locks.computeIfAbsent(site.name) { Mutex() }
                    val sweeping = lock.tryLock()
                    try {
                        val current = site.resolve("current").takeIf { it.exists() }?.readText()?.trim()
                        val releases = site.resolve("releases").takeIf { it.exists() }?.listDirectoryEntries("*.json").orEmpty()
                        for (file in releases) {
                            val release = runCatching { read(file, Release.serializer()) }.getOrNull() ?: continue
                            if (sweeping && release.id != current && release.createdAt < cutoff) {
                                file.deleteIfExists()
                                continue
                            }
                            release.manifest.files.forEach { referenced += it.hash }
                        }
                    } finally {
                        if (sweeping) lock.unlock()
                    }
                }
            }
            val blobRoot = root.resolve("blobs")
            if (!blobRoot.exists()) return@io
            var removed = 0
            for (bucket in blobRoot.listDirectoryEntries().filter { it.isDirectory() }) {
                for (blob in bucket.listDirectoryEntries()) {
                    if (blob.name !in referenced && !blob.name.startsWith("incoming-")) {
                        blob.deleteIfExists()
                        removed++
                    }
                }
                runCatching { Files.delete(bucket) }
            }
            if (removed > 0) log.info { "Unused blobs removed: count=[$removed]" }
        }
    }

    private fun siteDir(site: SiteName): Path = root.resolve("sites").resolve(site.value)

    private fun releasesDir(site: SiteName): Path = siteDir(site).resolve("releases")

    private fun releaseFile(site: SiteName, id: String): Path = releasesDir(site).resolve("$id.json")

    private fun currentFile(site: SiteName): Path = siteDir(site).resolve("current")

    private fun readRelease(site: SiteName, id: ReleaseId): Release? =
        releaseFile(site, id.value).takeIf { it.exists() }?.let { read(it, Release.serializer()) }

    private fun <T> read(file: Path, serializer: kotlinx.serialization.KSerializer<T>): T =
        RegolithJson.lenient.decodeFromString(serializer, file.readText())

    private fun <T> write(file: Path, serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        writeText(file, RegolithJson.strict.encodeToString(serializer, value))

    private fun writeText(file: Path, text: String) {
        file.parent.createDirectories()
        val temporary = file.resolveSibling("${file.name}.tmp")
        temporary.writeText(text)
        temporary.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun hash(raw: String): String {
        requireValid(HASH.matches(raw)) { "A file hash is 64 hexadecimal characters" }

        return raw
    }

    private suspend fun <T> withSite(site: SiteName, action: suspend () -> T): T =
        locks.computeIfAbsent(site.value) { Mutex() }.withLock { action() }

    private suspend fun <T> io(action: () -> T): T = withContext(Dispatchers.IO) { action() }

    private companion object {
        val log = KotlinLogging.logger {}
        val HASH = Regex("[0-9a-f]{64}")
        val STALE_RELEASE = 6.hours
        const val CHUNK = 64 * 1024
        const val MIB = 1024L * 1024L
    }
}
