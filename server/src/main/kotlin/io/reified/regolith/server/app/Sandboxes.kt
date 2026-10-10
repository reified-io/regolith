package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.domain.StopReason
import io.reified.regolith.server.domain.ImagePolicy
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.Metadata
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Alias
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxId
import io.reified.regolith.server.domain.Site
import io.reified.regolith.server.domain.SiteLabel
import io.reified.regolith.server.domain.Snapshot
import io.reified.regolith.server.domain.SnapshotId
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.ports.HomeStore
import io.reified.regolith.server.ports.SitePublisher
import io.reified.regolith.server.ports.StateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What a caller asks for when creating a sandbox; `null` takes the server default. */
data class SandboxRequest(
    val imagePolicy: ImagePolicy? = null,
    val cpus: Double? = null,
    val memoryMb: Int? = null,
    val homeMb: Int? = null,
    val network: NetworkPolicy? = null,
    val lifecycle: LifecycleRequest = LifecycleRequest(),
    val env: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val from: SnapshotRef? = null,
)

/** A snapshot of one sandbox, which a new sandbox's home can start as a copy of. */
data class SnapshotRef(val sandbox: SandboxId, val snapshot: SnapshotId)

data class LifecycleRequest(val idleStop: Duration? = null, val maxSession: Duration? = null, val retain: Duration? = null)

/** New resources for an existing sandbox; `null` keeps what it has. */
data class ResourcesRequest(val cpus: Double? = null, val memoryMb: Int? = null, val homeMb: Int? = null)

/** A change to an existing sandbox; only non-null fields apply. */
data class SandboxPatch(
    val alias: Alias? = null,
    val imagePolicy: ImagePolicy? = null,
    val network: NetworkPolicy? = null,
    val lifecycle: LifecycleRequest? = null,
    val env: Map<String, String>? = null,
    val labels: Map<String, String>? = null,
    val resources: ResourcesRequest? = null,
)

/** The registry of sandboxes and every operation on one as a whole. */
class Sandboxes(
    private val store: StateStore,
    private val sessions: Sessions,
    private val execs: Execs,
    private val homes: HomeStore,
    private val sites: SitePublisher?,
    private val config: ServerConfig,
    private val clock: Clock,
) {

    private val records = ConcurrentHashMap<SandboxId, Sandbox>()
    private val aliases = ConcurrentHashMap<Alias, SandboxId>()
    private val locks = KeyedLocks<SandboxId>()
    private val aliasLocks = KeyedLocks<Alias>()

    suspend fun load() {
        for (sandbox in store.sandboxes()) remember(sandbox)
        log.info { "Sandboxes loaded: count=[${records.size}]" }
        // the records keep naming their sites, so a pages role configured later can still take them
        // down; until then nothing ends them, and an operator should know that rather than find out.
        if (sites == null) {
            val held = records.values.count { it.site != null }
            if (held > 0) log.warn { "Sites are recorded but no pages role is configured, so none is taken down at its term: count=[$held]" }
        }
    }

    fun find(id: SandboxId): Sandbox? = records[id]

    fun require(id: SandboxId): Sandbox =
        records[id] ?: throw RegolithError.NotFound("Sandbox `$id` does not exist")

    /** The sandbox a caller filed under [alias], which is how it finds one again without a table. */
    fun byAlias(alias: Alias): Sandbox? = aliases[alias]?.let { records[it] }

    fun all(): List<Sandbox> = records.values.sortedBy { it.id.value }

    /** Sandboxes carrying every label in [labels], ordered by id, starting after [cursor]. */
    fun list(labels: Map<String, String>, limit: Int, cursor: String?): Pair<List<Sandbox>, String?> {
        requireValid(limit in 1..MAX_PAGE) { "limit is between 1 and $MAX_PAGE" }
        val matching = all()
            .filter { sandbox -> labels.all { (key, value) -> sandbox.labels[key] == value } }
            .filter { cursor == null || it.id.value > cursor }
        val page = matching.take(limit)

        return page to page.lastOrNull()?.id?.value?.takeIf { matching.size > limit }
    }

    /**
     * The sandbox filed under [alias], created when there is none; without an alias, always a new one.
     * The flag is true when it was created.
     */
    suspend fun create(alias: Alias?, request: SandboxRequest): Pair<Sandbox, Boolean> {
        if (alias == null) return created(alias = null, request) to true

        return aliasLocks.withLock(alias) {
            byAlias(alias)?.let { return@withLock it to false }
            created(alias, request) to true
        }
    }

    private suspend fun created(alias: Alias?, request: SandboxRequest): Sandbox {
        val defaults = config.defaults
        val limits = config.limits
        val imagePolicy = request.imagePolicy ?: ImagePolicy.Default
        config.images.requireAllowed(imagePolicy)
        val origin = request.from?.let { snapshotOf(require(it.sandbox), it.snapshot) }
        // a home made from a snapshot starts at least as large as the home the snapshot was taken of.
        val base = origin?.let { defaults.resources.copy(homeMb = maxOf(defaults.resources.homeMb, it.homeMb)) } ?: defaults.resources
        val resources = resources(base, ResourcesRequest(request.cpus, request.memoryMb, request.homeMb))
        requireValid(origin == null || resources.homeMb >= origin.homeMb) {
            "homeMb is at least ${origin?.homeMb}, the size of the home the snapshot was taken of"
        }
        Metadata.requireEnv(request.env)
        Metadata.requireLabels(request.labels, limits.maxLabels)
        val now = clock.now()
        val sandbox = Sandbox(
            id = SandboxId.random(),
            alias = alias,
            imagePolicy = imagePolicy,
            resources = resources,
            network = request.network ?: defaults.network,
            lifecycle = lifecycle(defaults.lifecycle, request.lifecycle),
            env = request.env,
            labels = request.labels,
            createdAt = now,
            lastUsedAt = now,
        )
        request.from?.let { from -> cloneHome(from, sandbox.id) }
        store.save(sandbox)
        remember(sandbox)
        log.info { "Sandbox created: sandbox=[${sandbox.id}] alias=[$alias] from=[${request.from?.let { "${it.sandbox}/${it.snapshot}" }}]" }

        return sandbox
    }

    /** The home of a sandbox not yet recorded, made a copy of [from]; nothing of it stays behind if that fails. */
    private suspend fun cloneHome(from: SnapshotRef, target: SandboxId) = locks.withLock(from.sandbox) {
        snapshotOf(require(from.sandbox), from.snapshot)

        try {
            homes.clone(from.sandbox, from.snapshot, target)
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { homes.destroy(target) } }
            throw e
        }
    }

    /**
     * Gives an orphaned home a record again: server defaults for everything but the home size, which is
     * the size the home already has, and an `adopted` label so an operator can find it.
     */
    suspend fun adopt(id: SandboxId, homeMb: Int): Sandbox = locks.withLock(id) {
        check(records[id] == null) { "Sandbox `$id` already has a record" }
        val defaults = config.defaults
        val now = clock.now()
        val sandbox = Sandbox(
            id = id,
            alias = null,
            imagePolicy = ImagePolicy.Default,
            resources = defaults.resources.copy(homeMb = homeMb),
            network = defaults.network,
            lifecycle = defaults.lifecycle,
            env = emptyMap(),
            labels = mapOf(ADOPTED_LABEL to "true"),
            createdAt = now,
            lastUsedAt = now,
        )
        store.save(sandbox)
        remember(sandbox)
        log.info { "Sandbox adopted from an orphaned home: sandbox=[$id] homeMb=[$homeMb]" }
        sandbox
    }

    /** An alias moves under its own lock, the one [create] holds, so two patches cannot both claim it. */
    suspend fun update(id: SandboxId, patch: SandboxPatch): Sandbox =
        patch.alias?.let { alias -> aliasLocks.withLock(alias) { patched(id, patch) } } ?: patched(id, patch)

    private suspend fun patched(id: SandboxId, patch: SandboxPatch): Sandbox = locks.withLock(id) {
        val current = require(id)
        patch.imagePolicy?.let(config.images::requireAllowed)
        patch.env?.let(Metadata::requireEnv)
        patch.labels?.let { Metadata.requireLabels(it, config.limits.maxLabels) }
        patch.alias?.let { alias ->
            val holder = aliases[alias]
            requireValid(holder == null || holder == id) { "Another sandbox is filed under that alias" }
        }
        val resources = patch.resources?.let { resources(current.resources, it) } ?: current.resources
        // the floor is the home the sandbox has, not the size its record promises: the record runs ahead of
        // the disk until the next session grows it, and a size the host turned out not to hold can be taken back.
        if (resources.homeMb < current.resources.homeMb) {
            val floor = homes.sizeMb(id)
            requireValid(floor == null || resources.homeMb >= floor) { "A home only grows: homeMb is at least $floor" }
        }
        val updated = current.copy(
            alias = patch.alias ?: current.alias,
            imagePolicy = patch.imagePolicy ?: current.imagePolicy,
            network = patch.network ?: current.network,
            lifecycle = patch.lifecycle?.let { lifecycle(current.lifecycle, it) } ?: current.lifecycle,
            env = patch.env ?: current.env,
            labels = patch.labels ?: current.labels,
            resources = resources,
        )
        store.save(updated)
        if (resources != current.resources) {
            log.info { "Sandbox resources changed from its next session: sandbox=[$id] from=[${current.resources}] to=[$resources]" }
        }
        current.alias?.takeIf { it != updated.alias }?.let { aliases.remove(it) }
        remember(updated)
        if (updated.network != current.network) sessions.applyNetwork(updated)
        updated
    }

    suspend fun start(id: SandboxId): Sandbox {
        val sandbox = markUsed(id)
        sessions.acquire(sandbox).close()

        return sandbox
    }

    suspend fun stop(id: SandboxId, reason: StopReason = StopReason.STOPPED) {
        require(id)
        execs.interrupt(id, reason)
        sessions.stop(id, reason)
    }

    suspend fun delete(id: SandboxId) = locks.withLock(id) {
        val sandbox = require(id)
        execs.interrupt(id, StopReason.SANDBOX_DELETED)
        sessions.stop(id, StopReason.SANDBOX_DELETED)
        execs.awaitNone(id)
        // a site outlives the home unless it is taken down here: nothing else knows where it was served.
        sandbox.site?.let { site ->
            try {
                sites?.unpublish(site.label.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Site left behind by a deleted sandbox: sandbox=[$id] site=[${site.label}]" }
            }
        }
        homes.destroy(id)
        store.delete(id)
        records.remove(id)
        sandbox.alias?.let { aliases.remove(it) }
        sessions.forget(id)
        log.info { "Sandbox deleted: sandbox=[$id]" }
    }

    /**
     * Retention for a sandbox kept for its site: the home goes, the record and the site stay, and the
     * next use starts an empty home. Checked again under the lock, which every use takes in [markUsed]
     * before its session starts, so a use that came in since the sweep looked keeps the home.
     */
    suspend fun releaseHome(id: SandboxId) = locks.withLock(id) {
        val sandbox = records[id] ?: return@withLock
        val now = clock.now()
        val due = sandbox.site != null && sandbox.homeReleasedAt == null && now >= sandbox.deleteAfter
        if (!due || sessions.get(id) != null) return@withLock
        homes.destroy(id)
        // the snapshots lived beside the home and went with it.
        val updated = sandbox.copy(homeReleasedAt = now, snapshots = emptyList())
        store.save(updated)
        remember(updated)
        log.info { "Home past retention released, site kept: sandbox=[$id] site=[${sandbox.site?.label}]" }
    }

    /**
     * Copies the sandbox's home into a new snapshot. A home is copied only while nothing has it open, so the
     * session is stopped first, and the next command starts a new one on the same home. A sandbox that never
     * ran gets the empty home it would have started with, and its snapshot is of that.
     */
    suspend fun snapshot(id: SandboxId): Snapshot = locks.withLock(id) {
        val sandbox = require(id)
        val max = config.limits.maxSnapshots
        if (sandbox.snapshots.size >= max) throw RegolithError.Conflict("A sandbox keeps at most $max snapshots; delete one first")
        val snapshotId = SnapshotId.random()
        execs.interrupt(id, StopReason.STOPPED)
        val (bytes, homeMb) = sessions.stopped(id, StopReason.STOPPED) {
            val size = homes.sizeMb(id) ?: homes.open(id, sandbox.resources.homeMb).let {
                homes.close(id)
                sandbox.resources.homeMb
            }
            homes.snapshot(id, snapshotId) to size
        }
        val now = clock.now()
        val snapshot = Snapshot(snapshotId, now, bytes, homeMb)
        saved(sandbox.copy(snapshots = sandbox.snapshots + snapshot, lastUsedAt = now, homeReleasedAt = null))
        log.info { "Snapshot taken: sandbox=[$id] snapshot=[$snapshotId] bytes=[$bytes] homeMb=[$homeMb]" }
        snapshot
    }

    /**
     * Replaces the sandbox's home with a copy of one of its snapshots, stopping its session first. The home
     * takes the size it had then, and grows back to the sandbox's own at the next session.
     */
    suspend fun restore(id: SandboxId, snapshot: SnapshotId): Sandbox = locks.withLock(id) {
        val sandbox = require(id)
        snapshotOf(sandbox, snapshot)
        execs.interrupt(id, StopReason.STOPPED)
        sessions.stopped(id, StopReason.STOPPED) { homes.restore(id, snapshot) }
        log.info { "Home restored from a snapshot: sandbox=[$id] snapshot=[$snapshot]" }
        saved(sandbox.copy(lastUsedAt = clock.now()))
    }

    suspend fun deleteSnapshot(id: SandboxId, snapshot: SnapshotId) = locks.withLock(id) {
        val sandbox = require(id)
        snapshotOf(sandbox, snapshot)
        homes.deleteSnapshot(id, snapshot)
        saved(sandbox.copy(snapshots = sandbox.snapshots.filterNot { it.id == snapshot }))
        log.info { "Snapshot deleted: sandbox=[$id] snapshot=[$snapshot]" }
    }

    private fun snapshotOf(sandbox: Sandbox, snapshot: SnapshotId): Snapshot =
        sandbox.snapshots.firstOrNull { it.id == snapshot } ?: throw RegolithError.NotFound("Snapshot `$snapshot` of sandbox `${sandbox.id}` does not exist")

    private suspend fun saved(sandbox: Sandbox): Sandbox {
        store.save(sandbox)
        remember(sandbox)

        return sandbox
    }

    /** Records where this sandbox's files are served and until when, or `null` once they no longer are. */
    suspend fun site(id: SandboxId, site: Site?): Sandbox = locks.withLock(id) {
        val updated = require(id).copy(site = site)
        store.save(updated)
        remember(updated)
        updated
    }

    /** A label nothing else is served at; they are random, so a taken one is a coincidence. */
    fun unusedSiteLabel(): SiteLabel {
        repeat(LABEL_TRIES) {
            val label = SiteLabel.random()
            if (records.values.none { it.site?.label == label }) return label
        }
        error("No unused site label after $LABEL_TRIES tries")
    }

    /**
     * Records use of the sandbox; retention counts from the last one. Persisted at most once a minute,
     * except the first use after a released home, which the session about to start creates anew. A use
     * counts here whether or not that session then starts, as it does for [Sandbox.lastUsedAt]: the home
     * is made anew by whichever session starts next.
     */
    suspend fun markUsed(id: SandboxId): Sandbox = locks.withLock(id) {
        val current = require(id)
        val now = clock.now()
        if (now - current.lastUsedAt < USE_PERSIST_INTERVAL && current.homeReleasedAt == null) return@withLock current
        val updated = current.copy(lastUsedAt = now, homeReleasedAt = null)
        store.save(updated)
        remember(updated)
        updated
    }

    private fun remember(sandbox: Sandbox) {
        records[sandbox.id] = sandbox
        sandbox.alias?.let { aliases[it] = sandbox.id }
    }

    private fun resources(base: Resources, request: ResourcesRequest): Resources {
        val limits = config.limits
        val result = Resources(
            cpus = request.cpus ?: base.cpus,
            memoryMb = request.memoryMb ?: base.memoryMb,
            homeMb = request.homeMb ?: base.homeMb,
        )
        requireValid(result.cpus > 0 && result.cpus <= limits.maxCpus) { "cpus is above 0 and at most ${limits.maxCpus}" }
        requireValid(result.memoryMb in MIN_MEMORY_MB..limits.maxMemoryMb) { "memoryMb is between $MIN_MEMORY_MB and ${limits.maxMemoryMb}" }
        requireValid(result.homeMb in MIN_HOME_MB..limits.maxHomeMb) { "homeMb is between $MIN_HOME_MB and ${limits.maxHomeMb}" }

        return result
    }

    private fun lifecycle(base: Lifecycle, request: LifecycleRequest): Lifecycle {
        val limits = config.limits
        val result = Lifecycle(
            idleStop = request.idleStop ?: base.idleStop,
            maxSession = request.maxSession ?: base.maxSession,
            retain = request.retain ?: base.retain,
        )
        requireValid(result.maxSession in MIN_SESSION..limits.maxSession) {
            "maxSessionSeconds is between ${MIN_SESSION.inWholeSeconds} and ${limits.maxSession.inWholeSeconds}"
        }
        requireValid(result.idleStop in MIN_IDLE..result.maxSession) {
            "idleStopSeconds is between ${MIN_IDLE.inWholeSeconds} and maxSessionSeconds"
        }
        requireValid(result.retain >= Duration.ZERO && result.retain <= limits.maxRetain) {
            "retainDays is between 0 and ${limits.maxRetain.inWholeDays}"
        }

        return result
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val MAX_PAGE = 500
        private const val LABEL_TRIES = 10
        const val ADOPTED_LABEL = "regolith.adopted"
        private const val MIN_MEMORY_MB = 128
        private const val MIN_HOME_MB = 256
        private val MIN_SESSION = 60.seconds
        private val MIN_IDLE = 30.seconds
        private val USE_PERSIST_INTERVAL = 1.minutes
    }
}
