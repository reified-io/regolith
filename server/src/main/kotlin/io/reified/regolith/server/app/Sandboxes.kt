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
import io.reified.regolith.server.domain.SiteLabel
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.ports.HomeStore
import io.reified.regolith.server.ports.SitePublisher
import io.reified.regolith.server.ports.StateStore
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
)

data class LifecycleRequest(val idleStop: Duration? = null, val maxSession: Duration? = null, val retain: Duration? = null)

/** A change to an existing sandbox; only non-null fields apply. */
data class SandboxPatch(
    val alias: Alias? = null,
    val imagePolicy: ImagePolicy? = null,
    val network: NetworkPolicy? = null,
    val lifecycle: LifecycleRequest? = null,
    val env: Map<String, String>? = null,
    val labels: Map<String, String>? = null,
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
        val resources = Resources(
            cpus = request.cpus ?: defaults.resources.cpus,
            memoryMb = request.memoryMb ?: defaults.resources.memoryMb,
            homeMb = request.homeMb ?: defaults.resources.homeMb,
        )
        requireValid(resources.cpus > 0 && resources.cpus <= limits.maxCpus) { "cpus is above 0 and at most ${limits.maxCpus}" }
        requireValid(resources.memoryMb in MIN_MEMORY_MB..limits.maxMemoryMb) { "memoryMb is between $MIN_MEMORY_MB and ${limits.maxMemoryMb}" }
        requireValid(resources.homeMb in MIN_HOME_MB..limits.maxHomeMb) { "homeMb is between $MIN_HOME_MB and ${limits.maxHomeMb}" }
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
        store.save(sandbox)
        remember(sandbox)
        log.info { "Sandbox created: sandbox=[${sandbox.id}] alias=[$alias]" }

        return sandbox
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

    suspend fun update(id: SandboxId, patch: SandboxPatch): Sandbox = locks.withLock(id) {
        val current = require(id)
        patch.imagePolicy?.let(config.images::requireAllowed)
        patch.env?.let(Metadata::requireEnv)
        patch.labels?.let { Metadata.requireLabels(it, config.limits.maxLabels) }
        patch.alias?.let { alias ->
            val holder = aliases[alias]
            requireValid(holder == null || holder == id) { "Another sandbox is filed under that alias" }
        }
        val updated = current.copy(
            alias = patch.alias ?: current.alias,
            imagePolicy = patch.imagePolicy ?: current.imagePolicy,
            network = patch.network ?: current.network,
            lifecycle = patch.lifecycle?.let { lifecycle(current.lifecycle, it) } ?: current.lifecycle,
            env = patch.env ?: current.env,
            labels = patch.labels ?: current.labels,
        )
        store.save(updated)
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
        sandbox.site?.let { label ->
            runCatching { sites?.unpublish(label.value) }
                .onFailure { log.warn(it) { "Site left behind by a deleted sandbox: sandbox=[$id] site=[$label]" } }
        }
        homes.destroy(id)
        store.delete(id)
        records.remove(id)
        sandbox.alias?.let { aliases.remove(it) }
        sessions.forget(id)
        log.info { "Sandbox deleted: sandbox=[$id]" }
    }

    /** Records where this sandbox's files are served, or that they no longer are. */
    suspend fun site(id: SandboxId, label: SiteLabel?): Sandbox = locks.withLock(id) {
        val updated = require(id).copy(site = label)
        store.save(updated)
        remember(updated)
        updated
    }

    /** A label nothing else is served at; they are random, so a taken one is a coincidence. */
    fun unusedSiteLabel(): SiteLabel {
        repeat(LABEL_TRIES) {
            val label = SiteLabel.random()
            if (records.values.none { it.site == label }) return label
        }
        error("No unused site label after $LABEL_TRIES tries")
    }

    /** Records use of the sandbox; retention counts from the last one. Persisted at most once a minute. */
    suspend fun markUsed(id: SandboxId): Sandbox = locks.withLock(id) {
        val current = require(id)
        val now = clock.now()
        if (now - current.lastUsedAt < USE_PERSIST_INTERVAL) return@withLock current
        val updated = current.copy(lastUsedAt = now)
        store.save(updated)
        remember(updated)
        updated
    }

    private fun remember(sandbox: Sandbox) {
        records[sandbox.id] = sandbox
        sandbox.alias?.let { aliases[it] = sandbox.id }
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
