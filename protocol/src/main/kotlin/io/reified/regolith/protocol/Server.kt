package io.reified.regolith.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `GET /v1/info`. The server is authoritative about its defaults and limits; a client
 * reads them here instead of keeping a copy.
 */
@Serializable
public data class ServerInfo(
    val version: String,
    val protocol: Int,
    val defaults: ServerDefaults,
    val limits: ServerLimits,
    /** Whether this server can publish a sandbox's files to the web; false means `publish` refuses. */
    val publishing: Boolean = false,
)

@Serializable
public data class ServerDefaults(
    val image: String,
    val resources: Resources,
    val network: NetworkPolicy,
    val lifecycle: Lifecycle,
    val execTimeoutSeconds: Int,
)

@Serializable
public data class ServerLimits(
    val images: List<String>,
    val maxCpus: Double,
    val maxMemoryMb: Int,
    val maxHomeMb: Int,
    val maxSessionSeconds: Int,
    val maxRetainDays: Int,
    val maxExecTimeoutSeconds: Int,
    val maxFileBytes: Long,
    val maxOutputBytes: Long,
    val maxLabels: Int,
    val unattendedCpuSeconds: Int,
)

@Serializable
public enum class HealthStatus {
    @SerialName("ok")
    OK,

    @SerialName("failing")
    FAILING,
}

@Serializable
public data class CheckInfo(
    val status: HealthStatus,
    val detail: String? = null,
)

/** Body of `GET /v1/health`, served without authentication. */
@Serializable
public data class HealthInfo(
    val status: HealthStatus,
    val checks: Map<String, CheckInfo>,
)

/** Media type of every error response. */
public const val PROBLEM_JSON: String = "application/problem+json"

/**
 * Body of every error response: an RFC 9457 problem document, served as [PROBLEM_JSON].
 *
 * [code] is the stable member to branch on. [type] is `urn:regolith:error:<code>`, [title] names the
 * kind of problem, and [detail] explains this occurrence for people.
 */
@Serializable
public data class ErrorBody(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val code: String,
)

/**
 * Stable machine-readable error codes. Kept as strings rather than an enum so a client never
 * fails to decode a code added by a newer server.
 */
public object ErrorCodes {
    public const val UNAUTHORIZED: String = "unauthorized"
    public const val INVALID_REQUEST: String = "invalid_request"
    public const val NOT_FOUND: String = "not_found"
    public const val CONFLICT: String = "conflict"
    public const val BUSY: String = "busy"
    public const val CAPACITY_EXHAUSTED: String = "capacity_exhausted"
    public const val PAYLOAD_TOO_LARGE: String = "payload_too_large"
    public const val INSUFFICIENT_STORAGE: String = "insufficient_storage"
    public const val UNAVAILABLE: String = "unavailable"
    public const val NOT_IMPLEMENTED: String = "not_implemented"
    public const val INTERNAL: String = "internal"

    /** The problem `type` URI of [code]. */
    public fun typeOf(code: String): String = "urn:regolith:error:$code"
}
