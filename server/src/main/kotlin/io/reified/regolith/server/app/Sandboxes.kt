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
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SandboxName
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.ports.HomeStore
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
    private val config: ServerConfig,
    private val clock: Clock,
) {

    private val records = ConcurrentHashMap<SandboxName, Sandbox>()
    private val locks = KeyedLocks<SandboxName>()

    suspend fun load() {
        for (sandbox in store.sandboxes()) records[sandbox.name] = sandbox
        log.info { "Sandboxes loaded: count=[${records.size}]" }
    }

    fun find(name: SandboxName): Sandbox? = records[name]

    fun require(name: SandboxName): Sandbox =
        records[name] ?: throw RegolithError.NotFound("Sandbox `$name` does not exist")

    fun all(): List<Sandbox> = records.values.sortedBy { it.name.value }

    /** Sandboxes carrying every label in [labels], ordered by name, starting after [cursor]. */
    fun list(labels: Map<String, String>, limit: Int, cursor: String?): Pair<List<Sandbox>, String?> {
        requireValid(limit in 1..MAX_PAGE) { "limit is between 1 and $MAX_PAGE" }
        val matching = all()
            .filter { sandbox -> labels.all { (key, value) -> sandbox.labels[key] == value } }
            .filter { cursor == null || it.name.value > cursor }
        val page = matching.take(limit)

        return page to page.lastOrNull()?.name?.value?.takeIf { matching.size > limit }
    }

    /** Creates the sandbox, or returns the existing one unchanged. The flag is true when it was created. */
    suspend fun getOrCreate(name: SandboxName, request: SandboxRequest): Pair<Sandbox, Boolean> = locks.withLock(name) {
        records[name]?.let { return@withLock it to false }
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
            name = name,
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
        records[name] = sandbox
        log.info { "Sandbox created: sandbox=[$name]" }
        sandbox to true
    }

    /**
     * Gives an orphaned home a record again: server defaults for everything but the home size, which is
     * the size the home already has, and an `adopted` label so an operator can find it.
     */
    suspend fun adopt(name: SandboxName, homeMb: Int): Sandbox = locks.withLock(name) {
        check(records[name] == null) { "Sandbox `$name` already has a record" }
        val defaults = config.defaults
        val now = clock.now()
        val sandbox = Sandbox(
            name = name,
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
        records[name] = sandbox
        log.info { "Sandbox adopted from an orphaned home: sandbox=[$name] homeMb=[$homeMb]" }
        sandbox
    }

    suspend fun update(name: SandboxName, patch: SandboxPatch): Sandbox = locks.withLock(name) {
        val current = require(name)
        patch.imagePolicy?.let(config.images::requireAllowed)
        patch.env?.let(Metadata::requireEnv)
        patch.labels?.let { Metadata.requireLabels(it, config.limits.maxLabels) }
        val updated = current.copy(
            imagePolicy = patch.imagePolicy ?: current.imagePolicy,
            network = patch.network ?: current.network,
            lifecycle = patch.lifecycle?.let { lifecycle(current.lifecycle, it) } ?: current.lifecycle,
            env = patch.env ?: current.env,
            labels = patch.labels ?: current.labels,
        )
        store.save(updated)
        records[name] = updated
        if (updated.network != current.network) sessions.applyNetwork(updated)
        updated
    }

    suspend fun start(name: SandboxName): Sandbox {
        val sandbox = markUsed(name)
        sessions.acquire(sandbox).close()

        return sandbox
    }

    suspend fun stop(name: SandboxName, reason: StopReason = StopReason.STOPPED) {
        require(name)
        execs.interrupt(name, reason)
        sessions.stop(name, reason)
    }

    suspend fun delete(name: SandboxName) = locks.withLock(name) {
        require(name)
        execs.interrupt(name, StopReason.SANDBOX_DELETED)
        sessions.stop(name, StopReason.SANDBOX_DELETED)
        execs.awaitNone(name)
        homes.destroy(name)
        store.delete(name)
        records.remove(name)
        sessions.forget(name)
        log.info { "Sandbox deleted: sandbox=[$name]" }
    }

    /** Records use of the sandbox; retention counts from the last one. Persisted at most once a minute. */
    suspend fun markUsed(name: SandboxName): Sandbox = locks.withLock(name) {
        val current = require(name)
        val now = clock.now()
        if (now - current.lastUsedAt < USE_PERSIST_INTERVAL) return@withLock current
        val updated = current.copy(lastUsedAt = now)
        store.save(updated)
        records[name] = updated
        updated
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
        const val ADOPTED_LABEL = "regolith.adopted"
        private const val MIN_MEMORY_MB = 128
        private const val MIN_HOME_MB = 256
        private val MIN_SESSION = 60.seconds
        private val MIN_IDLE = 30.seconds
        private val USE_PERSIST_INTERVAL = 1.minutes
    }
}
