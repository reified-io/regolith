package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.domain.SiteLabel
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.ports.PublishedSite
import io.reified.regolith.server.ports.SandboxRuntime
import io.reified.regolith.server.ports.SiteLimits
import io.reified.regolith.server.ports.SitePublisher
import io.reified.regolith.server.ports.TreeFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/**
 * Publishing a directory of a sandbox to the public pages role.
 *
 * A sandbox has one site, and the server gives it the label it is served at — a random one, so the
 * address carries nothing of the sandbox or of whoever the caller keeps it for. The label is recorded
 * with the sandbox, so publishing again keeps the address and nobody has to remember it.
 *
 * The sandbox never learns that any of this happened: it holds no token, opens no connection, and is
 * read from the outside like any other file operation. What reaches the internet is a snapshot taken
 * at one moment — not the home itself, which keeps changing and disappears when the session stops.
 */
class SitePublishing(
    private val sandboxes: Sandboxes,
    private val sessions: Sessions,
    private val runtime: SandboxRuntime,
    private val config: ServerConfig,
    private val publisher: SitePublisher?,
) {

    /** Whether this server publishes at all, which `GET /v1/info` reports so a client need not try. */
    val available: Boolean get() = publisher != null

    suspend fun publish(id: SandboxId, path: String): PublishedSite {
        val pages = require()
        val limits = pages.limits()
        val directory = resolvePath(path)

        val sandbox = sandboxes.markUsed(id)
        val label = sandbox.site ?: sandboxes.unusedSiteLabel()
        val snapshot = snapshotDir()

        try {
            sessions.withLease(sandbox) {
                val files = runtime.tree(id, directory, limits.maxFiles + 1)
                check(files, limits, directory)
                runtime.copyOut(id, directory, snapshot)
            }
            verify(snapshot, limits)
            val published = pages.publish(label.value, snapshot)
            // recorded only once the release is live, so a failed first publish leaves no label behind.
            if (sandbox.site != label) sandboxes.site(id, label)
            log.info { "Site published: sandbox=[$id] site=[$label] release=[${published.release}] files=[${published.files}]" }
            return published
        } finally {
            withContext(Dispatchers.IO) { snapshot.toFile().deleteRecursively() }
        }
    }

    suspend fun published(id: SandboxId): PublishedSite {
        val pages = require()
        val label = label(id)

        return pages.published(label.value) ?: throw RegolithError.NotFound("Nothing is published for sandbox `$id`")
    }

    suspend fun unpublish(id: SandboxId) {
        val pages = require()
        val label = label(id)
        pages.unpublish(label.value)
        sandboxes.site(id, null)
        log.info { "Site taken down: sandbox=[$id] site=[$label]" }
    }

    private fun label(id: SandboxId): SiteLabel =
        sandboxes.require(id).site ?: throw RegolithError.NotFound("Nothing is published for sandbox `$id`")

    /** What the pages role would refuse, refused here: before a byte leaves the sandbox. */
    private fun check(files: List<TreeFile>, limits: SiteLimits, directory: String) {
        requireValid(files.isNotEmpty()) { "`$directory` holds no files to publish" }
        val oversized = files.firstOrNull { it.size > limits.maxFileBytes }
        val total = files.sumOf { it.size }
        val refusal = when {
            files.size > limits.maxFiles -> "A site holds at most ${limits.maxFiles} files, `$directory` has ${files.size}"
            oversized != null -> "`${oversized.path}` is larger than the ${limits.maxFileBytes / MIB} MB a published file may be"
            total > limits.maxSiteBytes -> "A site holds at most ${limits.maxSiteBytes / MIB} MB, `$directory` is ${total / MIB} MB"
            else -> return
        }
        throw RegolithError.TooLarge(refusal)
    }

    /**
     * What actually landed on this server. The listing was taken inside the sandbox, which keeps
     * running: the copy is measured again here, and anything that is not a plain file — a symlink
     * above all — is left behind rather than followed.
     */
    private suspend fun verify(snapshot: Path, limits: SiteLimits) = withContext(Dispatchers.IO) {
        var bytes = 0L
        Files.walk(snapshot).use { paths ->
            paths.forEach { file ->
                when {
                    file.isSymbolicLink() -> Files.delete(file)
                    file.isRegularFile() -> bytes += Files.size(file)
                    else -> Unit
                }
            }
        }

        if (bytes > limits.maxSiteBytes) {
            throw RegolithError.TooLarge("The snapshot grew past the ${limits.maxSiteBytes / MIB} MB a site may hold")
        }
    }

    private fun require(): SitePublisher =
        publisher ?: throw RegolithError.NotImplemented("This server publishes nothing: no pages role is configured")

    private suspend fun snapshotDir(): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(config.stateDir.resolve("publish").resolve(UUID.randomUUID().toString()))
    }

    private companion object {
        val log = KotlinLogging.logger {}
        const val MIB = 1024L * 1024L
    }
}
