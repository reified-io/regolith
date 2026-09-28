package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.domain.Site
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.ports.PublishedSite
import io.reified.regolith.server.ports.SandboxRuntime
import io.reified.regolith.server.ports.SiteLimits
import io.reified.regolith.server.ports.SitePublisher
import io.reified.regolith.server.ports.TreeFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.time.Clock
import kotlin.time.Instant

/** A site as the API reports it: what the pages role serves, and the term the control plane keeps for it. */
data class ServedSite(val published: PublishedSite, val until: Instant)

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
 *
 * A site has a term, and the term is the truth: past it the site is gone wherever it is read, whether
 * or not the sweep has taken it down yet. The sweep, and a publish that finds one, only carry that out.
 */
class SitePublishing(
    private val sandboxes: Sandboxes,
    private val sessions: Sessions,
    private val runtime: SandboxRuntime,
    private val config: ServerConfig,
    private val publisher: SitePublisher?,
    private val clock: Clock,
) {

    // one publish or takedown per sandbox at a time: two first publishes would otherwise each make a
    // label, and the one not recorded would be served with nothing left to take it down.
    private val locks = KeyedLocks<SandboxId>()

    /** Whether this server publishes at all, which `GET /v1/info` reports so a client need not try. */
    val available: Boolean get() = publisher != null

    /**
     * Publishes [path] as this sandbox's site until [until]; without one, a first publish gets the
     * default term and a later one keeps the term the site has, so updating a site never shortens it.
     * A site past its term is not updated but replaced: it is taken down first, and what is published
     * is a new site at a new address.
     */
    suspend fun publish(id: SandboxId, path: String, until: Instant?): ServedSite = locks.withLock(id) {
        val pages = configured()
        val limits = pages.limits()
        val directory = resolvePath(path)
        val now = clock.now()
        until?.let { requireTerm(it, now) }

        val sandbox = takenDownIfOver(sandboxes.markUsed(id), now)
        val current = sandbox.site
        val label = current?.label ?: sandboxes.unusedSiteLabel()
        val site = Site(label, until ?: current?.until ?: (now + config.defaults.siteTerm))
        val snapshot = snapshotDir()

        try {
            sessions.withLease(sandbox) {
                val files = runtime.tree(id, directory, limits.maxFiles + 1)
                checkLimits(files, limits, directory)
                // bounded again as it arrives: the listing was a moment ago, and the sandbox keeps writing.
                runtime.copyOut(id, directory, snapshot, limits.bounds)
            }
            val published = pages.publish(label.value, snapshot)
            // recorded only once the release is live, so a failed first publish leaves no label behind.
            if (current != site) sandboxes.site(id, site)
            log.info { "Site published: sandbox=[$id] site=[$label] release=[${published.release}] files=[${published.files}] until=[${site.until}]" }
            ServedSite(published, site.until)
        } finally {
            withContext(Dispatchers.IO) { snapshot.toFile().deleteRecursively() }
        }
    }

    suspend fun published(id: SandboxId): ServedSite {
        val pages = configured()
        val site = liveSite(id, clock.now())

        return ServedSite(pages.published(site.label.value) ?: notPublished(id), site.until)
    }

    /** Moves the site's term to [until], earlier or later than it was. */
    suspend fun term(id: SandboxId, until: Instant): ServedSite = locks.withLock(id) {
        val pages = configured()
        val now = clock.now()
        requireTerm(until, now)
        val site = liveSite(id, now)
        val published = pages.published(site.label.value) ?: notPublished(id)
        sandboxes.site(id, site.copy(until = until))
        log.info { "Site term moved: sandbox=[$id] site=[${site.label}] from=[${site.until}] to=[$until]" }
        ServedSite(published, until)
    }

    /** Takes the site down: a live one, or one past its term that the sweep has not reached yet. */
    suspend fun unpublish(id: SandboxId) = locks.withLock(id) {
        val pages = configured()
        val site = sandboxes.require(id).site ?: notPublished(id)
        pages.unpublish(site.label.value)
        sandboxes.site(id, null)
        log.info { "Site taken down: sandbox=[$id] site=[${site.label}]" }
    }

    /**
     * Takes the site down once its term is over; the sweep calls it, and it looks again under the lock,
     * since the term may have been moved since the sweep read it.
     */
    suspend fun expire(id: SandboxId) {
        locks.withLock(id) { sandboxes.find(id)?.let { takenDownIfOver(it, clock.now()) } }
    }

    private suspend fun takenDownIfOver(sandbox: Sandbox, now: Instant): Sandbox {
        val site = sandbox.site ?: return sandbox
        if (now < site.until) return sandbox
        configured().unpublish(site.label.value)
        log.info { "Site past its term taken down: sandbox=[${sandbox.id}] site=[${site.label}] until=[${site.until}]" }

        return sandboxes.site(sandbox.id, null)
    }

    private fun liveSite(id: SandboxId, now: Instant): Site = sandboxes.require(id).liveSite(now) ?: notPublished(id)

    private fun notPublished(id: SandboxId): Nothing = throw RegolithError.NotFound("Nothing is published for sandbox `$id`")

    private fun requireTerm(until: Instant, now: Instant) {
        val max = config.limits.maxSiteTerm
        requireValid(until > now && until <= now + max) { "until is in the future and at most ${max.inWholeDays} days from now" }
    }

    /** What the pages role would refuse, refused here: before a byte leaves the sandbox. */
    private fun checkLimits(files: List<TreeFile>, limits: SiteLimits, directory: String) {
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

    private fun configured(): SitePublisher =
        publisher ?: throw RegolithError.NotImplemented("This server publishes nothing: no pages role is configured")

    private suspend fun snapshotDir(): Path = withContext(Dispatchers.IO) {
        config.stateDir.resolve("publish").resolve(UUID.randomUUID().toString()).createDirectories()
    }

    private companion object {
        val log = KotlinLogging.logger {}
        const val MIB = 1024L * 1024L
    }
}
