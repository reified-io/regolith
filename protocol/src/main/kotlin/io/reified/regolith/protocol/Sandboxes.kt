package io.reified.regolith.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** How a sandbox picks the image its sessions run; see [ImagePolicy]. */
@Serializable
public enum class ImageMode {
    /** The server's default image, whichever that is when a session starts. */
    @SerialName("default")
    DEFAULT,

    /** The image this server offers for one repository, whichever version that is. */
    @SerialName("track")
    TRACK,

    /** One exact reference, which never moves while the sandbox exists. */
    @SerialName("pin")
    PIN,
}

/**
 * How a sandbox chooses the image its sessions run. The choice is made again at the start of every
 * session, so a sandbox that follows the server picks up a newer image without being recreated.
 *
 * [image] is what [ImageMode.TRACK] tracks or [ImageMode.PIN] pins, and is meaningless with
 * [ImageMode.DEFAULT]. Tracking names a repository — an image from `GET /v1/info` without its tag
 * or digest; pinning names one of those images exactly.
 */
@Serializable
public data class ImagePolicy(val mode: ImageMode, val image: String? = null)

/**
 * Body of `POST /v1/sandboxes`. Every field is optional; an omitted one takes the server default
 * advertised by `GET /v1/info`.
 *
 * [alias] is the caller's own name for the sandbox — the identity it stands for, such as a user or a
 * project — and asking again with the same one returns that sandbox unchanged instead of making a
 * second. Without an alias, every call creates a sandbox the caller has to remember the id of.
 */
@Serializable
public data class CreateSandboxRequest(
    val alias: String? = null,
    val imagePolicy: ImagePolicy? = null,
    val resources: ResourcesSpec? = null,
    val network: NetworkPolicy? = null,
    val lifecycle: LifecycleSpec? = null,
    val env: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
)

/**
 * Body of `PATCH /v1/sandboxes/{id}`. Only the fields that are present change. A network policy
 * applies to a running session immediately; everything else applies from the next session. An
 * [alias] no other sandbox holds moves it to this one.
 */
@Serializable
public data class UpdateSandboxRequest(
    val alias: String? = null,
    val imagePolicy: ImagePolicy? = null,
    val network: NetworkPolicy? = null,
    val lifecycle: LifecycleSpec? = null,
    val env: Map<String, String>? = null,
    val labels: Map<String, String>? = null,
)

/** Requested resources; the home size is fixed when the sandbox is created. */
@Serializable
public data class ResourcesSpec(
    val cpus: Double? = null,
    val memoryMb: Int? = null,
    val homeMb: Int? = null,
)

/** Effective resources of a sandbox. */
@Serializable
public data class Resources(
    val cpus: Double,
    val memoryMb: Int,
    val homeMb: Int,
)

/** Requested lifecycle; `retainDays` of `0` makes the sandbox ephemeral. */
@Serializable
public data class LifecycleSpec(
    val idleStopSeconds: Int? = null,
    val maxSessionSeconds: Int? = null,
    val retainDays: Int? = null,
)

/**
 * Effective lifecycle of a sandbox.
 *
 * A session stops after [idleStopSeconds] without activity and after [maxSessionSeconds] in any
 * case. The whole sandbox, home included, is deleted once it has gone unused for [retainDays];
 * with `0` it is deleted as soon as its session stops.
 */
@Serializable
public data class Lifecycle(
    val idleStopSeconds: Int,
    val maxSessionSeconds: Int,
    val retainDays: Int,
)

/** How a sandbox may reach the network. Private and host addresses are unreachable in every mode. */
@Serializable
public enum class NetworkMode {
    /** The public internet. */
    @SerialName("public")
    PUBLIC,

    /** Nothing at all, DNS included. */
    @SerialName("none")
    NONE,

    /** Only the destinations listed in [NetworkPolicy.allow]. */
    @SerialName("allowlist")
    ALLOWLIST,
}

/** A network policy; [allow] is meaningful only for [NetworkMode.ALLOWLIST]. */
@Serializable
public data class NetworkPolicy(
    val mode: NetworkMode,
    val allow: List<NetworkAllow> = emptyList(),
)

/** One allowed destination. An object rather than a string, so domain rules can join it later. */
@Serializable
public data class NetworkAllow(
    val cidr: String,
)

/** Whether a sandbox currently has a running session. */
@Serializable
public enum class SandboxState {
    @SerialName("stopped")
    STOPPED,

    @SerialName("starting")
    STARTING,

    @SerialName("running")
    RUNNING,

    @SerialName("stopping")
    STOPPING,
}

/**
 * The running instance of a sandbox; a sandbox spans as many sessions as it is used for.
 *
 * [image] is what this session actually started on. It differs from the sandbox's
 * [SandboxInfo.image] when the server has been offered a newer image since — the session keeps the
 * one it started with, and the next one takes the newer.
 */
@Serializable
public data class SessionInfo(
    val startedAt: Instant,
    val lastActiveAt: Instant,
    val expiresAt: Instant,
    val image: String,
)

/**
 * How a sandbox's last session ended. [reason] is one of [Reasons]; a client should accept values it
 * does not know.
 */
@Serializable
public data class SessionEndInfo(
    val reason: String,
    val at: Instant,
)

/**
 * A sandbox as the server reports it. [id] is what addresses it; [alias] is the caller's own name for
 * it, when it gave one. [lastSessionEnd] is known only for sessions this server ended.
 *
 * [image] is the exact reference the sandbox's next session runs, and is null only when the server
 * no longer offers the image [imagePolicy] tracks, which no session can start on. [deleteAfter] is
 * when retention deletes the sandbox unless it is used first, and is absent while it has a site up:
 * retention never takes a sandbox whose address people hold.
 */
@Serializable
public data class SandboxInfo(
    val id: String,
    val alias: String? = null,
    val image: String?,
    val imagePolicy: ImagePolicy,
    val resources: Resources,
    val network: NetworkPolicy,
    val lifecycle: Lifecycle,
    val env: Map<String, String>,
    val labels: Map<String, String>,
    val state: SandboxState,
    val session: SessionInfo? = null,
    val lastSessionEnd: SessionEndInfo? = null,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val deleteAfter: Instant? = null,
)

/** One page of `GET /v1/sandboxes`. */
@Serializable
public data class SandboxPage(
    val sandboxes: List<SandboxInfo>,
    val nextCursor: String? = null,
)

/**
 * Body of `POST /v1/sandboxes/{id}/site`. [path] is the directory inside the sandbox to publish,
 * relative to the home unless it is absolute.
 */
@Serializable
public data class PublishRequest(
    val path: String,
)

/**
 * A published site. [release] identifies the exact snapshot behind [url]; publishing again replaces it
 * atomically, and nothing of the sandbox except the files of that snapshot is reachable at it.
 *
 * [url] is the server's to choose and the only address the site has: it is built from a label made
 * for this site alone, so it says nothing about the sandbox or the caller behind it.
 *
 * [hasIndex] says whether the snapshot holds an `index.html` at its top, the page [url] itself
 * opens; without one the address answers with nothing, which is the one publishing mistake the
 * numbers above cannot show.
 */
@Serializable
public data class PublishedSite(
    val url: String,
    val release: String,
    val files: Int,
    val bytes: Long,
    val publishedAt: Instant,
    val hasIndex: Boolean = true,
)
