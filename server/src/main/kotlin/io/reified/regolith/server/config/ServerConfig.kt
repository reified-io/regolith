package io.reified.regolith.server.config

import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Resources
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Everything the server is configured with, read once at startup from `REGOLITH_*` variables. */
data class ServerConfig(
    val bind: String,
    val port: Int,
    val token: String,
    val namespace: String,
    val stateDir: Path,
    val docker: String,
    val shell: String,
    val maxSessions: Int,
    val minFreeMb: Long,
    val helperImage: String?,
    val pagesUrl: String?,
    val pagesToken: String?,
    val blockedCidrs: List<Cidr>,
    val homeReadBps: String?,
    val homeWriteBps: String?,
    val defaults: Defaults,
    val limits: Limits,
) {

    /** Values a sandbox gets for whatever its creator left out. */
    data class Defaults(
        val image: String,
        val resources: Resources,
        val network: NetworkPolicy,
        val lifecycle: Lifecycle,
        val execTimeout: Duration,
    )

    /** Ceilings no request can exceed. */
    data class Limits(
        val images: List<String>,
        val maxCpus: Double,
        val maxMemoryMb: Int,
        val maxHomeMb: Int,
        val maxSession: Duration,
        val maxRetain: Duration,
        val maxExecTimeout: Duration,
        val maxFileBytes: Long,
        val maxOutputBytes: Long,
        val maxLabels: Int,
        val maxExecsPerSandbox: Int,
        val execsRetained: Int,
        val unattendedCpu: Duration,
    )

    override fun toString(): String = "ServerConfig(bind=$bind, port=$port, namespace=$namespace, stateDir=$stateDir)"

    companion object {
        const val MIN_TOKEN_CHARS = 32

        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            version: String = serverVersion(),
        ): ServerConfig {
            val read = EnvReader(env)
            val defaultImage = read.image("REGOLITH_IMAGE") ?: "ghcr.io/reified-io/regolith-sandbox:$version"
            val extraImages = read.list("REGOLITH_ALLOWED_IMAGES").onEach { requirePinned("REGOLITH_ALLOWED_IMAGES", it) }

            val maxCpus = read.double("REGOLITH_MAX_CPUS", 2.0)
            val maxMemoryMb = read.int("REGOLITH_MAX_MEMORY_MB", 4096)
            val maxHomeMb = read.int("REGOLITH_MAX_HOME_MB", 16_384)
            val maxSession = read.int("REGOLITH_MAX_SESSION_SECONDS", 86_400).seconds
            val maxRetain = read.int("REGOLITH_MAX_RETAIN_DAYS", 90).days
            val maxExecTimeout = read.int("REGOLITH_MAX_EXEC_TIMEOUT_SECONDS", 3600).seconds

            val limits = Limits(
                images = (listOf(defaultImage) + extraImages).distinct(),
                maxCpus = maxCpus,
                maxMemoryMb = maxMemoryMb,
                maxHomeMb = maxHomeMb,
                maxSession = maxSession,
                maxRetain = maxRetain,
                maxExecTimeout = maxExecTimeout,
                maxFileBytes = read.int("REGOLITH_MAX_FILE_MB", 64) * MIB,
                maxOutputBytes = read.int("REGOLITH_MAX_OUTPUT_MB", 8) * MIB,
                maxLabels = 32,
                maxExecsPerSandbox = read.int("REGOLITH_MAX_EXECS_PER_SANDBOX", 8),
                execsRetained = 50,
                unattendedCpu = read.int("REGOLITH_UNATTENDED_CPU_SECONDS", 600).seconds,
            )
            val defaults = Defaults(
                image = defaultImage,
                resources = Resources(
                    cpus = read.double("REGOLITH_CPUS", 1.0).also { check(it <= maxCpus) { "REGOLITH_CPUS exceeds REGOLITH_MAX_CPUS" } },
                    memoryMb = read.int("REGOLITH_MEMORY_MB", 1024).also { check(it <= maxMemoryMb) { "REGOLITH_MEMORY_MB exceeds REGOLITH_MAX_MEMORY_MB" } },
                    homeMb = read.int("REGOLITH_HOME_MB", 4096).also { check(it <= maxHomeMb) { "REGOLITH_HOME_MB exceeds REGOLITH_MAX_HOME_MB" } },
                ),
                network = when (val mode = env["REGOLITH_NETWORK"]?.trim().orEmpty().ifEmpty { "public" }) {
                    "public" -> NetworkPolicy.Public
                    "none" -> NetworkPolicy.None
                    else -> error("REGOLITH_NETWORK must be `public` or `none`, got `$mode`")
                },
                lifecycle = Lifecycle(
                    idleStop = read.int("REGOLITH_IDLE_STOP_SECONDS", 900).seconds,
                    maxSession = maxSession,
                    retain = read.int("REGOLITH_RETAIN_DAYS", 30).days.also { check(it <= maxRetain) { "REGOLITH_RETAIN_DAYS exceeds REGOLITH_MAX_RETAIN_DAYS" } },
                ),
                execTimeout = read.int("REGOLITH_EXEC_TIMEOUT_SECONDS", 120).seconds
                    .also { check(it <= maxExecTimeout) { "REGOLITH_EXEC_TIMEOUT_SECONDS exceeds REGOLITH_MAX_EXEC_TIMEOUT_SECONDS" } },
            )

            val pagesUrl = env["REGOLITH_PAGES_URL"]?.trim()?.ifEmpty { null }
            val namespace = env["REGOLITH_NAMESPACE"]?.trim().orEmpty().ifEmpty { "regolith" }
            check(NAMESPACE.matches(namespace)) { "REGOLITH_NAMESPACE must be 1-24 lowercase letters, digits and hyphens" }

            return ServerConfig(
                bind = env["REGOLITH_BIND"]?.trim().orEmpty().ifEmpty { "0.0.0.0" },
                port = read.int("REGOLITH_PORT", 8080),
                token = readToken(env),
                namespace = namespace,
                stateDir = Path.of(env["REGOLITH_STATE_DIR"]?.trim().orEmpty().ifEmpty { "/var/lib/regolith" }),
                docker = env["REGOLITH_DOCKER"]?.trim().orEmpty().ifEmpty { "docker" },
                shell = env["REGOLITH_SHELL"]?.trim().orEmpty().ifEmpty { "/bin/bash" },
                maxSessions = read.int("REGOLITH_MAX_SESSIONS", 2),
                minFreeMb = read.int("REGOLITH_MIN_FREE_MB", 2048).toLong(),
                helperImage = env["REGOLITH_HELPER_IMAGE"]?.trim()?.ifEmpty { null },
                pagesUrl = pagesUrl,
                pagesToken = pagesUrl?.let { readPagesToken(env) },
                blockedCidrs = read.list("REGOLITH_BLOCKED_CIDRS").map { raw ->
                    try {
                        Cidr.parse(raw)
                    } catch (e: RegolithError.Invalid) {
                        error("REGOLITH_BLOCKED_CIDRS: ${e.message}")
                    }
                },
                homeReadBps = read.rate("REGOLITH_HOME_READ_BPS", "100mb"),
                homeWriteBps = read.rate("REGOLITH_HOME_WRITE_BPS", "50mb"),
                defaults = defaults,
                limits = limits,
            )
        }

        private const val MIB = 1024L * 1024L
        private val NAMESPACE = Regex("[a-z][a-z0-9-]{0,23}")

        /** The token of the pages role, which only matters when this server publishes to one. */
        private fun readPagesToken(env: Map<String, String>): String {
            val inline = env["REGOLITH_PAGES_TOKEN"]?.trim().orEmpty()
            val file = env["REGOLITH_PAGES_TOKEN_FILE"]?.trim().orEmpty()
            check(inline.isEmpty() || file.isEmpty()) { "Set REGOLITH_PAGES_TOKEN or REGOLITH_PAGES_TOKEN_FILE, not both" }
            val token = if (file.isNotEmpty()) Files.readString(Path.of(file)).trim() else inline
            check(token.length >= MIN_TOKEN_CHARS) { "REGOLITH_PAGES_URL needs REGOLITH_PAGES_TOKEN of at least $MIN_TOKEN_CHARS characters" }

            return token
        }

        private fun readToken(env: Map<String, String>): String {
            val inline = env["REGOLITH_TOKEN"]?.trim().orEmpty()
            val file = env["REGOLITH_TOKEN_FILE"]?.trim().orEmpty()
            check(inline.isEmpty() || file.isEmpty()) { "Set REGOLITH_TOKEN or REGOLITH_TOKEN_FILE, not both" }
            val token = if (file.isNotEmpty()) Files.readString(Path.of(file)).trim() else inline
            // there is no unauthenticated mode: whoever reaches the api could otherwise run code.
            check(token.isNotEmpty()) { "REGOLITH_TOKEN or REGOLITH_TOKEN_FILE is required" }
            check(token.length >= MIN_TOKEN_CHARS) { "The API token must be at least $MIN_TOKEN_CHARS characters" }

            return token
        }

        /** Rejects references that can change under the server: no tag, or `latest`. */
        internal fun requirePinned(name: String, image: String) {
            val lastSegment = image.substringAfterLast('/')
            val pinned = "@sha256:" in image || (':' in lastSegment && !lastSegment.endsWith(":latest"))
            check(pinned) { "$name must name a tag other than `latest` or a digest, got `$image`" }
        }

        private fun serverVersion(): String = ServerConfig::class.java.`package`?.implementationVersion ?: "dev"
    }
}

private class EnvReader(private val env: Map<String, String>) {
    fun int(name: String, fallback: Int): Int {
        val raw = env[name]?.trim().orEmpty().ifEmpty { return fallback }
        val value = checkNotNull(raw.toIntOrNull()) { "$name must be a whole number, got `$raw`" }
        check(value > 0 || (name == "REGOLITH_RETAIN_DAYS" && value == 0)) { "$name must be positive, got `$raw`" }

        return value
    }

    fun double(name: String, fallback: Double): Double {
        val raw = env[name]?.trim().orEmpty().ifEmpty { return fallback }
        val value = checkNotNull(raw.toDoubleOrNull()) { "$name must be a number, got `$raw`" }
        check(value > 0) { "$name must be positive, got `$raw`" }

        return value
    }

    fun list(name: String): List<String> = env[name].orEmpty().split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** A docker byte rate such as `50mb`, or `none` for no cap. */
    fun rate(name: String, fallback: String): String? {
        val raw = env[name]?.trim().orEmpty().ifEmpty { return fallback }
        if (raw == "none") return null
        check(Regex("[1-9][0-9]*(kb|mb|gb)").matches(raw)) { "$name must be a rate such as `50mb`, or `none`, got `$raw`" }

        return raw
    }

    fun image(name: String): String? = env[name]?.trim()?.ifEmpty { null }?.also { ServerConfig.requirePinned(name, it) }
}
