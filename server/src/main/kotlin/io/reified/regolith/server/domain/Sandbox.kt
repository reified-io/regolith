package io.reified.regolith.server.domain

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/** Fixed facts about the inside of every sandbox; part of the public contract. */
object SandboxLayout {
    /** The persistent home, mounted from the sandbox's own disk. Relative paths resolve against it. */
    const val HOME = "/home/sandbox"

    /** Every process in a sandbox runs as this uid and gid, with no capabilities. */
    const val UID = 1000
}

/**
 * A sandbox: the durable part — its id, the caller's [alias] for it, its configuration and its home.
 * Its running container is a session, tracked separately, and a sandbox outlives as many sessions as
 * it is used for. [site] is the label its published files are served at, while any are.
 */
@Serializable
data class Sandbox(
    val id: SandboxId,
    val alias: Alias?,
    val site: SiteLabel? = null,
    val imagePolicy: ImagePolicy,
    val resources: Resources,
    val network: NetworkPolicy,
    val lifecycle: Lifecycle,
    val env: Map<String, String>,
    val labels: Map<String, String>,
    val createdAt: Instant,
    val lastUsedAt: Instant,
) {

    /**
     * When the retention sweep may delete this sandbox. An ephemeral one still gets its idle
     * window, so a sandbox created a moment before its first command is not swept in between.
     */
    val deleteAfter: Instant
        get() = lastUsedAt + if (lifecycle.retain == Duration.ZERO) lifecycle.idleStop else lifecycle.retain
}

@Serializable
data class Resources(
    val cpus: Double,
    val memoryMb: Int,
    val homeMb: Int,
)

@Serializable
data class Lifecycle(
    val idleStop: Duration,
    val maxSession: Duration,
    val retain: Duration,
)

/** Environment variables and labels share these shape rules wherever they are accepted. */
object Metadata {
    private val envKey = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    private val labelKey = Regex("[a-z0-9][a-z0-9._/-]{0,62}")
    private const val MAX_ENV_ENTRIES = 128
    private const val MAX_VALUE_CHARS = 4096

    fun requireEnv(env: Map<String, String>) {
        requireValid(env.size <= MAX_ENV_ENTRIES) { "At most $MAX_ENV_ENTRIES environment variables are allowed" }

        for ((key, value) in env) {
            requireValid(envKey.matches(key)) { "`$key` is not a valid environment variable name" }
            requireValid(value.length <= MAX_VALUE_CHARS) { "The value of `$key` is longer than $MAX_VALUE_CHARS characters" }
            // a process argument cannot carry a NUL, so a value holding one could never reach the command.
            requireValid('\u0000' !in value) { "The value of `$key` must not contain NUL" }
        }
    }

    fun requireLabels(labels: Map<String, String>, maxLabels: Int) {
        requireValid(labels.size <= maxLabels) { "At most $maxLabels labels are allowed" }

        for ((key, value) in labels) {
            requireValid(labelKey.matches(key)) { "`$key` is not a valid label key" }
            requireValid(value.length <= 256) { "The label `$key` is longer than 256 characters" }
        }
    }
}
