package io.reified.regolith.server.http

import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecStatus
import io.reified.regolith.protocol.ImageMode
import io.reified.regolith.protocol.NetworkAllow
import io.reified.regolith.protocol.NetworkMode
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.OutputFrame
import io.reified.regolith.protocol.OutputKind
import io.reified.regolith.protocol.SandboxInfo
import io.reified.regolith.protocol.SandboxState
import io.reified.regolith.protocol.ServerDefaults
import io.reified.regolith.protocol.ServerLimits
import io.reified.regolith.protocol.SessionEndInfo
import io.reified.regolith.protocol.SessionInfo
import io.reified.regolith.server.app.ExecRequest
import io.reified.regolith.server.app.LifecycleRequest
import io.reified.regolith.server.app.SandboxPatch
import io.reified.regolith.server.app.SandboxRequest
import io.reified.regolith.server.app.Sessions
import io.reified.regolith.server.config.ServerConfig
import io.reified.regolith.server.domain.Cidr
import io.reified.regolith.server.domain.EntryType
import io.reified.regolith.server.domain.Exec
import io.reified.regolith.server.domain.ExecCommand
import io.reified.regolith.server.domain.ExecOutcome
import io.reified.regolith.server.domain.FileEntry
import io.reified.regolith.server.domain.ImageCatalog
import io.reified.regolith.server.domain.ImagePolicy
import io.reified.regolith.server.domain.Lifecycle
import io.reified.regolith.server.domain.NetworkPolicy
import io.reified.regolith.server.domain.RegolithError
import io.reified.regolith.server.domain.Resources
import io.reified.regolith.server.domain.Sandbox
import io.reified.regolith.server.domain.SessionEnd
import io.reified.regolith.server.domain.requireValid
import io.reified.regolith.server.output.Frame
import io.reified.regolith.server.output.FrameKind
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import io.reified.regolith.server.ports.PublishedSite
import io.reified.regolith.protocol.PublishedSite as WirePublishedSite
import io.reified.regolith.protocol.CreateSandboxRequest as WireCreateSandbox
import io.reified.regolith.protocol.EntryType as WireEntryType
import io.reified.regolith.protocol.ExecOutcome as WireOutcome
import io.reified.regolith.protocol.ExecRequest as WireExecRequest
import io.reified.regolith.protocol.FileEntry as WireFileEntry
import io.reified.regolith.protocol.ImagePolicy as WireImagePolicy
import io.reified.regolith.protocol.Lifecycle as WireLifecycle
import io.reified.regolith.protocol.LifecycleSpec as WireLifecycleSpec
import io.reified.regolith.protocol.NetworkPolicy as WireNetworkPolicy
import io.reified.regolith.protocol.Resources as WireResources
import io.reified.regolith.protocol.UpdateSandboxRequest as WireUpdateSandbox

/*
 * the only place that knows both the wire types and the domain. requests are validated on the way
 * in by the domain constructors; responses are plain copies on the way out.
 */

internal fun Sandbox.toInfo(session: Sessions.Session?, lastEnd: SessionEnd?, images: ImageCatalog): SandboxInfo = SandboxInfo(
    name = name.value,
    image = images.resolveOrNull(imagePolicy),
    imagePolicy = imagePolicy.toWire(),
    resources = resources.toWire(),
    network = network.toWire(),
    lifecycle = lifecycle.toWire(),
    env = env,
    labels = labels,
    state = if (session != null) SandboxState.RUNNING else SandboxState.STOPPED,
    session = session?.let { SessionInfo(it.startedAt, it.lastActiveAt, it.expiresAt, it.image) },
    lastSessionEnd = lastEnd?.let { SessionEndInfo(it.reason.name.lowercase(), it.at) },
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
    deleteAfter = deleteAfter,
)

internal fun Resources.toWire() = WireResources(cpus, memoryMb, homeMb)

internal fun ImagePolicy.toWire(): WireImagePolicy = when (this) {
    ImagePolicy.Default -> WireImagePolicy(ImageMode.DEFAULT)
    is ImagePolicy.Track -> WireImagePolicy(ImageMode.TRACK, repository)
    is ImagePolicy.Pin -> WireImagePolicy(ImageMode.PIN, reference)
}

internal fun WireImagePolicy.toDomain(): ImagePolicy = when (mode) {
    ImageMode.DEFAULT -> {
        requireValid(image == null) { "image is only meaningful with the track and pin modes" }
        ImagePolicy.Default
    }
    ImageMode.TRACK -> ImagePolicy.Track(named())
    ImageMode.PIN -> ImagePolicy.Pin(named())
}

private fun WireImagePolicy.named(): String =
    image ?: throw RegolithError.Invalid("image is required with the `${mode.name.lowercase()}` mode")

internal fun Lifecycle.toWire() = WireLifecycle(
    idleStopSeconds = idleStop.inWholeSeconds.toInt(),
    maxSessionSeconds = maxSession.inWholeSeconds.toInt(),
    retainDays = retain.inWholeDays.toInt(),
)

internal fun NetworkPolicy.toWire(): WireNetworkPolicy = when (this) {
    NetworkPolicy.Public -> WireNetworkPolicy(NetworkMode.PUBLIC)
    NetworkPolicy.None -> WireNetworkPolicy(NetworkMode.NONE)
    is NetworkPolicy.Allowlist -> WireNetworkPolicy(NetworkMode.ALLOWLIST, cidrs.map { NetworkAllow(it.value) })
}

internal fun WireNetworkPolicy.toDomain(): NetworkPolicy {
    requireValid(mode == NetworkMode.ALLOWLIST || allow.isEmpty()) { "allow is only meaningful with the allowlist mode" }

    return when (mode) {
        NetworkMode.PUBLIC -> NetworkPolicy.Public
        NetworkMode.NONE -> NetworkPolicy.None
        NetworkMode.ALLOWLIST -> NetworkPolicy.Allowlist(allow.map { Cidr.parse(it.cidr) })
    }
}

internal fun WireLifecycleSpec.toDomain() = LifecycleRequest(
    idleStop = idleStopSeconds?.seconds,
    maxSession = maxSessionSeconds?.seconds,
    retain = retainDays?.days,
)

internal fun WireCreateSandbox.toDomain() = SandboxRequest(
    imagePolicy = imagePolicy?.toDomain(),
    cpus = resources?.cpus,
    memoryMb = resources?.memoryMb,
    homeMb = resources?.homeMb,
    network = network?.toDomain(),
    lifecycle = lifecycle?.toDomain() ?: LifecycleRequest(),
    env = env,
    labels = labels,
)

internal fun WireUpdateSandbox.toDomain() = SandboxPatch(
    imagePolicy = imagePolicy?.toDomain(),
    network = network?.toDomain(),
    lifecycle = lifecycle?.toDomain(),
    env = env,
    labels = labels,
)

internal fun WireExecRequest.toDomain(): ExecRequest {
    requireValid((shell == null) != (argv == null)) { "Set exactly one of shell and argv" }

    return ExecRequest(
        command = shell?.let(ExecCommand::Shell) ?: ExecCommand.Argv(checkNotNull(argv)),
        cwd = cwd,
        env = env,
        timeout = timeoutSeconds?.also { requireValid(it > 0) { "timeoutSeconds must be positive" } }?.seconds,
        stdin = stdin,
    )
}

internal fun Exec.toInfo(stdinOpen: Boolean) = ExecInfo(
    id = id.value,
    shell = (command as? ExecCommand.Shell)?.script,
    argv = (command as? ExecCommand.Argv)?.args,
    cwd = cwd,
    status = if (finished) ExecStatus.FINISHED else ExecStatus.RUNNING,
    outcome = outcome?.toWire(),
    startedAt = startedAt,
    finishedAt = finishedAt,
    outputEnd = outputEnd,
    outputTruncated = outputTruncated,
    stdinOpen = stdinOpen,
)

internal fun ExecOutcome.toWire(): WireOutcome = when (this) {
    is ExecOutcome.Exited -> WireOutcome(OutcomeType.EXITED, exitCode = code, reason = cause?.name?.lowercase())
    ExecOutcome.TimedOut -> WireOutcome(OutcomeType.TIMED_OUT)
    ExecOutcome.Cancelled -> WireOutcome(OutcomeType.CANCELLED)
    is ExecOutcome.Interrupted -> WireOutcome(OutcomeType.INTERRUPTED, reason = reason.name.lowercase())
}

internal fun Frame.toWire() = OutputFrame(
    kind = when (kind) {
        FrameKind.STDOUT -> OutputKind.STDOUT
        FrameKind.STDERR -> OutputKind.STDERR
        FrameKind.GAP -> OutputKind.GAP
    },
    text = if (kind == FrameKind.GAP) "" else text,
    droppedBytes = droppedBytes,
    end = end,
)

internal fun FileEntry.toWire() = WireFileEntry(
    path = path,
    name = name,
    type = when (type) {
        EntryType.FILE -> WireEntryType.FILE
        EntryType.DIRECTORY -> WireEntryType.DIRECTORY
        EntryType.SYMLINK -> WireEntryType.SYMLINK
        EntryType.OTHER -> WireEntryType.OTHER
    },
    size = size,
    modifiedAt = modifiedAt,
    mode = mode,
)

internal fun ServerConfig.Defaults.toWire(image: String) = ServerDefaults(
    image = image,
    resources = resources.toWire(),
    network = network.toWire(),
    lifecycle = lifecycle.toWire(),
    execTimeoutSeconds = execTimeout.inWholeSeconds.toInt(),
)

internal fun ServerConfig.Limits.toWire(images: List<String>) = ServerLimits(
    images = images,
    maxCpus = maxCpus,
    maxMemoryMb = maxMemoryMb,
    maxHomeMb = maxHomeMb,
    maxSessionSeconds = maxSession.inWholeSeconds.toInt(),
    maxRetainDays = maxRetain.inWholeDays.toInt(),
    maxExecTimeoutSeconds = maxExecTimeout.inWholeSeconds.toInt(),
    maxFileBytes = maxFileBytes,
    maxOutputBytes = maxOutputBytes,
    maxLabels = maxLabels,
    unattendedCpuSeconds = unattendedCpu.inWholeSeconds.toInt(),
)

/** A published site as the API reports it. */
internal fun PublishedSite.toWire(): WirePublishedSite = WirePublishedSite(
    site = site,
    url = url,
    release = release,
    files = files,
    bytes = bytes,
    publishedAt = publishedAt,
)
